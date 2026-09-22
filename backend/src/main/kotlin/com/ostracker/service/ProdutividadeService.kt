package com.ostracker.service

import com.ostracker.api.ProdutividadeAdesivadorResponse
import com.ostracker.api.RelatorioAdesivadorResponse
import com.ostracker.api.RelatorioSemanalResponse
import com.ostracker.api.ServicoDoRelatorioResponse
import com.ostracker.api.ProdutividadeResponse
import com.ostracker.api.ProdutividadeSetorResponse
import com.ostracker.api.ProdutividadeComercialResponse
import com.ostracker.api.ProdutividadePessoaResponse
import com.ostracker.domain.HorarioComercial
import com.ostracker.domain.Usuario
import com.ostracker.domain.SetorNome
import com.ostracker.domain.StatusAgendamento
import com.ostracker.domain.TipoEvento
import com.ostracker.repository.AdesivadorRepository
import com.ostracker.repository.AgendamentoRepository
import com.ostracker.repository.EventoMovimentacaoRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Deriva os indicadores de produtividade da trilha de eventos e da agenda.
 *
 * Nada aqui e armazenado: os eventos de movimentacao continuam sendo a fonte da verdade
 * (RNF01) e os numeros sao calculados na leitura. O banco segue aberto para BI - o que
 * muda e que as duas perguntas do dia a dia nao precisam mais sair do sistema.
 */
