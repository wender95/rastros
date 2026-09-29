package com.rastros.api

import com.rastros.domain.SetorNome
import com.rastros.domain.StatusAgendamento
import com.rastros.domain.StatusFluxo
import com.rastros.domain.TipoAgendamento
import com.rastros.domain.TipoColunaAgenda
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
    val servico: String?,
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
    /** Status criado na agenda que o card mostra no lugar do status do sistema. */
    val etiquetaId: Long? = null,
    /** Coluna Noturno: os adesivadores atribuidos ao servico. */
    val atribuidos: List<AtribuidoResponse> = emptyList(),
    val observacao: String?,
    val material: MaterialResponse?,
    /** Partes do mesmo servico: a chave do conjunto e quantas partes ele tem. */
    val grupoId: Long? = null,
    val partes: Int = 1
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
    /** Um status criado na agenda (o card fica como Programado, com o nome e a cor dele). */
    val etiquetaId: Long? = null,
    val osId: Int? = null,
    val observacao: String? = null
)

/** Destino de um agendamento arrastado na grade. */
data class MoverAgendamentoRequest(
    @field:NotNull(message = "Informe a data de destino") val data: LocalDate? = null,
    @field:NotNull(message = "Informe o adesivador de destino") val adesivadorId: Int? = null,
    @field:NotNull @field:Min(1) @field:Max(9) val slotInicio: Int? = null,
    /**
     * Tamanho que o servico deve ter **no destino**, em horas.
     *
     * Sem isso o que se conserva e a quantidade de faixas, e as faixas do dia nao valem
     * todas a mesma coisa: a agenda de espacos de trabalho via um servico de um espaco
     * virar dois so por mudar de lugar. Quem sabe o tamanho certo la e a tela, entao ela
     * manda junto - e a conta e feita ja no lugar novo, numa operacao so.
     */
    @field:DecimalMin("0.5") val horasEstimadas: BigDecimal? = null,
    /** Idem para quem estava no destino e vai para o lugar do arrastado (a troca). */
    @field:DecimalMin("0.5") val horasDoOcupante: BigDecimal? = null
)

/** Onde entra mais uma parte do mesmo servico (o "colar" da agenda simplificada). */
data class NovaParteRequest(
    @field:NotNull(message = "Informe a data") val data: LocalDate? = null,
    @field:NotNull(message = "Selecione o adesivador") val adesivadorId: Int? = null,
    @field:NotNull @field:Min(1) @field:Max(9) val slotInicio: Int? = null,
    @field:NotNull @field:DecimalMin("0.5") val horasEstimadas: BigDecimal? = null
)

/** Varios cards de uma vez (selecao com Shift na agenda). */
data class LoteAgendaRequest(
    @field:NotNull(message = "Escolha os cards") val ids: List<Long>? = null,
    /** So para mudar o estado. */
    val status: StatusAgendamento? = null,
    /** Ou um status criado na agenda. */
    val etiquetaId: Long? = null
)

/** Para onde vai cada card de um grupo arrastado junto. */
data class DestinoNoLote(
    @field:NotNull val id: Long? = null,
    @field:NotNull val data: LocalDate? = null,
    @field:NotNull val adesivadorId: Int? = null,
    @field:NotNull @field:Min(1) @field:Max(9) val slotInicio: Int? = null,
    /** O tamanho no destino, como no mover de um card so. */
    @field:NotNull @field:DecimalMin("0.5") val horasEstimadas: BigDecimal? = null
)

/** Varios cards arrastados juntos. */
data class MoverLoteRequest(
    @field:NotNull(message = "Escolha os cards") @field:jakarta.validation.Valid val itens: List<DestinoNoLote>? = null
)

/** A alca do canto: uma copia do card (parte do mesmo servico) em cada destino. */
data class ReplicarRequest(
    @field:NotNull(message = "Escolha os espacos") @field:jakarta.validation.Valid val destinos: List<NovaParteRequest>? = null
)

/** Quantos cards a acao atingiu. */
data class LoteAgendaResponse(val cards: Int)

data class AtribuidoResponse(val id: Int, val nome: String)

/** Coluna Noturno: quem vai fazer o servico. */
data class AtribuidosRequest(val adesivadorIds: List<Int> = emptyList())

data class StatusAgendamentoRequest(
    val status: StatusAgendamento? = null,
    /** Um status criado na agenda, no lugar de [status]. */
    val etiquetaId: Long? = null
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
