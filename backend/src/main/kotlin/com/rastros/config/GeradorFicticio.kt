package com.rastros.config

import com.rastros.domain.*
import com.rastros.repository.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.random.Random

/**
 * Lancamentos ficticios com cara de operacao real, coerentes do comeco ao fim:
 *
 * - **OS como vinham do ERP**: numero de 5 digitos em ordem de abertura, cliente pela razao
 *   social e o servico como a extensao lia (a campanha, ou "1x produto"). O fluxo leva o
 *   nome do servico, como a extensao cria.
 * - **Cada OS segue o caminho certo** pela matriz: Criacao, Impressao, Recorte ou
 *   Preparacao, Frota (ou Acabamento), Patio (ou Prateleira) e Financeiro - ate onde o
 *   "agora" deixa. O que ainda nao aconteceu fica na fila certa.
 * - **Frota**: a agenda preenchida como o escritorio preenche, pela alca que replica: cada
 *   servico e uma copia por espaco (1 a 3 espacos; caminhao e onibus passam para o dia
 *   seguinte), varios servicos por dia para cada adesivador, com um espaco livre aqui e
 *   ali. O inicio e a conclusao sao registrados por ele, um servico depois do outro, com
 *   pausas de vez em quando; a OS e recebida na Frota quando ele inicia e vai ao Patio
 *   quando ele conclui. O card da agenda fica ligado a OS.
 * - **Hoje, para o painel**: cada adesivador com o que ja concluiu, o que esta fazendo
 *   ("Agora") e o que vem depois; um deles pausado, um que comecou outro sem concluir o
 *   anterior, e um servico que comecou ontem e ainda ocupa a manha de hoje.
 *
 * Periodo: umas quatro semanas para tras e duas para a frente.
 *
 * Roda sozinho no banco novo de demonstracao (rastros.demo-completa) ou quando pedido:
 *   --rastros.ficticio.gerar=true   gera (uma vez; se ja houver ficticio, nao faz nada)
 *   --rastros.ficticio.apagar=true  apaga tudo o que foi gerado, e so isso
 *
 * Tudo leva a marca [ficticio] - na observacao do evento de abertura da OS e na do card da
 * agenda - e e por ela que a limpeza encontra o que apagar.
 */