@Service
class ProdutividadeService(
    private val eventoRepository: EventoMovimentacaoRepository,
    private val agendamentoRepository: AgendamentoRepository,
    private val adesivadorRepository: AdesivadorRepository,
    private val fluxoRepository: com.ostracker.repository.FluxoOsRepository,
    private val ordemRepository: com.ostracker.repository.OrdemServicoRepository
) {

    @Transactional(readOnly = true)
    fun calcular(inicio: LocalDate, fim: LocalDate): ProdutividadeResponse {
        val zona = ZoneId.systemDefault()
        val de = inicio.atStartOfDay(zona).toInstant()
        val ate = fim.plusDays(1).atStartOfDay(zona).toInstant()

        return ProdutividadeResponse(
            inicio = inicio,
            fim = fim,
            comercial = comercial(de, ate),
            setores = porSetor(de, ate),
            adesivadores = porAdesivador(inicio, fim)
        )
    }

    // ---------------------------------------------------------------- comercial

    /** OS abertas no periodo, por quem abriu. */
    private fun comercial(de: Instant, ate: Instant): ProdutividadeComercialResponse {
        val abertas = ordemRepository.findByCriadoEmGreaterThanEqualAndCriadoEmLessThan(de, ate)
        return ProdutividadeComercialResponse(
            abertas = abertas.size,
            pessoas = abertas.groupBy { it.criadoPor }
                .map { (usuario, lista) ->
                    ProdutividadePessoaResponse(usuario.id!!, usuario.nome, lista.size, horasMediaNoSetor = null)
                }
                .sortedByDescending { it.quantidade }
        )
    }

    // ------------------------------------------------------------------ setores

    private class Acumulador {
        var processadas = 0
        var retornos = 0
        val noSetor = mutableListOf<Duration>()
        val espera = mutableListOf<Duration>()
        val processamento = mutableListOf<Duration>()
        val porPessoa = mutableMapOf<Usuario, MutableList<Duration?>>()
    }

    private class Chegada(val setor: SetorNome, val quando: Instant)
    private class Recebimento(val setor: SetorNome, val quando: Instant, val quem: Usuario)

    /**
     * Percorre os eventos de cada fluxo em ordem. Cada vez que uma OS sai de um setor
     * (despacho, devolucao, envio ao Financeiro ou conclusao), fecha a passagem dela por
     * ali: conta uma processada para o setor e para quem a recebeu, e mede o tempo desde a
     * chegada - dividido em espera (ate alguem receber) e trabalho (dali ate sair).
     *
     * So entram as saidas que caem no periodo pedido, senao uma OS antiga ainda parada
     * contaminaria a media.
     */
    private fun porSetor(de: Instant, ate: Instant): List<ProdutividadeSetorResponse> {
        val fluxos = eventoRepository.fluxosComEventoNoPeriodo(de, ate)
        if (fluxos.isEmpty()) return emptyList()

        val porFluxo = eventoRepository.findByFluxoIdInOrderByFluxoIdAscDataHoraAsc(fluxos)
            .groupBy { it.fluxo.id!! }
        val acumuladores = mutableMapOf<SetorNome, Acumulador>()
        fun de(setor: SetorNome) = acumuladores.getOrPut(setor) { Acumulador() }

        porFluxo.values.forEach { eventos ->
            var chegada: Chegada? = null
            var recebimento: Recebimento? = null

            fun saiu(setor: SetorNome, quando: Instant, retorno: Boolean) {
                val dentro = !quando.isBefore(de) && quando.isBefore(ate)
                if (!dentro) return
                val a = de(setor)
                a.processadas++
                if (retorno) a.retornos++
                val total = chegada?.takeIf { it.setor == setor }?.let { HorarioComercial.entre(it.quando, quando) }
                total?.let { a.noSetor += it }
                recebimento?.takeIf { it.setor == setor }?.let { r ->
                    chegada?.takeIf { it.setor == setor }?.let { a.espera += HorarioComercial.entre(it.quando, r.quando) }
                    a.processamento += HorarioComercial.entre(r.quando, quando)
                    a.porPessoa.getOrPut(r.quem) { mutableListOf() } += total
                }
            }

            eventos.forEach { evento ->
                when (evento.tipoEvento) {
                    TipoEvento.CRIACAO -> chegada = Chegada(evento.setorDestino.nome, evento.dataHora)

                    TipoEvento.RECEBIMENTO ->
                        recebimento = Recebimento(evento.setorDestino.nome, evento.dataHora, evento.usuario)

                    TipoEvento.DESPACHO, TipoEvento.RETORNO, TipoEvento.ENTREGA -> {
                        evento.setorOrigem?.nome?.let { saiu(it, evento.dataHora, evento.tipoEvento == TipoEvento.RETORNO) }
                        chegada = Chegada(evento.setorDestino.nome, evento.dataHora)
                        recebimento = null
                    }

                    // O Financeiro conclui: e a saida dele, e o fim do fluxo.
                    TipoEvento.CONCLUSAO -> {
                        saiu(evento.setorOrigem?.nome ?: evento.setorDestino.nome, evento.dataHora, retorno = false)
                        chegada = null
                        recebimento = null
                    }

                    TipoEvento.CANCELAMENTO -> {
                        chegada = null
                        recebimento = null
                    }
                }
            }
        }

        return acumuladores
            .map { (setor, a) ->
                ProdutividadeSetorResponse(
                    setor = setor,
                    processadas = a.processadas,
                    horasMediaNoSetor = mediaEmHoras(a.noSetor),
                    horasMediaEspera = mediaEmHoras(a.espera),
                    horasMediaProcessamento = mediaEmHoras(a.processamento),
                    retornos = a.retornos,
                    pessoas = a.porPessoa
                        .map { (usuario, tempos) ->
                            ProdutividadePessoaResponse(
                                usuarioId = usuario.id!!,
                                nome = usuario.nome,
                                quantidade = tempos.size,
                                horasMediaNoSetor = mediaEmHoras(tempos.filterNotNull())
                            )
                        }
                        .sortedByDescending { it.quantidade }
                )
            }
            .sortedBy { it.setor.ordinal } // a ordem da producao: Criacao, Impressao, ... Financeiro
    }

    private fun mediaEmHoras(duracoes: List<Duration>): BigDecimal? {
        if (duracoes.isEmpty()) return null
        val minutos = duracoes.sumOf { it.toMinutes() } / duracoes.size
        return BigDecimal(minutos).divide(BigDecimal(60), 1, RoundingMode.HALF_UP)
    }

    // -------------------------------------------------------------- adesivadores

    private fun porAdesivador(inicio: LocalDate, fim: LocalDate): List<ProdutividadeAdesivadorResponse> {
        val agendamentos = agendamentoRepository.doPeriodo(inicio, fim)
        // Metrica individual e de pessoa: Encaixe e Noturno sao colunas da grade, nao
        // adesivadores, e atribuir o trabalho delas a alguem seria invencao.
        // Quem foi removido da agenda continua aparecendo nos periodos em que trabalhou.
        val comCarro = agendamentos.mapNotNull { it.adesivador.id }.toSet()
        val colunas = adesivadorRepository.findAllByOrderByOrdemAsc()
            .filter { it.tipo == com.ostracker.domain.TipoColunaAgenda.ADESIVADOR && (it.ativo || it.id in comCarro) }

        return colunas.map { coluna ->
            val meus = agendamentos.filter { it.adesivador.id == coluna.id }
            val servicos = meus.filter { it.ehServico }
            val concluidos = servicos.filter { it.status == StatusAgendamento.CONCLUIDO }

            ProdutividadeAdesivadorResponse(
                adesivadorId = coluna.id!!,
                adesivador = coluna.nome,
                servicos = servicos.size,
                concluidos = concluidos.size,
                scoreConcluido = somaScore(concluidos.mapNotNull { it.score }),
                scoreAgendado = somaScore(servicos.mapNotNull { it.score }),
                horasAgendadas = somaHoras(servicos.map { it.horas }),
                horasIndisponiveis = somaHoras(meus.filterNot { it.ehServico }.map { it.horas }),
                naoCompareceu = servicos.count { it.status == StatusAgendamento.NAO_VEIO }
            )
        }
    }

    /** Nulo quando nenhum servico tem score lancado - zero diria "produziu nada". */
    private fun somaScore(valores: List<BigDecimal>) =
        if (valores.isEmpty()) null
        else valores.fold(BigDecimal.ZERO) { soma, v -> soma + v }.setScale(1, RoundingMode.HALF_UP)

    // -------------------------------------------------------- relatorio semanal

    /**
     * Relatorio da semana por adesivador: os servicos que passaram pela agenda de cada um,
     * com a OS e o andamento do material. E a leitura que a hora nao da - o que a pessoa
     * entregou na semana, servico por servico.
     */
    @Transactional(readOnly = true)
    /** A semana da data, cortada no mes (como a agenda). */
    fun relatorioSemanal(dataReferencia: LocalDate): RelatorioSemanalResponse {
        val (inicio, fim) = com.ostracker.domain.FaixasDoDia.semanaNoMes(dataReferencia)
        return relatorio(inicio, fim)
    }

    /** O relatorio de um periodo qualquer: a semana cortada no mes, ou o mes inteiro. */
    fun relatorio(segunda: LocalDate, sexta: LocalDate): RelatorioSemanalResponse {

        val agendamentos = agendamentoRepository.doPeriodo(segunda, sexta)
        // Quem foi removido da agenda continua aparecendo nos periodos em que trabalhou.
        val comCarro = agendamentos.mapNotNull { it.adesivador.id }.toSet()
        val colunas = adesivadorRepository.findAllByOrderByOrdemAsc()
            .filter { it.tipo == com.ostracker.domain.TipoColunaAgenda.ADESIVADOR && (it.ativo || it.id in comCarro) }
        // Os fluxos de todas as OS da semana numa consulta so, e nao uma por servico.
        val osIds = agendamentos.mapNotNull { it.ordemServico?.id }.toSet()
        val fluxosPorOs = if (osIds.isEmpty()) emptyMap()
        else fluxoRepository.findByOrdemServicoIdInOrderByIdAsc(osIds).groupBy { it.ordemServico.id!! }

        return RelatorioSemanalResponse(
            inicio = segunda,
            fim = sexta,
            adesivadores = colunas.map { coluna ->
                val meus = agendamentos
                    .filter { it.adesivador.id == coluna.id }
                    .sortedWith(compareBy({ it.data }, { it.slotInicio }))
                val servicos = meus.filter { it.ehServico }
                val concluidos = servicos.filter { it.status == StatusAgendamento.CONCLUIDO }

                RelatorioAdesivadorResponse(
                    adesivadorId = coluna.id!!,
                    adesivador = coluna.nome,
                    servicos = meus.map { it.paraLinhaDoRelatorio(fluxosPorOs) },
                    totalServicos = servicos.size,
                    concluidos = concluidos.size,
                    naoCompareceu = servicos.count { it.status == StatusAgendamento.NAO_VEIO },
                    score = somaScore(concluidos.mapNotNull { it.score }),
                    scoreLancado = somaScore(servicos.mapNotNull { it.score }),
                    horasIndisponiveis = somaHoras(meus.filterNot { it.ehServico }.map { it.horas })
                )
            }
        )
    }

    private fun com.ostracker.domain.Agendamento.paraLinhaDoRelatorio(
        fluxosPorOs: Map<Int, List<com.ostracker.domain.FluxoOs>>
    ): ServicoDoRelatorioResponse {
        val posicoes = posicoesOcupadas
        val ultima = com.ostracker.domain.FaixasDoDia.faixaDaPosicao(posicoes.last())
        val os = ordemServico
        val fluxos = os?.let { fluxosPorOs[it.id].orEmpty() }
        val ativos = fluxos?.filter { it.statusAtual != com.ostracker.domain.StatusFluxo.CANCELADA }

        return ServicoDoRelatorioResponse(
            agendamentoId = id!!,
            data = data,
            diaSemana = siglaDoDia(data),
            horarioInicio = com.ostracker.domain.FaixasDoDia.de(slotInicio).inicio,
            horarioFim = com.ostracker.domain.FaixasDoDia.de(ultima).fim,
            dataFim = ultimoDia,
            diaSemanaFim = siglaDoDia(ultimoDia),
            dias = posicoes.last() / com.ostracker.domain.FaixasDoDia.QUANTIDADE + 1,
            descricao = descricao,
            status = status,
            tipo = tipo ?: com.ostracker.domain.TipoAgendamento.SERVICO,
            vendedor = vendedorCodigo,
            numeroOsErp = os?.numeroOsErp,
            osId = os?.id,
            materialPronto = ativos?.let { lista ->
                lista.isNotEmpty() && lista.all { it.encerrado || it.setorAtual.nome.saiuDaProducao }
            },
            score = score,
            horasEstimadas = horas
        )
    }

    private fun siglaDoDia(dia: LocalDate) = when (dia.dayOfWeek) {
        java.time.DayOfWeek.MONDAY -> "SEG"
        java.time.DayOfWeek.TUESDAY -> "TER"
        java.time.DayOfWeek.WEDNESDAY -> "QUA"
        java.time.DayOfWeek.THURSDAY -> "QUI"
        else -> "SEX"
    }

    private fun somaHoras(valores: List<BigDecimal>) =
        valores.fold(BigDecimal.ZERO) { soma, v -> soma + v }.setScale(1, RoundingMode.HALF_UP)
}
