package com.ostracker.service

import com.ostracker.api.AdesivadorResponse
import com.ostracker.domain.Adesivador
import com.ostracker.domain.PerfilNome
import com.ostracker.domain.TipoColunaAgenda
import com.ostracker.repository.AdesivadorRepository
import com.ostracker.repository.AgendamentoRepository
import com.ostracker.security.UsuarioAutenticado
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/** O que aconteceu ao remover: apagado de vez, ou so tirado da agenda para guardar o historico. */
enum class Remocao { APAGADO, RETIRADO_DA_AGENDA }

data class RemocaoResponse(val resultado: Remocao, val mensagem: String)

/**
 * As colunas da agenda (os adesivadores, mais Encaixe e Noturno): adicionar, renomear,
 * mudar a ordem, remover e restaurar. So Diretoria e Administrador.
 *
 * Remover nao apaga historico. Quem ainda tem carro de hoje em diante nao sai - os carros
 * sumiriam da tela. Quem so tem carros no passado sai da agenda, mas continua nas semanas
 * em que trabalhou, no relatorio e na produtividade, e pode ser restaurado. Quem nunca
 * teve carro e apagado de vez.
 */
@Service
class ColunasAgendaService(
    private val adesivadorRepository: AdesivadorRepository,
    private val agendamentoRepository: AgendamentoRepository
) {

    @Transactional
    fun adicionar(nome: String, autor: UsuarioAutenticado): AdesivadorResponse {
        garantirPodeEditar(autor)
        val limpo = limparNome(nome)
        val existente = adesivadorRepository.findByNomeIgnoreCase(limpo)
        if (existente != null) {
            if (existente.ativo) throw RegraDeNegocioException("$limpo ja esta na agenda.")
            // Ja trabalhou aqui e foi removido: volta, com o historico.
            existente.ativo = true
            return entrarNaFila(existente).paraResponse()
        }
        return entrarNaFila(
            adesivadorRepository.save(Adesivador(nome = limpo, tipo = TipoColunaAgenda.ADESIVADOR, ordem = 0))
        ).paraResponse()
    }

    @Transactional
    fun renomear(id: Int, nome: String, autor: UsuarioAutenticado): AdesivadorResponse {
        garantirPodeEditar(autor)
        val coluna = carregar(id)
        val limpo = limparNome(nome)
        adesivadorRepository.findByNomeIgnoreCase(limpo)?.let {
            if (it.id != id) throw RegraDeNegocioException("Ja existe uma coluna chamada $limpo.")
        }
        coluna.nome = limpo
        return adesivadorRepository.save(coluna).paraResponse()
    }

    /**
     * Nova ordem das colunas que estao na agenda, da esquerda para a direita. As removidas
     * vao para o fim, na ordem em que estavam.
     */
    @Transactional
    fun reordenar(ids: List<Int>, autor: UsuarioAutenticado): List<AdesivadorResponse> {
        garantirPodeEditar(autor)
        val todas = adesivadorRepository.findAllByOrderByOrdemAsc()
        val ativas = todas.filter { it.ativo }
        if (ids.toSet() != ativas.mapNotNull { it.id }.toSet() || ids.size != ativas.size) {
            throw RegraDeNegocioException("A nova ordem precisa ter todas as colunas da agenda, uma vez cada.")
        }
        val porId = ativas.associateBy { it.id!! }
        val novaOrdem = ids.map { porId.getValue(it) } + todas.filter { !it.ativo }
        novaOrdem.forEachIndexed { i, coluna -> coluna.ordem = i + 1 }
        adesivadorRepository.saveAll(novaOrdem)
        return novaOrdem.map { it.paraResponse() }
    }

    @Transactional
    fun remover(id: Int, autor: UsuarioAutenticado): RemocaoResponse {
        garantirPodeEditar(autor)
        val coluna = carregar(id)
        val futuros = agendamentoRepository.countByAdesivadorIdAndDataGreaterThanEqual(id, LocalDate.now())
        if (futuros > 0) {
            throw RegraDeNegocioException(
                "${coluna.nome} tem $futuros carro(s) agendado(s) de hoje em diante. " +
                    "Arraste para outro adesivador antes de remover."
            )
        }
        if (agendamentoRepository.countByAdesivadorId(id) == 0L) {
            adesivadorRepository.delete(coluna)
            return RemocaoResponse(Remocao.APAGADO, "${coluna.nome} foi removido da agenda.")
        }
        coluna.ativo = false
        adesivadorRepository.save(coluna)
        return RemocaoResponse(
            Remocao.RETIRADO_DA_AGENDA,
            "${coluna.nome} saiu da agenda. As semanas em que trabalhou continuam no historico."
        )
    }

    @Transactional
    fun restaurar(id: Int, autor: UsuarioAutenticado): AdesivadorResponse {
        garantirPodeEditar(autor)
        val coluna = carregar(id)
        if (!coluna.ativo) {
            coluna.ativo = true
            entrarNaFila(coluna)
        }
        return coluna.paraResponse()
    }

    private fun garantirPodeEditar(autor: UsuarioAutenticado) {
        if (autor.perfil != PerfilNome.DIRETORIA && autor.perfil != PerfilNome.ADMIN) {
            throw PermissaoNegadaException("Somente a Diretoria e o Administrador editam os adesivadores da agenda.")
        }
    }

    /** Como na planilha: maiusculas, sem espaco sobrando. */
    private fun limparNome(nome: String): String {
        val limpo = nome.trim().replace(Regex("\\s+"), " ").uppercase()
        if (limpo.isEmpty()) throw RegraDeNegocioException("Informe o nome.")
        if (limpo.length > 60) throw RegraDeNegocioException("O nome pode ter no maximo 60 caracteres.")
        return limpo
    }

    /**
     * Poe a coluna que entrou (ou voltou) depois do ultimo adesivador, antes de Encaixe e
     * Noturno, e renumera todas: 1..n as da agenda, as removidas depois.
     */
    private fun entrarNaFila(coluna: Adesivador): Adesivador {
        val outras = adesivadorRepository.findAllByOrderByOrdemAsc().filter { it.id != coluna.id }
        val ativas = outras.filter { it.ativo }.toMutableList()
        val depoisDoUltimoAdesivador = ativas.indexOfLast { it.tipo == TipoColunaAgenda.ADESIVADOR } + 1
        ativas.add(if (coluna.tipo == TipoColunaAgenda.ADESIVADOR) depoisDoUltimoAdesivador else ativas.size, coluna)
        val todas = ativas + outras.filter { !it.ativo }
        todas.forEachIndexed { i, c -> c.ordem = i + 1 }
        adesivadorRepository.saveAll(todas)
        return coluna
    }

    private fun carregar(id: Int) = adesivadorRepository.findById(id)
        .orElseThrow { NaoEncontradoException("Coluna $id nao encontrada.") }

    private fun Adesivador.paraResponse() = AdesivadorResponse(id!!, nome, tipo, ordem, ativo)
}
