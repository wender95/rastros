package com.ostracker.api

import com.ostracker.domain.SetorNome
import com.ostracker.domain.StatusAgendamento
import com.ostracker.domain.StatusFluxo
import com.ostracker.domain.TipoAgendamento
import com.ostracker.domain.TipoColunaAgenda
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.LocalDate

data class AdesivadorResponse(
    val id: Int,
    val nome: String,
    val tipo: TipoColunaAgenda,
    val ordem: Int,
    val ativo: Boolean
)

/** Situacao de um fluxo da OS vinculada, para leitura direta na agenda. */
data class MaterialFluxoResponse(
    val fluxoId: Int,
    val identificador: String,
    val setorAtual: SetorNome,
    val status: StatusFluxo
)

/**
 * Resposta da pergunta "como esta o andamento do material deste carro?".
 * `pronto` indica que todos os fluxos ativos ja sairam da producao
 * (estao na Prateleira, no Patio, no Financeiro ou ja concluidos).
 */
data class MaterialResponse(
    val osId: Int,
    val numeroOsErp: String,
    val cliente: String?,
    val osCancelada: Boolean,
    val pronto: Boolean,
    val fluxos: List<MaterialFluxoResponse>
)

/** Uma faixa de horario da grade do dia. */
data class FaixaHorariaResponse(
    val indice: Int,
    val inicio: String,
    val fim: String,
    val rotulo: String,
    val horas: BigDecimal,
    val almoco: Boolean
)

/**
 * Um pedaco contiguo que o servico ocupa na grade. O almoco parte o servico em dois:
 * a faixa do almoco fica livre e o serviço continua depois dela.
 */
data class SegmentoAgendaResponse(
    val data: LocalDate,
    val faixaInicio: Int,
    val quantidade: Int
)

data class AgendamentoResponse(
    val id: Long,
    val data: LocalDate,
    val adesivadorId: Int,
    val adesivador: String,
    val tipo: TipoAgendamento,
    val slotInicio: Int,
    /** Faixas de trabalho cobertas, sem contar o almoco. */
    val faixasOcupadas: Int,
    /** Pedacos contiguos na grade; mais de um quando atravessa o almoco ou vira o dia. */
    val segmentos: List<SegmentoAgendaResponse>,
    val horasEstimadas: BigDecimal,
    val horarioInicio: String,
    val horarioFim: String,
    val descricao: String,
    val vendedorCodigo: String?,
    val status: StatusAgendamento,
    val observacao: String?,
    val material: MaterialResponse?
)

data class DiaAgendaResponse(
    val data: LocalDate,
    val diaSemana: String,
    val agendamentos: List<AgendamentoResponse>
)

/** Soma de uma medida por coluna da semana (horas de servico, horas bloqueadas). */
data class TotalPorColunaResponse(val adesivadorId: Int, val total: BigDecimal)

data class SemanaAgendaResponse(
    val inicio: LocalDate,
    val fim: LocalDate,
    val colunas: List<AdesivadorResponse>,
    val dias: List<DiaAgendaResponse>,
    /** Comecaram numa semana anterior e ainda ocupam o comeco desta. */
    val continuacoes: List<AgendamentoResponse> = emptyList(),
    val faixas: List<FaixaHorariaResponse>,
    val horasUteisDoDia: BigDecimal,
    /** Horas de trabalho dos dias mostrados: 44 numa semana cheia (sexta ate 17h), menos num corte de mes. */
    val horasUteisDaSemana: BigDecimal,
    /** Horas de serviço por adesivador na semana, para enxergar a carga. */
    val horasOcupadas: List<TotalPorColunaResponse>,
    /** Horas bloqueadas por falta, férias ou feriado — reduzem a capacidade da semana. */
    val horasIndisponiveis: List<TotalPorColunaResponse>,
    /** O que o botao Desfazer vai reverter, ou nulo quando nao ha nada na pilha. */
    val ultimaAcao: String?
)

data class NovoAgendamentoRequest(
    @field:NotNull(message = "Informe a data") val data: LocalDate? = null,
    @field:NotNull(message = "Selecione o adesivador") val adesivadorId: Int? = null,
    val tipo: TipoAgendamento = TipoAgendamento.SERVICO,
    @field:NotNull @field:Min(1) @field:Max(9) val slotInicio: Int? = null,
    /** Sem teto: um projeto longo atravessa o fim do dia e segue nos dias seguintes. */
    @field:NotNull @field:DecimalMin("0.5") val horasEstimadas: BigDecimal? = null,
    @field:NotBlank(message = "Descreva o carro/servico") @field:Size(max = 200) val descricao: String = "",
    @field:Size(max = 5) val vendedorCodigo: String? = null,
    val status: StatusAgendamento = StatusAgendamento.PROGRAMADO,
    val osId: Int? = null,
    val observacao: String? = null
)

/** Destino de um agendamento arrastado na grade. */
data class MoverAgendamentoRequest(
    @field:NotNull(message = "Informe a data de destino") val data: LocalDate? = null,
    @field:NotNull(message = "Informe o adesivador de destino") val adesivadorId: Int? = null,
    @field:NotNull @field:Min(1) @field:Max(9) val slotInicio: Int? = null
)

/** Novo inicio de um servico, puxando a borda de cima do card: o fim fica onde esta. */
data class InicioAgendamentoRequest(
    @field:NotNull(message = "Informe o dia") val data: LocalDate? = null,
    @field:NotNull @field:Min(1) @field:Max(9) val slotInicio: Int? = null
)

data class StatusAgendamentoRequest(
    @field:NotNull(message = "Informe o status") val status: StatusAgendamento? = null
)

/** Redimensionar o card arrastando a borda de baixo, como numa planilha. */
data class HorasAgendamentoRequest(
    @field:NotNull @field:DecimalMin("0.5") val horasEstimadas: BigDecimal? = null
)

/** OS disponiveis para vincular a um carro da agenda. */
data class OrdemAbertaResponse(
    val id: Int,
    val numeroOsErp: String,
    val cliente: String?,
    val fluxosAtivos: Int,
    val setores: List<SetorNome>
)

data class VinculoOsRequest(
    /** Numero da OS no ERP. Quando nulo ou vazio, o vinculo e desfeito. */
    val numeroOsErp: String? = null,
    /** Alternativa ao numero: id interno da OS. */
    val osId: Int? = null
)

data class ResultadoImportacaoResponse(
    val aba: String,
    val semanas: Int,
    /** Celulas preenchidas lidas na planilha. Maior que criados porque um servico
     *  repetido em linhas seguidas vira um unico agendamento. */
    val celulasLidas: Int,
    val criados: Int,
    val colunasNovas: List<String>,
    val avisos: List<String>,
    /** So no reparo: dias que nao existiam no sistema e foram gravados agora. */
    val diasCompletados: List<LocalDate> = emptyList()
)
