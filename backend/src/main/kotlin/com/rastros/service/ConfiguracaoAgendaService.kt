package com.rastros.service

import com.rastros.domain.PerfilNome
import com.rastros.domain.StatusAgenda
import com.rastros.domain.StatusAgendamento
import com.rastros.domain.VendedorAgenda
import com.rastros.repository.StatusAgendaRepository
import com.rastros.repository.UsoNaAgendaRepository
import com.rastros.repository.UsuarioRepository
import com.rastros.repository.VendedorAgendaRepository
import com.rastros.security.UsuarioAutenticado
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class StatusAgendaResponse(
    val id: Long,
    /** O status do sistema que ele nomeia; nulo nos criados na agenda. */
    val chave: StatusAgendamento?,
    val nome: String,
    val cor: String,
    val ordem: Int,
    val doSistema: Boolean
)

data class VendedorAgendaResponse(val id: Int, val codigo: String, val nome: String, val ativo: Boolean)

/**
 * A legenda de status e a lista de vendedores da agenda, editadas na propria agenda como
 * os adesivadores. So Diretoria e Administrador.
 */
@Service
class ConfiguracaoAgendaService(
    private val statusRepository: StatusAgendaRepository,
    private val vendedorRepository: VendedorAgendaRepository,
    private val usoNaAgenda: UsoNaAgendaRepository,
    private val usuarioRepository: UsuarioRepository
) {

    // ------------------------------------------------------------------ status

    @Transactional(readOnly = true)
    fun status(): List<StatusAgendaResponse> = statusRepository.findAllByOrderByOrdemAsc().map { it.paraResponse() }

    @Transactional
    fun adicionarStatus(nome: String, cor: String, autor: UsuarioAutenticado): StatusAgendaResponse {
        garantirPodeEditar(autor)
        val limpo = nomeDoStatus(nome)
        statusRepository.findByNomeIgnoreCase(limpo)?.let { throw RegraDeNegocioException("Ja existe o status \"${it.nome}\".") }
        val ultimo = statusRepository.findAllByOrderByOrdemAsc().maxOfOrNull { it.ordem } ?: 0
        return statusRepository.save(StatusAgenda(nome = limpo, cor = validarCor(cor), ordem = ultimo + 1)).paraResponse()
    }

    @Transactional
    fun alterarStatus(id: Long, nome: String, cor: String, autor: UsuarioAutenticado): StatusAgendaResponse {
        garantirPodeEditar(autor)
        val status = carregarStatus(id)
        val limpo = nomeDoStatus(nome)
        statusRepository.findByNomeIgnoreCase(limpo)?.let {
            if (it.id != id) throw RegraDeNegocioException("Ja existe o status \"${it.nome}\".")
        }
        status.nome = limpo
        status.cor = validarCor(cor)
        return statusRepository.save(status).paraResponse()
    }

    /** Nova ordem da legenda (e dos menus), com todos os status, uma vez cada. */
    @Transactional
    fun reordenarStatus(ids: List<Long>, autor: UsuarioAutenticado): List<StatusAgendaResponse> {
        garantirPodeEditar(autor)
        val todos = statusRepository.findAllByOrderByOrdemAsc()
        if (ids.size != todos.size || ids.toSet() != todos.mapNotNull { it.id }.toSet()) {
            throw RegraDeNegocioException("A nova ordem precisa ter todos os status, uma vez cada.")
        }
        val porId = todos.associateBy { it.id!! }
        val nova = ids.map { porId.getValue(it) }
        nova.forEachIndexed { i, s -> s.ordem = i + 1 }
        return statusRepository.saveAll(nova).map { it.paraResponse() }
    }

    /** So os criados na agenda saem; os cards que estavam nele voltam para Programado. */
    @Transactional
    fun removerStatus(id: Long, autor: UsuarioAutenticado): RemocaoResponse {
        garantirPodeEditar(autor)
        val status = carregarStatus(id)
        if (status.doSistema) {
            throw RegraDeNegocioException(
                "\"${status.nome}\" e um status do sistema (tem regra por tras): da para mudar o nome e a cor, mas nao remover."
            )
        }
        val cards = usoNaAgenda.tirarEtiqueta(id)
        statusRepository.delete(status)
        val programado = statusRepository.findAllByOrderByOrdemAsc().firstOrNull { it.chave == StatusAgendamento.PROGRAMADO }?.nome ?: "Programado"
        return RemocaoResponse(
            Remocao.APAGADO,
            if (cards == 0) "O status \"${status.nome}\" foi removido."
            else "O status \"${status.nome}\" foi removido; $cards card(s) voltaram para $programado."
        )
    }

    /** O status criado na agenda que um card vai usar. */
    @Transactional(readOnly = true)
    fun etiqueta(id: Long): StatusAgenda {
        val status = carregarStatus(id)
        if (status.doSistema) throw RegraDeNegocioException("Use o status do sistema \"${status.nome}\" diretamente.")
        return status
    }

    @Transactional(readOnly = true)
    fun etiquetaExiste(id: Long) = statusRepository.existsById(id)

    private fun carregarStatus(id: Long) = statusRepository.findById(id)
        .orElseThrow { NaoEncontradoException("Status $id nao encontrado.") }

    private fun nomeDoStatus(nome: String): String {
        val limpo = nome.trim().replace(Regex("\\s+"), " ")
        if (limpo.isEmpty()) throw RegraDeNegocioException("Informe o nome do status.")
        if (limpo.length > 40) throw RegraDeNegocioException("O nome do status pode ter no maximo 40 caracteres.")
        return limpo
    }

    private fun validarCor(cor: String): String {
        val limpa = cor.trim().lowercase()
        if (!Regex("^#[0-9a-f]{6}$").matches(limpa)) throw RegraDeNegocioException("Cor invalida: use o formato #rrggbb.")
        return limpa
    }

    private fun StatusAgenda.paraResponse() = StatusAgendaResponse(id!!, chave, nome, cor, ordem, doSistema)

    // --------------------------------------------------------------- vendedores

    /** Todos, inclusive os removidos (ativo = false): os cards antigos ainda mostram o nome. */
    @Transactional(readOnly = true)
    fun vendedores(): List<VendedorAgendaResponse> = vendedorRepository.findAllByOrderByNomeAsc().map { it.paraResponse() }

    /**
     * Vendedor novo. Sem codigo, vale a primeira letra do nome; se a letra ja e de outro,
     * e preciso escolher um codigo (ex.: "KA").
     */
    @Transactional
    fun adicionarVendedor(nome: String, codigo: String?, autor: UsuarioAutenticado): VendedorAgendaResponse {
        garantirPodeEditar(autor)
        val limpo = nomeDoVendedor(nome)
        val cod = codigoDoVendedor(codigo?.takeIf { it.isNotBlank() } ?: primeiraLetra(limpo))
        vendedorRepository.findByCodigo(cod)?.let {
            throw RegraDeNegocioException(
                "O codigo $cod ja e de ${it.nome}${if (it.ativo) "" else " (removido - restaure ou escolha outro codigo)"}. " +
                    "Escolha outro codigo, como ${sugestao(limpo)}."
            )
        }
        vendedorRepository.findByNomeIgnoreCase(limpo)?.let {
            if (it.ativo) throw RegraDeNegocioException("${it.nome} ja esta na lista (codigo ${it.codigo}).")
        }
        return vendedorRepository.save(VendedorAgenda(codigo = cod, nome = limpo)).paraResponse()
    }

    /** Muda o nome exibido. O codigo fica: e ele que esta gravado nos cards. */
    @Transactional
    fun renomearVendedor(id: Int, nome: String, autor: UsuarioAutenticado): VendedorAgendaResponse {
        garantirPodeEditar(autor)
        val vendedor = carregarVendedor(id)
        vendedor.nome = nomeDoVendedor(nome)
        return vendedorRepository.save(vendedor).paraResponse()
    }

    /** Sem card na agenda, sai de vez; com cards, sai da lista mas os cards mantem o nome. */
    @Transactional
    fun removerVendedor(id: Int, autor: UsuarioAutenticado): RemocaoResponse {
        garantirPodeEditar(autor)
        val vendedor = carregarVendedor(id)
        if (usoNaAgenda.countByVendedorCodigo(vendedor.codigo) == 0L) {
            vendedorRepository.delete(vendedor)
            return RemocaoResponse(Remocao.APAGADO, "${vendedor.nome} foi removido.")
        }
        vendedor.ativo = false
        vendedorRepository.save(vendedor)
        return RemocaoResponse(
            Remocao.RETIRADO_DA_AGENDA,
            "${vendedor.nome} saiu da lista. Os cards que ja tem o vendedor continuam com o nome dele."
        )
    }

    @Transactional
    fun restaurarVendedor(id: Int, autor: UsuarioAutenticado): VendedorAgendaResponse {
        garantirPodeEditar(autor)
        val vendedor = carregarVendedor(id)
        vendedor.ativo = true
        return vendedorRepository.save(vendedor).paraResponse()
    }

    /**
     * A lista de partida numa base nova de demonstracao: os usuarios do Comercial e da
     * Diretoria, como a migracao V14 faz numa base que ja existia.
     */
    @Transactional
    fun semearVendedoresDosUsuarios() {
        if (vendedorRepository.count() > 0) return
        val prioridade = listOf(PerfilNome.VENDEDOR, PerfilNome.DIRETORIA)
        val porCodigo = linkedMapOf<String, String>()
        usuarioRepository.findAllByOrderByNomeAsc()
            .filter { it.ativo && it.perfil.nome in prioridade }
            .sortedBy { prioridade.indexOf(it.perfil.nome) }
            .forEach { u -> primeiraLetraOuNulo(u.nome)?.let { porCodigo.putIfAbsent(it, u.nome.trim().take(60)) } }
        vendedorRepository.saveAll(porCodigo.map { (codigo, nome) -> VendedorAgenda(codigo = codigo, nome = nome) })
    }

    private fun carregarVendedor(id: Int) = vendedorRepository.findById(id)
        .orElseThrow { NaoEncontradoException("Vendedor $id nao encontrado.") }

    private fun nomeDoVendedor(nome: String): String {
        val limpo = nome.trim().replace(Regex("\\s+"), " ")
        if (limpo.isEmpty()) throw RegraDeNegocioException("Informe o nome do vendedor.")
        if (limpo.length > 60) throw RegraDeNegocioException("O nome pode ter no maximo 60 caracteres.")
        return limpo
    }

    private fun codigoDoVendedor(codigo: String): String {
        val limpo = semAcento(codigo.trim()).uppercase()
        if (!Regex("^[A-Z0-9]{1,5}$").matches(limpo)) {
            throw RegraDeNegocioException("O codigo do vendedor tem de 1 a 5 letras ou numeros (ex.: K).")
        }
        return limpo
    }

    /** Uma sugestao livre de codigo: as duas primeiras letras, ou a inicial de cada nome. */
    private fun sugestao(nome: String): String {
        val letras = semAcento(nome).uppercase().filter { it.isLetter() }
        val iniciais = semAcento(nome).uppercase().split(" ").mapNotNull { p -> p.firstOrNull { it.isLetter() } }.joinToString("").take(5)
        return listOf(letras.take(2), iniciais, letras.take(3))
            .firstOrNull { it.isNotEmpty() && vendedorRepository.findByCodigo(it) == null } ?: "outro"
    }

    private fun primeiraLetra(nome: String) =
        primeiraLetraOuNulo(nome) ?: throw RegraDeNegocioException("Informe o codigo do vendedor.")

    private fun primeiraLetraOuNulo(nome: String) = semAcento(nome.trim()).firstOrNull { it.isLetter() }?.uppercase()

    private fun semAcento(texto: String) =
        java.text.Normalizer.normalize(texto, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

    private fun VendedorAgenda.paraResponse() = VendedorAgendaResponse(id!!, codigo, nome, ativo)

    private fun garantirPodeEditar(autor: UsuarioAutenticado) {
        if (!autor.tem(com.rastros.domain.SecaoDoSistema.AGENDA_LEGENDA)) {
            throw PermissaoNegadaException("Somente a Diretoria e o Administrador editam os status e os vendedores da agenda.")
        }
    }
}
