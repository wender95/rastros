package com.rastros.api

import com.rastros.domain.SetorNome
import com.rastros.domain.StatusAgendamento
import com.rastros.domain.TipoAgendamento
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Uma pessoa dentro do setor. Nos setores, a OS e de quem a recebeu (RN01: quem clica em
 * Receber e o responsavel pela etapa); no comercial, de quem abriu.
 */
data class ProdutividadePessoaResponse(
    val usuarioId: Int,
    val nome: String,
    /** OS processadas no setor (ou abertas, no comercial). */
    val quantidade: Int,
    /** Tempo medio das OS no setor com essa pessoa, da chegada a saida. Nulo no comercial. */
    val horasMediaNoSetor: BigDecimal?
)

/**
 * Produtividade de um setor no periodo, derivada da trilha de eventos (docs/07).
 *
 * Processada = saiu do setor no periodo (despachada, devolvida, enviada ao Financeiro ou,
 * no Financeiro, concluida). O tempo no setor vai da chegada a saida e se divide em espera
 * (chegou -> alguem recebeu) e trabalho (recebeu -> saiu).
 */
data class ProdutividadeSetorResponse(
    val setor: SetorNome,
    val processadas: Int,
    val horasMediaNoSetor: BigDecimal?,
    val horasMediaEspera: BigDecimal?,
    val horasMediaProcessamento: BigDecimal?,
    /** OS devolvidas ao setor anterior: retrabalho. */
    val retornos: Int,
    /** Por pessoa; vazio na Prateleira e no Patio, que sao lugares e nao tem quem receba. */
    val pessoas: List<ProdutividadePessoaResponse>
)

/** O comercial se mede pelas OS que abre. */
data class ProdutividadeComercialResponse(
    val abertas: Int,
    val pessoas: List<ProdutividadePessoaResponse>
)

/**
 * Produtividade individual de um adesivador, a partir da agenda.
 *
 * A medida NAO e hora: todos cumprem a mesma carga horaria, entao hora mede o quanto foi
 * agendado para a pessoa, nao o quanto ela produziu. O que compara e a quantidade de
 * servicos concluidos e o score - a ponderacao de tamanho de servico que o cronograma ja
 * usava. As horas ficam como capacidade e contexto.
 */
data class ProdutividadeAdesivadorResponse(
    val adesivadorId: Int,
    val adesivador: String,
    val servicos: Int,
    val concluidos: Int,
    /** Score dos servicos concluidos; nulo quando nenhum deles tem score lancado. */
    val scoreConcluido: BigDecimal?,
    val scoreAgendado: BigDecimal?,
    val horasAgendadas: BigDecimal,
    val horasIndisponiveis: BigDecimal,
    val naoCompareceu: Int
)

data class ProdutividadeResponse(
    val inicio: LocalDate,
    val fim: LocalDate,
    val comercial: ProdutividadeComercialResponse,
    val setores: List<ProdutividadeSetorResponse>,
    val adesivadores: List<ProdutividadeAdesivadorResponse>
)

// ------------------------------------------------- relatorio semanal por adesivador

/** Uma linha do relatorio: o servico como ele esta na agenda. */
data class ServicoDoRelatorioResponse(
    val agendamentoId: Long,
    val data: LocalDate,
    val diaSemana: String,
    val horarioInicio: String,
    val horarioFim: String,
    /** Dia em que o servico termina: diferente de data quando passa do fim do dia. */
    val dataFim: LocalDate,
    val diaSemanaFim: String,
    /** Em quantos dias uteis o servico se estende (1 = comeca e termina no mesmo dia). */
    val dias: Int,
    val descricao: String,
    val status: StatusAgendamento,
    val tipo: TipoAgendamento,
    val vendedor: String?,
    /** Status criado na agenda que o card mostra. */
    val etiquetaId: Long? = null,
    val numeroOsErp: String?,
    val osId: Int?,
    /** Situacao do material da OS: pronto, em producao ou nulo sem OS vinculada. */
    val materialPronto: Boolean?,
    val score: BigDecimal?,
    val horasEstimadas: BigDecimal,
    /** Quando o adesivador iniciou e concluiu o projeto - o horario real, nao o da agenda. */
    val iniciadoEm: java.time.Instant? = null,
    val concluidoEm: java.time.Instant? = null,
    /** Quem registrou o inicio e a conclusao (o mouse sobre o horario mostra). */
    val iniciadoPor: String? = null,
    val concluidoPor: String? = null,
    /** As pausas do servico (o icone de pausa no relatorio mostra os horarios). */
    val pausas: List<com.rastros.service.PausaResponse> = emptyList()
)

/** Um servico achado pela busca do relatorio, com o adesivador de quem ele e. */
data class ServicoEncontradoResponse(
    val adesivador: String,
    val servico: ServicoDoRelatorioResponse
)

/** Resultado da busca: `limitado` avisa que ha mais do que os mostrados. */
data class BuscaRelatorioResponse(
    val termo: String,
    val servicos: List<ServicoEncontradoResponse>,
    val limitado: Boolean
)

data class RelatorioAdesivadorResponse(
    val adesivadorId: Int,
    val adesivador: String,
    val servicos: List<ServicoDoRelatorioResponse>,
    val totalServicos: Int,
    val concluidos: Int,
    val naoCompareceu: Int,
    /** Soma dos pontos (score) dos servicos concluidos. */
    val score: BigDecimal?,
    /** Soma dos pontos de todos os servicos da semana que ja tem score, concluidos ou nao. */
    val scoreLancado: BigDecimal?,
    val horasIndisponiveis: BigDecimal
)

data class RelatorioSemanalResponse(
    val inicio: LocalDate,
    val fim: LocalDate,
    val adesivadores: List<RelatorioAdesivadorResponse>
)
