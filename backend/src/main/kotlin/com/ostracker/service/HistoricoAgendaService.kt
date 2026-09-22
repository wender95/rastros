package com.ostracker.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.ostracker.domain.RetratoAgenda
import com.ostracker.domain.StatusAgendamento
import com.ostracker.domain.TipoAgendamento
import com.ostracker.repository.RetratoAgendaRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** Como uma faixa estava antes da alteracao. */
data class LinhaSnapshot(
    val id: Long,
    val data: LocalDate,
    val adesivadorId: Int,
    val slotInicio: Int,
    val horas: BigDecimal,
    val tipo: TipoAgendamento,
    val descricao: String,
    val vendedorCodigo: String?,
    val status: StatusAgendamento,
    val score: BigDecimal?,
    val osId: Int?,
    val observacao: String?
)

/**
 * Retrato da agenda dos adesivadores afetados, no intervalo que a alteracao pode ter
 * remanejado. Guarda a janela inteira porque um empurrao mexe em varios servicos.
 */
data class SnapshotAgenda(
    val descricao: String,
    val adesivadorIds: Set<Int>,
    val inicio: LocalDate,
    val fim: LocalDate,
    val linhas: List<LinhaSnapshot>
)

/**
 * Pilha de desfazer da agenda, por usuario, gravada no banco.
 *
 * E um "ctrl+z" do dia de trabalho, nao um historico de auditoria - esse papel e das
 * tabelas de eventos da OS, que sao imutaveis. Por isso cada pessoa guarda so os ultimos
 * [LIMITE] passos e um retrato vale por [VALIDADE]: desfazer algo de dias atras
 * devolveria a janela inteira ao estado antigo, apagando o que outros fizeram depois.
 */
@Service
class HistoricoAgendaService(
    private val repositorio: RetratoAgendaRepository,
    private val json: ObjectMapper
) {

    fun empilhar(usuarioId: Int, snapshot: SnapshotAgenda) {
        repositorio.save(
            RetratoAgenda(
                usuarioId = usuarioId,
                descricao = snapshot.descricao.take(250),
                adesivadorIds = snapshot.adesivadorIds.joinToString(","),
                inicio = snapshot.inicio,
                fim = snapshot.fim,
                linhas = json.writeValueAsString(snapshot.linhas)
            )
        )
        val excedentes = repositorio.idsDoUsuario(usuarioId).drop(LIMITE)
        if (excedentes.isNotEmpty()) repositorio.deleteAllByIdInBatch(excedentes)
    }

    fun desempilhar(usuarioId: Int): SnapshotAgenda? {
        val retrato = ultimoValido(usuarioId) ?: return null
        repositorio.delete(retrato)
        return SnapshotAgenda(
            descricao = retrato.descricao,
            adesivadorIds = retrato.adesivadorIds.split(",").filter { it.isNotBlank() }.map { it.toInt() }.toSet(),
            inicio = retrato.inicio,
            fim = retrato.fim,
            linhas = json.readValue(retrato.linhas)
        )
    }

    /** Descricao da ultima acao, para o botao dizer o que sera desfeito. */
    fun ultima(usuarioId: Int): String? = ultimoValido(usuarioId)?.descricao

    private fun ultimoValido(usuarioId: Int): RetratoAgenda? =
        repositorio.findFirstByUsuarioIdOrderByIdDesc(usuarioId)
            ?.takeIf { it.criadoEm.isAfter(Instant.now().minus(VALIDADE)) }

    companion object {
        const val LIMITE = 25
        val VALIDADE: Duration = Duration.ofHours(24)
    }
}
