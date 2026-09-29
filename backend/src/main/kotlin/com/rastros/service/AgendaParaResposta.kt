package com.rastros.service

import com.rastros.api.*
import com.rastros.domain.*
import com.rastros.repository.AgendamentoRepository
import com.rastros.repository.FluxoOsRepository
import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.LocalDate

/** Monta o que a tela da agenda recebe: o card, com o material da OS e as copias dele. */
@Component
class AgendaParaResposta(
    private val agendamentoRepository: AgendamentoRepository,
    private val fluxoRepository: FluxoOsRepository
) {

    fun de(adesivador: Adesivador) = with(adesivador) { AdesivadorResponse(id!!, nome, tipo, ordem, ativo) }

    /**
     * Varios agendamentos de uma vez: os fluxos de material de todas as OS vem numa
     * consulta so, em vez de uma por carro.
     */
    fun de(agendamentos: List<Agendamento>): List<AgendamentoResponse> {
        val fluxosPorOs = fluxosDasOs(agendamentos.mapNotNull { it.ordemServico?.id }.toSet())
        return agendamentos.map { de(it, fluxosPorOs) }
    }

    fun de(
        agendamento: Agendamento,
        fluxosPorOs: Map<Int, List<FluxoOs>> = fluxosDasOs(setOfNotNull(agendamento.ordemServico?.id))
    ): AgendamentoResponse = with(agendamento) {
        val posicoes = posicoesOcupadas
        val ultima = FaixasDoDia.faixaDaPosicao(posicoes.last())
        AgendamentoResponse(
            id = id!!,
            data = data,
            adesivadorId = adesivador.id!!,
            adesivador = adesivador.nome,
            tipo = tipo ?: TipoAgendamento.SERVICO,
            slotInicio = slotInicio,
            faixasOcupadas = posicoes.size,
            segmentos = montarSegmentos(data, posicoes),
            horasEstimadas = horas,
            horarioInicio = FaixasDoDia.de(slotInicio).inicio,
            horarioFim = FaixasDoDia.de(ultima).fim,
            descricao = descricao,
            vendedorCodigo = vendedorCodigo,
            status = status,
            etiquetaId = etiquetaId,
            atribuidos = atribuidos.sortedBy { it.ordem }.map { AtribuidoResponse(it.id!!, it.nome) },
            observacao = observacao,
            material = ordemServico?.let { montarMaterial(it, fluxosPorOs[it.id].orEmpty()) },
            grupoId = grupoId,
            partes = grupoId?.let { agendamentoRepository.findByGrupoIdOrderByDataAscSlotInicioAsc(it).size } ?: 1
        )
    }

    private fun fluxosDasOs(osIds: Set<Int>): Map<Int, List<FluxoOs>> =
        if (osIds.isEmpty()) emptyMap()
        else fluxoRepository.findByOrdemServicoIdInOrderByIdAsc(osIds).groupBy { it.ordemServico.id!! }

    /**
     * O servico e **um bloco continuo** da primeira a ultima faixa que ele ocupa.
     *
     * O almoco fica dentro desse intervalo: nao conta hora e nao recebe inicio de servico,
     * mas a grade o desenha como uma pausa no meio do card, em vez de partir o servico em
     * dois e repetir o nome. Um servico de varios dias tambem sai num bloco so.
     */
    private fun montarSegmentos(inicio: LocalDate, posicoes: List<Int>): List<SegmentoAgendaResponse> {
        val primeira = posicoes.first()
        val ultima = posicoes.last()
        return listOf(
            SegmentoAgendaResponse(
                data = FaixasDoDia.diaUtilAFrente(inicio, primeira / FaixasDoDia.QUANTIDADE),
                faixaInicio = FaixasDoDia.faixaDaPosicao(primeira),
                quantidade = ultima - primeira + 1
            )
        )
    }

    /** Situacao dos fluxos da OS vinculada: e a resposta de "o material ja esta pronto?". */
    private fun montarMaterial(os: OrdemServico, fluxos: List<FluxoOs>): MaterialResponse {
        val ativos = fluxos.filter { it.statusAtual != StatusFluxo.CANCELADA }
        val pronto = ativos.isNotEmpty() && ativos.all { it.encerrado || it.setorAtual.nome.saiuDaProducao }

        return MaterialResponse(
            osId = os.id!!,
            numeroOsErp = os.numeroOsErp,
            cliente = os.cliente,
            servico = os.servico,
            osCancelada = os.cancelada,
            pronto = pronto,
            fluxos = fluxos.map {
                MaterialFluxoResponse(it.id!!, it.identificadorFluxo, it.setorAtual.nome, it.statusAtual)
            }
        )
    }

    companion object {
        fun nomeDoDia(dia: DayOfWeek) = when (dia) {
            DayOfWeek.MONDAY -> "SEG"
            DayOfWeek.TUESDAY -> "TER"
            DayOfWeek.WEDNESDAY -> "QUA"
            DayOfWeek.THURSDAY -> "QUI"
            DayOfWeek.FRIDAY -> "SEX"
            DayOfWeek.SATURDAY -> "SAB"
            DayOfWeek.SUNDAY -> "DOM"
        }
    }
}