@Component
class GeradorFicticio(
    private val usuarioRepository: UsuarioRepository,
    private val setorRepository: SetorRepository,
    private val transicaoRepository: TransicaoPermitidaRepository,
    private val ordemRepository: OrdemServicoRepository,
    private val fluxoRepository: FluxoOsRepository,
    private val eventoRepository: EventoMovimentacaoRepository,
    private val adesivadorRepository: AdesivadorRepository,
    private val agendamentoRepository: AgendamentoRepository,
    private val pausaRepository: PausaProjetoRepository,
    private val jdbc: JdbcTemplate,
    @Value("\${rastros.ficticio.gerar:false}") private val gerar: Boolean,
    @Value("\${rastros.ficticio.apagar:false}") private val apagar: Boolean
) : CommandLineRunner {

    private val log = LoggerFactory.getLogger(javaClass)
    private val zona = ZoneId.systemDefault()
    private val sorte = Random(20260921)
    private val agora = Instant.now()

    @Transactional
    override fun run(vararg args: String) {
        if (apagar) apagarTudo()
        if (gerar) gerarTudo()
    }

    // ================================================================== limpeza

    internal fun apagarTudo() {
        val osIds = jdbc.queryForList(
            "SELECT DISTINCT f.os_id FROM eventos_movimentacao e JOIN fluxos_os f ON f.id = e.fluxo_id " +
                "WHERE e.tipo_evento = 'CRIACAO' AND e.observacao LIKE ?",
            Int::class.java, "%$MARCA%"
        )
        jdbc.update(
            "DELETE FROM pausas_projeto WHERE servico_id IN (SELECT id FROM agendamentos WHERE observacao LIKE ?)",
            "%$MARCA%"
        )
        val carros = jdbc.update("DELETE FROM agendamentos WHERE observacao LIKE ?", "%$MARCA%")
        osIds.chunked(500).forEach { lote ->
            val ids = lote.joinToString(",")
            jdbc.update("DELETE FROM agendamentos WHERE os_id IN ($ids)")
            jdbc.update("DELETE FROM eventos_movimentacao WHERE fluxo_id IN (SELECT id FROM fluxos_os WHERE os_id IN ($ids))")
            jdbc.update("DELETE FROM fluxos_os WHERE os_id IN ($ids)")
            jdbc.update("DELETE FROM ordens_servico WHERE id IN ($ids)")
        }
        // O Desfazer poderia recriar um carro ficticio apagado.
        jdbc.update("DELETE FROM historico_agenda")
        log.warn("Ficticio: apagados {} OS e {} cards da agenda.", osIds.size, carros)
    }

    // ================================================================== geracao

    private lateinit var setores: Map<SetorNome, Setor>
    private lateinit var operadores: Map<SetorNome, List<Usuario>>
    private lateinit var vendedores: List<Usuario>
    private lateinit var financeiros: List<Usuario>
    private lateinit var diretores: List<Usuario>
    private lateinit var reserva: Usuario

    /** Uma OS planejada: o numero so e dado no fim, em ordem de abertura, como no ERP. */
    private class PlanoOs(
        val aberta: Instant,
        val cliente: Cliente,
        val servico: String,
        val vendedor: Usuario,
        val passos: List<Passo>,
        val cancelar: Boolean = false
    ) {
        var os: OrdemServico? = null
    }

    /** Um dos 5 espacos do dia, como a tela da agenda os mostra. */
    private class Espaco(
        val dia: LocalDate,
        val numero: Int,
        val faixaInicio: Int,
        val horas: BigDecimal,
        val comeca: LocalTime,
        val termina: LocalTime
    )

    /** Um servico da agenda: espacos seguidos na coluna de um adesivador, uma copia em cada. */
    private class Projeto(
        val coluna: Adesivador,
        val adesivador: Usuario,
        val espacos: List<Espaco>,
        val tipo: TipoDeServico,
        val veiculo: String,
        val cliente: Cliente,
        val vendedor: Usuario,
        var inicio: Instant,
        /** Null: iniciado e ainda nao concluido. */
        var fim: Instant?,
        val naoVeio: Boolean,
        var pausa: Pair<Instant, Instant?>?
    ) {
        var plano: PlanoOs? = null
    }

    private class Frota(val projetos: List<Projeto>, val bloqueios: List<Pair<Adesivador, Espaco>>)

    @Transactional
    internal fun gerarTudo() {
        val jaTem = jdbc.queryForObject(
            "SELECT COUNT(*) FROM eventos_movimentacao WHERE observacao LIKE ?", Long::class.java, "%$MARCA%"
        ) ?: 0L
        if (jaTem > 0) {
            log.warn("Ficticio: ja existe lancamento ficticio no banco - nada foi gerado. Apague antes com --rastros.ficticio.apagar=true.")
            return
        }

        val ativos = usuarioRepository.findAll().filter { it.ativo }
        setores = setorRepository.findAll().associateBy { it.nome }
        operadores = SetorNome.entries.associateWith { s ->
            ativos.filter { it.perfil.nome == PerfilNome.OPERACIONAL && it.setores.any { x -> x.nome == s } }
        }
        vendedores = ativos.filter { it.perfil.nome == PerfilNome.VENDEDOR }
        financeiros = ativos.filter { it.perfil.nome == PerfilNome.FINANCEIRO }
        diretores = ativos.filter { it.perfil.nome == PerfilNome.DIRETORIA }
        reserva = ativos.firstOrNull { it.perfil.nome == PerfilNome.ADMIN } ?: error("Nenhum administrador ativo.")
        if (vendedores.isEmpty()) error("Nenhum vendedor ativo: cadastre o comercial antes de gerar.")

        val frota = planejarFrota()
        val projetos = frota.projetos
        val acabamento = planejarAcabamento()
        val planos = (projetos.mapNotNull { it.plano } + acabamento).sortedBy { it.aberta }

        // Numeros de OS em ordem de abertura, com saltos (o ERP numera tudo, nao so producao).
        // Banco novo de demonstracao: no padrao do ERP (5 digitos). Banco com OS de verdade:
        // a partir de 90001, para nunca se confundir com um numero do ERP.
        var numero = if (ordemRepository.count() == 0L) 56180 else 90000
        planos.forEach { plano ->
            numero += 1 + (if (sorte.nextInt(100) < 30) sorte.nextInt(1, 4) else 0)
            plano.os = abrirOs(numero.toString(), plano)
        }
        val cards = gravarAgenda(frota)
        log.warn(
            "Ficticio: {} OS ({} da Frota, {} de acabamento), {} servicos em {} cards na agenda de {} adesivadores.",
            planos.size, projetos.count { it.plano != null }, acabamento.size, projetos.size, cards,
            projetos.map { it.coluna.id }.distinct().size
        )
    }

    // ------------------------------------------------------------------ clientes

    private class Cliente(val razao: String, val fantasia: String)

    private val cooperativas = listOf(
        Cliente("Cooperativa de Taxistas Vila Nova Ltda", "COOPERVILA"),
        Cliente("Radio Taxi Estrela Azul Ltda", "ESTRELA AZUL"),
        Cliente("Associacao dos Taxistas do Aeroporto Norte", "TAXI NORTE"),
        Cliente("Cooperativa Taxi Centro Sul Ltda", "CENTRO SUL")
    )
    private val transportadoras = listOf(
        Cliente("Translog Cargas e Encomendas Ltda", "TRANSLOG"),
        Cliente("Rodoviario Serra Alta Ltda", "SERRA ALTA"),
        Cliente("Frigorifico Campo Nobre S/A", "CAMPO NOBRE"),
        Cliente("Expresso Vale do Sol Transportes Ltda", "VALE DO SOL"),
        Cliente("Distribuidora de Alimentos Bom Preco Ltda", "BOM PRECO")
    )
    private val empresas = listOf(
        Cliente("Climafrio Refrigeracao e Climatizacao Ltda", "CLIMAFRIO"),
        Cliente("Eletrica Faisca Instalacoes Ltda ME", "FAISCA"),
        Cliente("Agropecuaria Terra Boa Ltda", "TERRA BOA"),
        Cliente("Clinica Odontologica Sorriso Pleno Ltda", "SORRISO PLENO"),
        Cliente("Dedetizadora Casa Limpa Ltda ME", "CASA LIMPA"),
        Cliente("Net Veloz Telecomunicacoes Ltda", "NET VELOZ"),
        Cliente("Imobiliaria Lar Ideal Ltda", "LAR IDEAL"),
        Cliente("Confeitaria Maria Flor Ltda ME", "MARIA FLOR"),
        Cliente("Construtora Alicerce Forte Ltda", "ALICERCE"),
        Cliente("Pet Center Amigo Leal Ltda ME", "AMIGO LEAL"),
        Cliente("Locadora Rota Livre Veiculos Ltda", "ROTA LIVRE")
    )
    private val turismo = listOf(
        Cliente("Viacao Rota das Serras Ltda", "ROTA DAS SERRAS"),
        Cliente("Turismo Horizonte Azul Ltda", "HORIZONTE AZUL")
    )

    private fun <T> List<T>.qualquer(): T = this[sorte.nextInt(size)]

    // ------------------------------------------------------------------ frota

    /** O que se faz na Frota: veiculo, texto do ERP, caminho da producao e tamanho em espacos. */
    private class TipoDeServico(
        val peso: Int,
        val espacos: IntRange,
        val veiculo: () -> String,
        val servicoDoErp: () -> String,
        val clientes: List<Cliente>,
        val rota: List<SetorNome>,
        val score: Double
    )

    private val ano = LocalDate.now(zona).year

    private val tiposDeServico by lazy {
        listOf(
            TipoDeServico(
                peso = 26, espacos = 1..2,
                veiculo = { "TAXI ${listOf("SPIN", "ONIX", "COBALT", "CRONOS", "VIRTUS", "LOGAN").qualquer()} ${sorte.nextInt(100, 2999)}" },
                servicoDoErp = { "FROTA TAXI $ano - ADESIVACAO COMPLETA" },
                clientes = cooperativas, rota = listOf(SetorNome.CRIACAO, SetorNome.IMPRESSAO, SetorNome.RECORTE), score = 3.0
            ),
            TipoDeServico(
                peso = 10, espacos = 1..1,
                veiculo = { "REPARO TAXI ${sorte.nextInt(100, 2999)}" },
                servicoDoErp = { listOf("1x REPARO ADESIVO PORTA", "1x TROCA FAIXA LATERAL").qualquer() },
                clientes = cooperativas, rota = listOf(SetorNome.CRIACAO, SetorNome.IMPRESSAO), score = 1.0
            ),
            TipoDeServico(
                peso = 16, espacos = 2..3,
                veiculo = { listOf("SPRINTER", "MASTER", "DUCATO", "DAILY", "TRANSIT", "EXPERT").qualquer() },
                servicoDoErp = { listOf("1x ADESIVACAO VAN LATERAIS E TRASEIRA", "CAMPANHA: FROTA DE ENTREGAS $ano").qualquer() },
                clientes = transportadoras + empresas, rota = listOf(SetorNome.CRIACAO, SetorNome.IMPRESSAO, SetorNome.RECORTE),
                score = 5.0
            ),
            TipoDeServico(
                peso = 14, espacos = 1..2,
                veiculo = { listOf("HILUX", "S10", "STRADA", "TORO", "SAVEIRO", "RANGER").qualquer() },
                servicoDoErp = { listOf("1x ADESIVACAO PORTAS E CACAMBA", "1x PLOTAGEM LOGO + TELEFONE").qualquer() },
                clientes = empresas, rota = listOf(SetorNome.CRIACAO, SetorNome.IMPRESSAO), score = 2.5
            ),
            TipoDeServico(
                peso = 14, espacos = 2..4,
                veiculo = { listOf("KICKS", "COMPASS", "T-CROSS", "HB20", "ARGO", "FIORINO", "KANGOO", "BYD DOLPHIN").qualquer() },
                servicoDoErp = { listOf("1x ENVELOPAMENTO TOTAL VEICULO", "1x ADESIVACAO VEICULO COMPLETA").qualquer() },
                clientes = empresas, rota = listOf(SetorNome.CRIACAO, SetorNome.IMPRESSAO, SetorNome.RECORTE), score = 5.0
            ),
            TipoDeServico(
                peso = 6, espacos = 1..2,
                veiculo = { "MOTO ${listOf("DUCATTI", "BMW GS", "HONDA CB", "TRIUMPH").qualquer()} PPF" },
                servicoDoErp = { "1x PELICULA PPF MOTO" },
                clientes = empresas, rota = listOf(SetorNome.CRIACAO, SetorNome.RECORTE), score = 2.0
            ),
            TipoDeServico(
                peso = 3, espacos = 5..6,
                veiculo = { listOf("CAMINHAO VW 24.280 BAU", "CAMINHAO MB ATEGO BAU", "CAMINHAO IVECO TECTOR BAU").qualquer() },
                servicoDoErp = { "1x ENVELOPAMENTO BAU COMPLETO" },
                clientes = transportadoras, rota = listOf(SetorNome.CRIACAO, SetorNome.IMPRESSAO, SetorNome.PREPARACAO),
                score = 11.0
            ),
            TipoDeServico(
                peso = 1, espacos = 6..8,
                veiculo = { "ONIBUS MARCOPOLO ${listOf("PARADISO", "VIAGGIO", "TORINO").qualquer()}" },
                servicoDoErp = { "CAMPANHA: ENVELOPAMENTO FROTA TURISMO $ano" },
                clientes = turismo, rota = listOf(SetorNome.CRIACAO, SetorNome.IMPRESSAO, SetorNome.PREPARACAO), score = 12.0
            )
        )
    }

    private fun sortearTipo(): TipoDeServico {
        var n = sorte.nextInt(tiposDeServico.sumOf { it.peso })
        for (t in tiposDeServico) {
            n -= t.peso
            if (n < 0) return t
        }
        return tiposDeServico.last()
    }

    /**
     * Os 5 espacos do dia, pela mesma conta da tela (blocos.ts): cada faixa de horario cai no
     * espaco onde fica o meio dela. Uma copia ocupa exatamente o seu espaco.
     */
    private fun espacosDoDia(dia: LocalDate): List<Espaco> {
        val uteis = FaixasDoDia.TODAS.filter { !it.almoco && !FaixasDoDia.fechadaNoDia(dia, it.indice) }
        val porEspaco = uteis.sumOf { it.horas.toDouble() } / ESPACOS_POR_DIA
        var acumulado = 0.0
        val porNumero = linkedMapOf<Int, MutableList<FaixaHoraria>>()
        for (f in uteis) {
            val meio = acumulado + f.horas.toDouble() / 2
            acumulado += f.horas.toDouble()
            porNumero.getOrPut(minOf(ESPACOS_POR_DIA, (meio / porEspaco).toInt() + 1)) { mutableListOf() } += f
        }
        return porNumero.map { (numero, faixas) ->
            Espaco(
                dia, numero, faixas.first().indice, faixas.fold(BigDecimal.ZERO) { s, f -> s + f.horas },
                LocalTime.parse(faixas.first().inicio), LocalTime.parse(faixas.last().fim)
            )
        }
    }

    /** O espaco em que uma faixa de horario cai (o almoco fica com o espaco de antes). */
    private fun espacoDaFaixa(dia: LocalDate, faixa: Int): Int =
        espacosDoDia(dia).last { it.faixaInicio <= faixa }.numero

    /**
     * Cada adesivador com a sua coluna, espaco a espaco: um servico depois do outro, varios
     * por dia, como o escritorio preenche a agenda.
     */
    private fun planejarFrota(): Frota {
        val hoje = LocalDate.now(zona)
        val ontem = voltarDiasUteis(hoje, 1)
        val inicio = voltarDiasUteis(hoje, 20)
        val fim = avancarDiasUteis(hoje, 9)
        val dias = generateSequence(inicio) { avancarDiasUteis(it, 1) }.takeWhile { !it.isAfter(fim) }.toList()
        val colunas = adesivadorRepository.findByAtivoTrueOrderByOrdemAsc()
            .filter { it.tipo == TipoColunaAgenda.ADESIVADOR }
            .mapNotNull { coluna -> adesivadorDa(coluna)?.let { coluna to it } }
        val projetos = mutableListOf<Projeto>()
        val bloqueios = mutableListOf<Pair<Adesivador, Espaco>>()

        colunas.forEachIndexed { n, (coluna, adesivador) ->
            // Espacos com card de verdade (lancado por alguem) ficam de fora: o gerador contorna.
            val ocupados = agendamentoRepository
                .doPeriodoDoAdesivador(inicio.minusWeeks(4), fim.plusWeeks(4), coluna.id!!)
                .flatMap { a ->
                    a.posicoesOcupadas.map { p ->
                        val dia = FaixasDoDia.diaUtilAFrente(a.data, p / FaixasDoDia.QUANTIDADE)
                        dia to espacoDaFaixa(dia, p % FaixasDoDia.QUANTIDADE + 1)
                    }
                }
                .toSet()
            // De vez em quando o adesivador nao vem (folga, atestado): o dia fica indisponivel, linha a linha.
            val folgas = dias.filter { it != hoje && it != ontem && sorte.nextInt(100) < 3 }.toSet()
            val espacos = dias.flatMap { espacosDoDia(it) }
            espacos.filter { it.dia in folgas && (it.dia to it.numero) !in ocupados }.forEach { bloqueios += coluna to it }
            val livre = { e: Espaco -> e.dia !in folgas && (e.dia to e.numero) !in ocupados }

            // Na quarta coluna, um caminhao que comecou ontem a tarde e ocupa o dia de hoje inteiro.
            val longoDeOntem = if (n == 3) espacos.indexOfFirst { it.dia == ontem && it.numero == 4 } else -1
            val daColuna = mutableListOf<Projeto>()
            var livreDesde = Instant.EPOCH
            var i = 0
            while (i < espacos.size) {
                if (!livre(espacos[i])) { i++; continue }
                val forcado = i == longoDeOntem
                // Um espaco vazio aqui e ali: a agenda de verdade nao e cheia de ponta a ponta.
                // Hoje nao: o painel tem de ter sempre o que mostrar.
                if (!forcado && espacos[i].dia != hoje && sorte.nextInt(100) < 7) { i++; continue }
                val tipo = if (forcado) tiposDeServico.first { it.espacos.first == 5 } else sortearTipo()
                var quer = if (forcado) 7 else sorte.nextInt(tipo.espacos.first, tipo.espacos.last + 1)
                if (longoDeOntem > i && longoDeOntem < i + quer) quer = longoDeOntem - i
                // Hoje, servicos curtos (1 ou 2 espacos): o painel mostra o que ja foi, o de agora e o que vem.
                if (!forcado && (0 until quer).any { espacos.getOrNull(i + it)?.dia == hoje }) quer = minOf(quer, 2)
                var tamanho = 0
                while (tamanho < quer && i + tamanho < espacos.size && livre(espacos[i + tamanho])) tamanho++
                val meus = espacos.subList(i, i + tamanho).toList()
                i += tamanho

                // Um servico depois do outro: comeca no espaco dele, nunca antes do anterior acabar.
                val primeiro = meus.first()
                val ultimo = meus.last()
                val comeca = maxOf(
                    primeiro.dia.atTime(primeiro.comeca).plusMinutes(sorte.nextLong(0, 20)).atZone(zona).toInstant(),
                    livreDesde.plus(sorte.nextLong(3, 12), ChronoUnit.MINUTES)
                )
                val termina = maxOf(
                    ultimo.dia.atTime(ultimo.termina).plusMinutes(sorte.nextLong(-25, 15)).atZone(zona).toInstant(),
                    comeca.plus(35, ChronoUnit.MINUTES)
                )
                livreDesde = termina

                // O carro que nao veio: so no passado, e sem OS andando.
                val umDiaSo = meus.all { it.dia == primeiro.dia }
                val naoVeio = !forcado && termina.isBefore(agora) && umDiaSo && sorte.nextInt(100) < 3
                val minutos = ChronoUnit.MINUTES.between(comeca, termina)
                val pausa = if (!naoVeio && umDiaSo && minutos >= 90 && sorte.nextInt(100) < 15) {
                    val p = comeca.plus(sorte.nextLong(20, minutos - 60), ChronoUnit.MINUTES)
                    val volta = p.plus(sorte.nextLong(15, 45), ChronoUnit.MINUTES)
                    if (volta.isBefore(agora)) p to volta else null
                } else null
                daColuna += Projeto(
                    coluna, adesivador, meus, tipo, tipo.veiculo(), tipo.clientes.qualquer(), vendedores.qualquer(),
                    comeca, termina, naoVeio, pausa
                )
            }

            // Hoje, o que o painel tem de mostrar. O servico do espaco de agora esta em andamento
            // (o registro real nunca bate com a grade ao minuto) e o anterior ja acabou.
            val atual = daColuna.firstOrNull { p ->
                val de = p.espacos.first().let { it.dia.atTime(it.comeca).atZone(zona).toInstant() }
                val ate = p.espacos.last().let { it.dia.atTime(it.termina).atZone(zona).toInstant() }
                !p.naoVeio && !de.isAfter(agora) && ate.isAfter(agora)
            }
            if (atual != null) {
                val anterior = daColuna.getOrNull(daColuna.indexOf(atual) - 1)?.takeUnless { it.naoVeio }
                if (anterior != null && anterior.fim!!.isAfter(agora.minus(5, ChronoUnit.MINUTES))) {
                    anterior.fim = maxOf(agora.minus(sorte.nextLong(8, 15), ChronoUnit.MINUTES), anterior.inicio.plus(10, ChronoUnit.MINUTES))
                    if (anterior.pausa?.second?.isAfter(anterior.fim) == true) anterior.pausa = null
                }
                if (atual.inicio.isAfter(agora.minus(5, ChronoUnit.MINUTES))) {
                    val depoisDoAnterior = (anterior?.fim ?: Instant.EPOCH).plus(3, ChronoUnit.MINUTES)
                    atual.inicio = minOf(maxOf(depoisDoAnterior, agora.minus(sorte.nextLong(10, 30), ChronoUnit.MINUTES)), agora.minus(2, ChronoUnit.MINUTES))
                }
                if (!atual.fim!!.isAfter(agora)) atual.fim = agora.plus(sorte.nextLong(20, 90), ChronoUnit.MINUTES)
                if (atual.pausa?.second?.isAfter(agora) == true || atual.pausa?.first?.isBefore(atual.inicio) == true) atual.pausa = null
            }
            when {
                // Na segunda coluna, pausado agora (o painel mostra a pausa, sem "Agora").
                n == 1 && atual != null && ChronoUnit.MINUTES.between(atual.inicio, agora) >= 4 -> {
                    atual.pausa = atual.inicio.plus(ChronoUnit.MINUTES.between(atual.inicio, agora) * 55 / 100, ChronoUnit.MINUTES) to null
                }
                // Na terceira, comecou este sem concluir o anterior: o "Agora" e o ultimo iniciado,
                // e o anterior volta para a lista, ainda em andamento.
                n == 2 && atual != null -> {
                    daColuna.lastOrNull {
                        it !== atual && !it.naoVeio && it.fim!!.isBefore(atual.inicio) &&
                            it.espacos.last().dia == hoje
                    }?.let { anterior ->
                        anterior.fim = null
                        if (anterior.pausa?.second?.isAfter(atual.inicio) == true) anterior.pausa = null
                    }
                }
            }
            projetos += daColuna
        }

        projetos.filterNot { it.naoVeio }.forEach {
            it.plano = planoDaFrota(it.tipo, it.cliente, it.vendedor, it.adesivador, it.inicio, it.fim)
        }
        return Frota(projetos, bloqueios)
    }

    /**
     * A OS por tras do projeto: aberta dias antes pelo vendedor, passa pela producao e
     * espera na Frota; o adesivador a recebe quando inicia e a manda ao Patio quando
     * conclui; o comercial libera, e o Financeiro recebe e conclui. Projeto muito a frente
     * ainda nao tem OS; projeto ainda nao concluido deixa a OS na Frota.
     */
    private fun planoDaFrota(
        tipo: TipoDeServico, cliente: Cliente, vendedor: Usuario, adesivador: Usuario, comeca: Instant, termina: Instant?
    ): PlanoOs? {
        val aberta = voltarDiasUteis(comeca, tipo.rota.size + sorte.nextInt(1, 4), sorte.nextInt(8, 17))
        if (aberta.isAfter(agora)) return null
        // O material chega a Frota na vespera (ou no inicio da manha).
        val chegaNaFrota = voltarDiasUteis(comeca, 1, sorte.nextInt(14, 17)).coerceAtLeast(aberta.plus(3, ChronoUnit.HOURS))

        val passos = mutableListOf<Passo>()
        var t = aberta
        val fatia = horasUteisEntre(aberta, chegaNaFrota) / (tipo.rota.size + 0.5)
        tipo.rota.forEachIndexed { i, setor ->
            t = somarHorasUteis(t, fatia * sorte.nextDouble(0.05, 0.3))
            passos += Passo.Receber(setor, operador(setor), t)
            t = somarHorasUteis(t, fatia * sorte.nextDouble(0.3, 0.6)).coerceAtMost(chegaNaFrota).coerceAtLeast(t.plusSeconds(60))
            passos += Passo.Despachar(setor, if (i == tipo.rota.lastIndex) SetorNome.FROTA else tipo.rota[i + 1], operador(setor), t)
        }
        // Na Frota quem recebe e despacha e o adesivador, ao iniciar e ao concluir o projeto.
        val recebe = comeca.coerceAtLeast(t.plusSeconds(60))
        passos += Passo.Receber(SetorNome.FROTA, adesivador, recebe)
        if (termina != null) {
            val conclui = termina.coerceAtLeast(recebe.plusSeconds(60))
            passos += Passo.Despachar(SetorNome.FROTA, SetorNome.PATIO, adesivador, conclui)
            fecharNoFinanceiro(passos, SetorNome.PATIO, vendedor, conclui)
        }
        return PlanoOs(aberta, cliente, tipo.servicoDoErp(), vendedor, passos)
    }

    // --------------------------------------------------------------- acabamento

    private val pecas = listOf(
        "1x PLACA ACM 2,00 x 0,80", "2x BANNER LONA 1,50 x 1,00", "1x ADESIVO VITRINE JATEADO",
        "10x PLACA PVC SINALIZACAO", "1x FACHADA ACM LETRA CAIXA", "4x WIND BANNER",
        "1x ADESIVO PAREDE 3,00 x 2,40", "50x ETIQUETA PATRIMONIO", "1x TOTEM RECEPCAO",
        "CAMPANHA: INAUGURACAO LOJA CENTRO", "CAMPANHA: SINALIZACAO NOVA SEDE", "6x PLACA DE OBRA LONA"
    )
    private val rotasAcabamento = listOf(
        listOf(SetorNome.CRIACAO, SetorNome.IMPRESSAO, SetorNome.ACABAMENTO),
        listOf(SetorNome.CRIACAO, SetorNome.IMPRESSAO, SetorNome.ACABAMENTO),
        listOf(SetorNome.CRIACAO, SetorNome.RECORTE, SetorNome.ACABAMENTO),
        listOf(SetorNome.CRIACAO, SetorNome.IMPRESSAO, SetorNome.RECORTE, SetorNome.ACABAMENTO)
    )

    /** Placas, banners e adesivos: vao para a Prateleira e dali para o Financeiro. */
    private fun planejarAcabamento(): List<PlanoOs> {
        val desde = voltarDiasUteis(LocalDate.now(zona), 20).atTime(8, 0).atZone(zona).toInstant()
        val ontemCedo = voltarDiasUteis(LocalDate.now(zona), 1).atTime(8, 30).atZone(zona).toInstant()
        return (1..32).map { i ->
            val vendedor = vendedores.qualquer()
            // As ultimas abriram ontem cedo, andaram rapido e estao no Acabamento agora (o painel mostra).
            val naFila = i > 28
            val aberta = if (naFila) ontemCedo.plus(sorte.nextLong(0, 90), ChronoUnit.MINUTES)
            else somarHorasUteis(desde, sorte.nextDouble(0.0, 20 * 10.0)).coerceAtMost(agora.minus(40, ChronoUnit.MINUTES))
            val rota = rotasAcabamento.qualquer()
            val passos = mutableListOf<Passo>()
            var t = aberta
            rota.forEachIndexed { j, setor ->
                val noAcabamento = setor == SetorNome.ACABAMENTO
                t = somarHorasUteis(t, if (naFila) sorte.nextDouble(0.2, 0.8) else sorte.nextDouble(0.3, 3.0))
                passos += Passo.Receber(setor, operador(setor), t)
                t = somarHorasUteis(
                    t, when {
                        naFila && noAcabamento -> sorte.nextDouble(30.0, 40.0)
                        naFila -> sorte.nextDouble(1.0, 2.5)
                        else -> sorte.nextDouble(1.5, 9.0)
                    }
                )
                passos += Passo.Despachar(setor, if (j == rota.lastIndex) SetorNome.PRATELEIRA else rota[j + 1], operador(setor), t)
            }
            fecharNoFinanceiro(passos, SetorNome.PRATELEIRA, vendedor, t)
            // Uma delas o cliente desiste, e a Diretoria cancela (o historico fica).
            PlanoOs(aberta, empresas.qualquer(), pecas.qualquer(), vendedor, passos, cancelar = i == 7 && diretores.isNotEmpty())
        }
    }

    // ------------------------------------------------------------ trajetorias

    private sealed class Passo(val quando: Instant) {
        class Receber(val setor: SetorNome, val quem: Usuario, t: Instant) : Passo(t)
        class Despachar(val de: SetorNome, val para: SetorNome, val quem: Usuario, t: Instant) : Passo(t)
        class Concluir(val quem: Usuario, t: Instant) : Passo(t)
    }

    /** Do Patio/Prateleira o comercial libera; o Financeiro recebe e conclui. */
    private fun fecharNoFinanceiro(passos: MutableList<Passo>, local: SetorNome, vendedor: Usuario, depoisDe: Instant) {
        var t = somarHorasUteis(depoisDe, sorte.nextDouble(1.0, 20.0))
        passos += Passo.Despachar(local, SetorNome.FINANCEIRO, vendedor, t)
        val quem = financeiros.ifEmpty { listOf(reserva) }.qualquer()
        t = somarHorasUteis(t, sorte.nextDouble(0.3, 5.0))
        passos += Passo.Receber(SetorNome.FINANCEIRO, quem, t)
        t = somarHorasUteis(t, sorte.nextDouble(1.0, 12.0))
        passos += Passo.Concluir(quem, t)
    }

    /**
     * Grava a OS como a extensao do ERP criaria e reproduz a trajetoria ate agora: o que tem
     * data no passado vira evento; o primeiro passo no futuro para tudo, e a OS fica onde
     * estaria hoje.
     */
    private fun abrirOs(numero: String, plano: PlanoOs): OrdemServico {
        val primeiro = (plano.passos.first() as Passo.Receber).setor
        garantirRota(primeiro, plano.passos)

        val os = ordemRepository.save(
            OrdemServico(
                numeroOsErp = numero, cliente = plano.cliente.razao, servico = plano.servico,
                criadoPor = plano.vendedor, criadoEm = plano.aberta
            )
        )
        val fluxo = fluxoRepository.save(
            FluxoOs(
                ordemServico = os, identificadorFluxo = plano.servico.take(50),
                setorAtual = setores.getValue(primeiro), entrouNoSetorEm = plano.aberta
            )
        )
        evento(fluxo, null, primeiro, TipoEvento.CRIACAO, plano.vendedor, plano.aberta, "Enviada pela extensao do ERP. $MARCA")

        var ultimoEvento = plano.aberta
        for (passo in plano.passos) {
            if (passo.quando.isAfter(agora)) break
            ultimoEvento = passo.quando
            when (passo) {
                is Passo.Receber -> {
                    evento(fluxo, fluxo.setorAnterior?.nome, passo.setor, TipoEvento.RECEBIMENTO, passo.quem, passo.quando)
                    fluxo.statusAtual = StatusFluxo.EM_PROCESSAMENTO
                    fluxo.recebidoPor = passo.quem
                    fluxo.recebidoEm = passo.quando
                }
                is Passo.Despachar -> {
                    val tipo = if (passo.para == SetorNome.FINANCEIRO) TipoEvento.ENTREGA else TipoEvento.DESPACHO
                    evento(fluxo, passo.de, passo.para, tipo, passo.quem, passo.quando)
                    fluxo.setorAnterior = setores.getValue(passo.de)
                    fluxo.setorAtual = setores.getValue(passo.para)
                    fluxo.entrouNoSetorEm = passo.quando
                    fluxo.recebidoPor = null
                    fluxo.recebidoEm = null
                    fluxo.statusAtual = StatusFluxo.AGUARDANDO_RECEBIMENTO
                }
                is Passo.Concluir -> {
                    evento(fluxo, SetorNome.FINANCEIRO, SetorNome.FINANCEIRO, TipoEvento.CONCLUSAO, passo.quem, passo.quando)
                    fluxo.statusAtual = StatusFluxo.ENCERRADA
                    fluxo.encerrado = true
                    fluxo.encerradoEm = passo.quando
                }
            }
        }

        if (plano.cancelar && !fluxo.encerrado) {
            // Depois do ultimo movimento: o historico continua em ordem.
            val quando = ultimoEvento.plus(2, ChronoUnit.HOURS).coerceAtMost(agora).coerceAtLeast(ultimoEvento.plusSeconds(60))
            val motivo = "Cliente desistiu do pedido."
            evento(fluxo, fluxo.setorAtual.nome, fluxo.setorAtual.nome, TipoEvento.CANCELAMENTO, diretores.first(), quando, motivo)
            fluxo.statusAtual = StatusFluxo.CANCELADA
            fluxo.encerrado = true
            fluxo.encerradoEm = quando
            fluxo.motivoCancelamento = motivo
            os.cancelada = true
            os.motivoCancelamento = motivo
            ordemRepository.save(os)
        }
        fluxoRepository.save(fluxo)
        return os
    }

    // ------------------------------------------------------------------ agenda

    /**
     * Os cards da agenda como a alca que replica os deixa: uma copia por espaco, todas do
     * mesmo servico (mesma OS, mesmo estado), e o Indisponivel linha a linha.
     */
    private fun gravarAgenda(frota: Frota): Int {
        val codigos = vendedores.associateWith { it.nome.trim().take(1).uppercase() }
        val inicioDaSemana = LocalDate.now(zona).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val amanha = avancarDiasUteis(LocalDate.now(zona), 1)
        var cards = 0
        for ((coluna, e) in frota.bloqueios) {
            agendamentoRepository.save(
                Agendamento(
                    data = e.dia, adesivador = coluna, slotInicio = e.faixaInicio, tipo = TipoAgendamento.INDISPONIVEL,
                    horasEstimadas = e.horas, descricao = "INDISPONÍVEL", status = StatusAgendamento.PROGRAMADO,
                    observacao = MARCA_AGENDA, criadoPor = reserva
                )
            )
            cards++
        }
        for (p in frota.projetos) {
            val iniciado = !p.naoVeio && !p.inicio.isAfter(agora)
            val concluido = iniciado && p.fim?.isAfter(agora) == false
            val status = when {
                p.naoVeio -> StatusAgendamento.NAO_VEIO
                concluido -> StatusAgendamento.CONCLUIDO
                iniciado -> StatusAgendamento.EXECUTANDO
                // O carro de hoje ou de amanha que ja chegou fica "Em patio"; de vez em quando
                // um servico a frente e feito fora (Externo).
                !p.espacos.first().dia.isAfter(amanha) && sorte.nextInt(100) < 45 -> StatusAgendamento.EM_PATIO
                sorte.nextInt(100) < 5 -> StatusAgendamento.EXTERNO
                else -> StatusAgendamento.PROGRAMADO
            }
            // O score se lanca depois, no relatorio: a semana atual ainda tem uns sem.
            val score = if (concluido && (p.espacos.last().dia.isBefore(inicioDaSemana) || sorte.nextInt(100) < 50))
                BigDecimal(p.tipo.score) else null

            var grupo: Long? = null
            p.espacos.forEachIndexed { i, e ->
                val card = agendamentoRepository.save(
                    Agendamento(
                        data = e.dia, adesivador = p.coluna, slotInicio = e.faixaInicio, tipo = TipoAgendamento.SERVICO,
                        horasEstimadas = e.horas,
                        descricao = "${p.veiculo} ${p.cliente.fantasia}".take(200),
                        vendedorCodigo = codigos[p.vendedor], status = status, score = if (i == 0) score else null,
                        ordemServico = p.plano?.os, observacao = MARCA_AGENDA, grupoId = grupo,
                        criadoPor = p.vendedor, criadoEm = p.plano?.aberta ?: p.inicio.minus(3, ChronoUnit.DAYS),
                        iniciadoEm = if (iniciado) p.inicio else null,
                        iniciadoPor = if (iniciado) p.adesivador else null,
                        inicioRegistradoPor = if (iniciado) p.adesivador else null,
                        concluidoEm = if (concluido) p.fim else null,
                        conclusaoRegistradaPor = if (concluido) p.adesivador else null
                    )
                )
                // A primeira copia e a chave do servico: as outras apontam para ela.
                if (p.espacos.size > 1 && i == 0) {
                    grupo = card.id
                    card.grupoId = card.id
                    agendamentoRepository.save(card)
                }
                if (i == 0 && p.pausa != null && iniciado) {
                    val (de, ate) = p.pausa!!
                    pausaRepository.save(
                        PausaProjeto(
                            servicoId = card.id!!, inicio = de, fim = ate?.takeIf { concluido || it.isBefore(agora) },
                            pausadoPor = p.adesivador, retomadoPor = p.adesivador.takeIf { ate != null }
                        )
                    )
                }
                cards++
            }
        }
        return cards
    }

    // ------------------------------------------------------------ utilidades

    /** O adesivador da coluna: o usuario ligado a ela, ou o da Frota com o mesmo nome. */
    private fun adesivadorDa(coluna: Adesivador): Usuario? =
        coluna.usuario ?: operadores[SetorNome.FROTA].orEmpty()
            .firstOrNull { it.nome.trim().equals(coluna.nome.trim(), ignoreCase = true) }

    /** Nada fora da matriz de transicao: se a rota nao e permitida, e erro do gerador. */
    private fun garantirRota(primeiro: SetorNome, passos: List<Passo>) {
        check(transicaoRepository.existsBySetorOrigemAndSetorDestino(null, primeiro)) {
            "Ficticio: $primeiro nao e porta de entrada de OS na matriz."
        }
        passos.filterIsInstance<Passo.Despachar>().forEach {
            check(transicaoRepository.existsBySetorOrigemAndSetorDestino(it.de, it.para)) {
                "Ficticio: ${it.de} -> ${it.para} nao esta na matriz de transicao."
            }
        }
    }

    private fun evento(
        fluxo: FluxoOs, de: SetorNome?, para: SetorNome, tipo: TipoEvento, quem: Usuario, quando: Instant,
        observacao: String? = null
    ) {
        eventoRepository.save(
            EventoMovimentacao(
                fluxo = fluxo, setorOrigem = de?.let { setores.getValue(it) }, setorDestino = setores.getValue(para),
                tipoEvento = tipo, usuario = quem, dataHora = quando, observacao = observacao
            )
        )
    }

    private fun operador(setor: SetorNome): Usuario = operadores[setor].orEmpty().ifEmpty { listOf(reserva) }.qualquer()

    // ------------------------------------------------------ horario comercial

    private fun util(d: LocalDate) = d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY
    private fun diaUtil(d: LocalDate): LocalDate { var x = d; while (!util(x)) x = x.plusDays(1); return x }

    private fun avancarDiasUteis(de: LocalDate, dias: Int): LocalDate {
        var d = de
        var restantes = dias
        while (restantes > 0) { d = d.plusDays(1); if (util(d)) restantes-- }
        return diaUtil(d)
    }

    private fun voltarDiasUteis(de: LocalDate, dias: Int): LocalDate {
        var d = de
        var restantes = dias
        while (restantes > 0) { d = d.minusDays(1); if (util(d)) restantes-- }
        return diaUtil(d)
    }

    /** N dias uteis antes, numa hora cheia do expediente. */
    private fun voltarDiasUteis(de: Instant, dias: Int, horaDoDia: Int): Instant =
        voltarDiasUteis(LocalDateTime.ofInstant(de, zona).toLocalDate(), dias)
            .atTime(horaDoDia, sorte.nextInt(0, 60)).atZone(zona).toInstant()

    /** Soma horas de expediente (07:30 as 17:30, dias uteis). */
    private fun somarHorasUteis(de: Instant, horas: Double): Instant {
        var t = LocalDateTime.ofInstant(de, zona)
        var minutos = (horas * 60).toLong()
        while (true) {
            if (!util(t.toLocalDate()) || t.toLocalTime() >= FIM) {
                t = diaUtil(t.toLocalDate().plusDays(1)).atTime(INICIO); continue
            }
            if (t.toLocalTime() < INICIO) t = t.toLocalDate().atTime(INICIO)
            val livres = ChronoUnit.MINUTES.between(t, t.toLocalDate().atTime(FIM))
            if (minutos <= livres) return t.plusMinutes(minutos).atZone(zona).toInstant()
            minutos -= livres
            t = diaUtil(t.toLocalDate().plusDays(1)).atTime(INICIO)
        }
    }

    private fun horasUteisEntre(de: Instant, ate: Instant): Double {
        if (!ate.isAfter(de)) return 0.0
        var minutos = 0L
        var t = LocalDateTime.ofInstant(de, zona)
        val fim = LocalDateTime.ofInstant(ate, zona)
        while (t < fim) {
            val dia = t.toLocalDate()
            if (util(dia)) {
                val a = maxOf(t, dia.atTime(INICIO))
                val b = minOf(fim, dia.atTime(FIM))
                if (b > a) minutos += ChronoUnit.MINUTES.between(a, b)
            }
            t = dia.plusDays(1).atStartOfDay()
        }
        return minutos / 60.0
    }

    companion object {
        const val MARCA = "[ficticio]"
        const val MARCA_AGENDA = "Lancamento ficticio para demonstracao $MARCA"
        private val INICIO: LocalTime = LocalTime.of(7, 30)
        private val FIM: LocalTime = LocalTime.of(17, 30)
        /** Espacos de trabalho por dia na agenda (a tela: BLOCOS_POR_DIA). */
        private const val ESPACOS_POR_DIA = 5
    }
}
