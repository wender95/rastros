package com.rastros.service

import com.rastros.domain.Adesivador
import com.rastros.domain.Agendamento
import com.rastros.domain.TipoAgendamento
import com.rastros.repository.AdesivadorRepository
import com.rastros.repository.AgendamentoRepository
import com.rastros.repository.OrdemServicoRepository
import com.rastros.security.UsuarioAutenticado
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate

/**
 * O Ctrl+Z da agenda: antes de cada alteracao, um retrato das colunas e dos dias que ela
 * pode mexer; desfazer devolve essa janela ao que era.
 */
@Component
class DesfazerAgenda(
    private val agendamentoRepository: AgendamentoRepository,
    private val adesivadorRepository: AdesivadorRepository,
    private val ordemRepository: OrdemServicoRepository,
    private val historico: HistoricoAgendaService,
    private val configuracao: ConfiguracaoAgendaService
) {

    /**
     * Guarda como a agenda estava antes de mexer. A janela cobre os dias que o
     * reempilhamento pode remanejar, senao desfazer deixaria os vizinhos deslocados.
     */
    fun registrar(
        autor: UsuarioAutenticado,
        descricao: String,
        adesivadores: List<Adesivador>,
        apartirDe: LocalDate,
        diasUteis: Int = 20
    ) {
        val dias = DiasUteisDaAgenda.aPartirDe(apartirDe, diasUteis)
        val ids = adesivadores.mapNotNull { it.id }.toSet()
        val linhas = ids.flatMap { id ->
            agendamentoRepository.doPeriodoDoAdesivador(dias.first(), dias.last(), id)
        }.map {
            LinhaSnapshot(
                id = it.id!!,
                data = it.data,
                adesivadorId = it.adesivador.id!!,
                slotInicio = it.slotInicio,
                horas = it.horas,
                tipo = it.tipo ?: TipoAgendamento.SERVICO,
                descricao = it.descricao,
                vendedorCodigo = it.vendedorCodigo,
                status = it.status,
                score = it.score,
                osId = it.ordemServico?.id,
                observacao = it.observacao,
                etiquetaId = it.etiquetaId,
                atribuidosIds = it.atribuidos.mapNotNull { a -> a.id }
            )
        }
        historico.empilhar(autor.id, SnapshotAgenda(descricao, ids, dias.first(), dias.last(), linhas))
    }

    /** Volta a agenda ao estado anterior a ultima alteracao deste usuario; devolve o que foi desfeito. */
    fun desfazer(autor: UsuarioAutenticado): String {
        val snapshot = historico.desempilhar(autor.id)
            ?: throw RegraDeNegocioException("Nao ha nada para desfazer: o desfazer guarda as suas ultimas 25 alteracoes das ultimas 24 horas.")

        val atuais = snapshot.adesivadorIds.flatMap {
            agendamentoRepository.doPeriodoDoAdesivador(snapshot.inicio, snapshot.fim, it)
        }
        val guardados = snapshot.linhas.associateBy { it.id }

        // O que foi criado depois do retrato sai.
        atuais.filter { it.id !in guardados.keys }.forEach { agendamentoRepository.delete(it) }

        val existentes = atuais.associateBy { it.id }
        snapshot.linhas.forEach { linha ->
            val alvo = existentes[linha.id] ?: Agendamento(
                data = linha.data,
                adesivador = carregarAdesivador(linha.adesivadorId),
                slotInicio = linha.slotInicio,
                descricao = linha.descricao
            )
            alvo.data = linha.data
            alvo.adesivador = carregarAdesivador(linha.adesivadorId)
            alvo.slotInicio = linha.slotInicio
            alvo.horasEstimadas = linha.horas
            alvo.tipo = linha.tipo
            alvo.descricao = linha.descricao
            alvo.vendedorCodigo = linha.vendedorCodigo
            alvo.status = linha.status
            // O status criado na agenda pode ter sido removido depois do retrato.
            alvo.etiquetaId = linha.etiquetaId?.takeIf { configuracao.etiquetaExiste(it) }
            // Um adesivador pode ter sido apagado depois do retrato: fica quem ainda existe.
            alvo.atribuidos = adesivadorRepository.findAllById(linha.atribuidosIds).toMutableSet()
            alvo.score = linha.score
            alvo.ordemServico = linha.osId?.let { id ->
                ordemRepository.findById(id).orElseThrow { NaoEncontradoException("Ordem de servico $id nao encontrada.") }
            }
            alvo.observacao = linha.observacao
            alvo.atualizadoEm = Instant.now()
            agendamentoRepository.save(alvo)
        }
        return snapshot.descricao
    }

    private fun carregarAdesivador(id: Int) = adesivadorRepository.findById(id)
        .orElseThrow { NaoEncontradoException("Adesivador $id nao encontrado.") }
}
