package com.rastros.service

import com.rastros.api.ProdutividadeAdesivadorResponse
import com.rastros.api.RelatorioAdesivadorResponse
import com.rastros.api.RelatorioSemanalResponse
import com.rastros.api.BuscaRelatorioResponse
import com.rastros.api.ServicoDoRelatorioResponse
import com.rastros.api.ServicoEncontradoResponse
import com.rastros.api.ProdutividadeResponse
import com.rastros.api.ProdutividadeSetorResponse
import com.rastros.api.ProdutividadeComercialResponse
import com.rastros.api.ProdutividadePessoaResponse
import com.rastros.domain.HorarioComercial
import com.rastros.domain.Usuario
import com.rastros.domain.SetorNome
import com.rastros.domain.StatusAgendamento
import com.rastros.domain.TipoEvento
import com.rastros.repository.AdesivadorRepository
import com.rastros.repository.AgendamentoRepository
import com.rastros.repository.EventoMovimentacaoRepository
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
/** Quantos servicos a busca do relatorio mostra de uma vez (os mais recentes). */
private const val LIMITE_DA_BUSCA = 100

@Service
class ProdutividadeService(
    private val eventoRepository: EventoMovimentacaoRepository,
    private val agendamentoRepository: AgendamentoRepository,
    private val adesivadorRepository: AdesivadorRepository,
    private val fluxoRepository: com.rastros.repository.FluxoOsRepository,
    private val ordemRepository: com.rastros.repository.OrdemServicoRepository,
    private val pausas: PausasDosProjetos
) {

    /** Poe nas linhas as pausas de cada servico (a pausa e do servico, por isso vai pela chave). */
    private fun comPausas(
        linhas: List<ServicoDoRelatorioResponse>,
        agendamentos: List<com.rastros.domain.Agendamento>,
        porServico: Map<Long, List<PausaResponse>> = pausas.dosServicos(agendamentos.map { pausas.chave(it) })
    ): List<ServicoDoRelatorioResponse> {
        if (porServico.isEmpty()) return linhas
        val chaveDe = agendamentos.associate { it.id!! to pausas.chave(it) }
        return linhas.map { l -> porServico[chaveDe[l.agendamentoId]]?.let { l.copy(pausas = it) } ?: l }
    }

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
            .filter { it.tipo == com.rastros.domain.TipoColunaAgenda.ADESIVADOR && (it.ativo || it.id in comCarro) }

        return colunas.map { coluna ->
            val meus = agendamentos.filter { it.adesivador.id == coluna.id }
            // Copias do mesmo servico (a alca do canto, o colar) contam como um servico so.
            val servicos = porServico(meus.filter { it.ehServico })
            val concluidos = servicos.filter { it.status == StatusAgendamento.CONCLUIDO }

            ProdutividadeAdesivadorResponse(
                adesivadorId = coluna.id!!,
                adesivador = coluna.nome,
                servicos = servicos.size,
                concluidos = concluidos.size,
                scoreConcluido = somaScore(concluidos.mapNotNull { it.score }),
                scoreAgendado = somaScore(servicos.mapNotNull { it.score }),
                // As horas sao de todas as copias: e o tempo que o servico ocupou na agenda.
                horasAgendadas = somaHoras(meus.filter { it.ehServico }.map { it.horas }),
                horasIndisponiveis = somaHoras(meus.filterNot { it.ehServico }.map { it.horas }),
                naoCompareceu = servicos.count { it.status == StatusAgendamento.NAO_VEIO }
            )
        }
    }

    /** Um servico com as suas copias: a primeira no tempo o representa. */
    private data class ServicoComCopias(val principal: com.rastros.domain.Agendamento, val partes: List<com.rastros.domain.Agendamento>) {
        val status get() = principal.status
        /** O score e do servico: vale o que estiver lancado (antes do ajuste, podia estar numa copia so). */
        val score: BigDecimal? get() = partes.firstNotNullOfOrNull { it.score }
    }

    /**
     * Junta as copias de um mesmo servico. Na agenda, a alca do canto e o colar criam copias
     * ligadas (o mesmo grupo): para contar, listar e pontuar, sao **um servico so**.
     */
    private fun porServico(itens: List<com.rastros.domain.Agendamento>): List<ServicoComCopias> =
        itens.sortedWith(compareBy({ it.data }, { it.slotInicio }))
            .groupBy { it.grupoId ?: it.id }
            .values
            .map { ServicoComCopias(it.first(), it) }

    /** A linha do relatorio de um servico e das copias dele: do primeiro ao ultimo dia. */
    private fun linhaDoServico(
        servico: ServicoComCopias,
        fluxosPorOs: Map<Int, List<com.rastros.domain.FluxoOs>>
    ): ServicoDoRelatorioResponse {
        // Inicio e conclusao sao do servico: vale o que o adesivador registrou em qualquer copia.
        val execucao = Execucao.de(servico.principal, servico.partes)
        val linha = servico.principal.paraLinhaDoRelatorio(fluxosPorOs).copy(
            iniciadoEm = execucao.iniciadoEm,
            concluidoEm = execucao.concluidoEm,
            iniciadoPor = execucao.iniciadoEm?.let { (execucao.inicioRegistradoPor ?: execucao.iniciadoPor)?.nome },
            concluidoPor = execucao.concluidoEm?.let { (execucao.conclusaoRegistradaPor ?: execucao.iniciadoPor)?.nome }
        )
        if (servico.partes.size == 1) return linha
        val ultimoDia = servico.partes.maxOf { it.ultimoDia }
        return linha.copy(
            dataFim = ultimoDia,
            diaSemanaFim = siglaDoDia(ultimoDia),
            dias = servico.partes.flatMap { p -> generateSequence(p.data) { d -> d.plusDays(1).takeIf { !it.isAfter(p.ultimoDia) } }.toList() }
                .filter { it.dayOfWeek != java.time.DayOfWeek.SATURDAY && it.dayOfWeek != java.time.DayOfWeek.SUNDAY }
                .toSet().size,
            score = servico.score,
            horasEstimadas = somaHoras(servico.partes.map { it.horas }) ?: linha.horasEstimadas
        )
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
        val (inicio, fim) = com.rastros.domain.FaixasDoDia.semanaNoMes(dataReferencia)
        return relatorio(inicio, fim)
    }

    /** O relatorio de um periodo qualquer: a semana cortada no mes, ou o mes inteiro. */
    fun relatorio(segunda: LocalDate, sexta: LocalDate): RelatorioSemanalResponse {

        val agendamentos = agendamentoRepository.doPeriodo(segunda, sexta)
        // Quem foi removido da agenda continua aparecendo nos periodos em que trabalhou.
        val comCarro = agendamentos.mapNotNull { it.adesivador.id }.toSet()
        val colunas = adesivadorRepository.findAllByOrderByOrdemAsc()
            .filter { it.tipo == com.rastros.domain.TipoColunaAgenda.ADESIVADOR && (it.ativo || it.id in comCarro) }
        // Os fluxos de todas as OS da semana numa consulta so, e nao uma por servico.
        val osIds = agendamentos.mapNotNull { it.ordemServico?.id }.toSet()
        val fluxosPorOs = if (osIds.isEmpty()) emptyMap()
        else fluxoRepository.findByOrdemServicoIdInOrderByIdAsc(osIds).groupBy { it.ordemServico.id!! }

        // As pausas de todos os servicos do periodo numa consulta so.
        val pausasPorServico = pausas.dosServicos(agendamentos.map { pausas.chave(it) })

        return RelatorioSemanalResponse(
            inicio = segunda,
            fim = sexta,
            adesivadores = colunas.map { coluna ->
                val meus = agendamentos
                    .filter { it.adesivador.id == coluna.id }
                    .sortedWith(compareBy({ it.data }, { it.slotInicio }))
                // Copias do mesmo servico: uma linha e um servico so na conta.
                val servicos = porServico(meus.filter { it.ehServico })
                val concluidos = servicos.filter { it.status == StatusAgendamento.CONCLUIDO }
                val linhas = (servicos.map { it.principal to linhaDoServico(it, fluxosPorOs) } +
                    meus.filterNot { it.ehServico }.map { it to it.paraLinhaDoRelatorio(fluxosPorOs) })
                    .sortedWith(compareBy({ it.first.data }, { it.first.slotInicio }))
                    .map { it.second }

                RelatorioAdesivadorResponse(
                    adesivadorId = coluna.id!!,
                    adesivador = coluna.nome,
                    servicos = comPausas(linhas, meus, pausasPorServico),
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

    /**
     * A busca do relatorio: em **todos os meses**, pelo numero da OS ou pelo carro/servico.
     * Traz os mais recentes primeiro, ate [LIMITE_DA_BUSCA]; com menos de 2 letras, nada.
     */
    @Transactional(readOnly = true)
    fun buscarServicos(termo: String): BuscaRelatorioResponse {
        val limpo = termo.trim()
        if (limpo.length < 2) return BuscaRelatorioResponse(limpo, emptyList(), false)
        val achados = agendamentoRepository.buscarServicos(
            limpo, com.rastros.domain.TipoAgendamento.SERVICO,
            org.springframework.data.domain.PageRequest.of(0, LIMITE_DA_BUSCA + 1)
        )
        val mostrados = achados.take(LIMITE_DA_BUSCA)
        val osIds = mostrados.mapNotNull { it.ordemServico?.id }.toSet()
        val fluxosPorOs = if (osIds.isEmpty()) emptyMap()
        else fluxoRepository.findByOrdemServicoIdInOrderByIdAsc(osIds).groupBy { it.ordemServico.id!! }
        return BuscaRelatorioResponse(
            termo = limpo,
            servicos = mostrados.groupBy { it.adesivador.id }.flatMap { (_, doAdesivador) ->
                porServico(doAdesivador).map { ServicoEncontradoResponse(it.principal.adesivador.nome, linhaDoServico(it, fluxosPorOs)) }
            }.sortedByDescending { it.servico.data }.let { encontrados ->
                val comAsPausas = comPausas(encontrados.map { it.servico }, mostrados).associateBy { it.agendamentoId }
                encontrados.map { it.copy(servico = comAsPausas[it.servico.agendamentoId] ?: it.servico) }
            },
            limitado = achados.size > LIMITE_DA_BUSCA
        )
    }

    private fun com.rastros.domain.Agendamento.paraLinhaDoRelatorio(
        fluxosPorOs: Map<Int, List<com.rastros.domain.FluxoOs>>
    ): ServicoDoRelatorioResponse {
        val posicoes = posicoesOcupadas
        val ultima = com.rastros.domain.FaixasDoDia.faixaDaPosicao(posicoes.last())
        val os = ordemServico
        val fluxos = os?.let { fluxosPorOs[it.id].orEmpty() }
        val ativos = fluxos?.filter { it.statusAtual != com.rastros.domain.StatusFluxo.CANCELADA }

        return ServicoDoRelatorioResponse(
            agendamentoId = id!!,
            data = data,
            diaSemana = siglaDoDia(data),
            horarioInicio = com.rastros.domain.FaixasDoDia.de(slotInicio).inicio,
            horarioFim = com.rastros.domain.FaixasDoDia.de(ultima).fim,
            dataFim = ultimoDia,
            diaSemanaFim = siglaDoDia(ultimoDia),
            dias = posicoes.last() / com.rastros.domain.FaixasDoDia.QUANTIDADE + 1,
            descricao = descricao,
            status = status,
            tipo = tipo ?: com.rastros.domain.TipoAgendamento.SERVICO,
            vendedor = vendedorCodigo,
            etiquetaId = etiquetaId,
            numeroOsErp = os?.numeroOsErp,
            osId = os?.id,
            materialPronto = ativos?.let { lista ->
                lista.isNotEmpty() && lista.all { it.encerrado || it.setorAtual.nome.saiuDaProducao }
            },
            score = score,
            horasEstimadas = horas,
            iniciadoEm = iniciadoEm,
            concluidoEm = concluidoEm,
            // Antes da V19 nao se guardava quem registrou: vale o adesivador que iniciou.
            iniciadoPor = iniciadoEm?.let { (inicioRegistradoPor ?: iniciadoPor)?.nome },
            concluidoPor = concluidoEm?.let { (conclusaoRegistradaPor ?: iniciadoPor)?.nome }
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
