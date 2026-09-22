package com.ostracker.config

import com.ostracker.domain.*
import com.ostracker.repository.*
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
import kotlin.random.Random

/**
 * Lancamentos ficticios para testar o sistema com cara de uso real, com os usuarios que
 * ja estao cadastrados: OS abertas pelos vendedores, passando pelos setores com os
 * operadores de cada um, chegando ao Patio/Prateleira, liberadas pelo comercial e
 * concluidas pelo Financeiro - e a agenda dos adesivadores cheia de carros.
 *
 * Comeca 30 dias atras e vai ate umas duas semanas a frente. Tudo o que ja passou fica
 * registrado com a data em que teria acontecido; o que ainda nao aconteceu fica na fila
 * certa (aguardando, em processamento, no Patio...).
 *
 * So roda quando pedido:
 *   --ostracker.ficticio.gerar=true   gera (uma vez; se ja houver ficticio, nao faz nada)
 *   --ostracker.ficticio.apagar=true  apaga tudo o que foi gerado, e so isso
 *
 * Tudo leva a marca [ficticio] - na observacao do evento de abertura da OS e na do
 * carro da agenda - e e por ela que a limpeza encontra o que apagar.
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
    private val jdbc: JdbcTemplate,
    @Value("\${ostracker.ficticio.gerar:false}") private val gerar: Boolean,
    @Value("\${ostracker.ficticio.apagar:false}") private val apagar: Boolean
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
        log.warn("Ficticio: apagados {} OS e {} carros da agenda.", osIds.size, carros)
    }

    // ================================================================== geracao

    private lateinit var setores: Map<SetorNome, Setor>
    private lateinit var operadores: Map<SetorNome, List<Usuario>>
    private lateinit var vendedores: List<Usuario>
    private lateinit var financeiros: List<Usuario>
    private lateinit var reserva: Usuario
    private var proximoNumero = 90001

    internal fun gerarTudo() {
        val jaTem = jdbc.queryForObject(
            "SELECT COUNT(*) FROM eventos_movimentacao WHERE observacao LIKE ?", Long::class.java, "%$MARCA%"
        ) ?: 0L
        if (jaTem > 0) {
            log.warn("Ficticio: ja existe lancamento ficticio no banco - nada foi gerado. Apague antes com --ostracker.ficticio.apagar=true.")
            return
        }

        val ativos = usuarioRepository.findAll().filter { it.ativo }
        setores = setorRepository.findAll().associateBy { it.nome }
        operadores = SetorNome.entries.associateWith { s ->
            ativos.filter { it.perfil.nome == PerfilNome.OPERACIONAL && it.setor?.nome == s }
        }
        vendedores = ativos.filter { it.perfil.nome == PerfilNome.VENDEDOR }
        financeiros = ativos.filter { it.perfil.nome == PerfilNome.FINANCEIRO }
        reserva = ativos.firstOrNull { it.perfil.nome == PerfilNome.ADMIN }
            ?: error("Nenhum administrador ativo.")
        if (vendedores.isEmpty()) error("Nenhum vendedor ativo: cadastre o comercial antes de gerar.")
        operadores.filter { (s, lista) -> lista.isEmpty() && !s.localFisico && s != SetorNome.FINANCEIRO }
            .keys.forEach { log.warn("Ficticio: setor {} sem operador ativo; o administrador faz o papel.", it) }
        if (financeiros.isEmpty()) log.warn("Ficticio: nenhum usuario do Financeiro; o administrador faz o papel.")

        val carros = gerarAgenda()
        val placas = gerarAcabamento()
        log.warn(
            "Ficticio: {} carros na agenda, {} OS de frota e {} OS de acabamento geradas ({} vendedores, {} operadores, {} do financeiro).",
            carros.first, carros.second, placas, vendedores.size, operadores.values.sumOf { it.size }, financeiros.size
        )
    }

    // ------------------------------------------------------------------ agenda

    private data class Modelo(
        val peso: Int,
        val horas: () -> Double,
        val score: (Double) -> Double,
        val descricao: () -> String,
        val cliente: () -> String,
        val rota: () -> List<SetorNome>
    )

    private val cooperativas = listOf("Coopertaxi", "Radio Taxi Uniao", "Taxi Aeroporto", "Cooperativa Central de Taxi", "Taxi Rodoviaria")
    private val transportadoras = listOf(
        "Transportes Sul", "Rodoviario Andrade", "TransNorte Logistica", "Expresso Real", "Transportadora Vale Verde",
        "Logistica Ferreira", "Frigorifico Boi Bom", "Distribuidora Ideal"
    )
    private val empresas = listOf(
        "Auto Center Veloz", "Construtora Horizonte", "Clinica Vida", "Padaria Pao Dourado", "Agropecuaria Campo Forte",
        "Eletrica Luz Viva", "Farmacia Popular Centro", "Pet Shop Amigo Fiel", "Imobiliaria Chave de Ouro",
        "Dedetizadora Limpa Tudo", "Ar Condicionado Frio Bom", "Supermercado Estrela", "Vidracaria Cristal",
        "Internet Rapida Net", "Gas Facil", "Seguranca Alerta", "Escola Pequeno Saber", "Moveis Planejados Arte"
    )
    private fun <T> List<T>.qualquer(): T = this[sorte.nextInt(size)]

    private val modelos = listOf(
        // Taxi completo: 2 a 3 dias.
        Modelo(
            peso = 26,
            horas = { listOf(18.0, 20.0, 22.0, 24.0, 27.0).qualquer() },
            score = { h -> if (h >= 24) 8.0 else if (h >= 20) 7.0 else 6.0 },
            descricao = {
                "TAXI ${sorte.nextInt(100, 1500)} ${listOf("SPIN", "COBALT", "ONIX", "VOYAGE", "CRONOS", "VIRTUS", "LOGAN", "PRISMA", "SPIN", "SPIN").qualquer()} COMPLETO"
            },
            cliente = { cooperativas.qualquer() },
            rota = { listOf(SetorNome.CRIACAO, SetorNome.IMPRESSAO, SetorNome.RECORTE) }
        ),
        // Taxi so com faixas e portas: meio dia.
        Modelo(
            peso = 6,
            horas = { listOf(4.0, 5.0).qualquer() },
            score = { 2.0 },
            descricao = { "TAXI ${sorte.nextInt(100, 1500)} ${listOf("SPIN", "ONIX", "COBALT").qualquer()} FAIXAS + PORTAS" },
            cliente = { cooperativas.qualquer() },
            rota = { listOf(SetorNome.IMPRESSAO, SetorNome.RECORTE) }
        ),
        // Caminhao: 3 dias.
        Modelo(
            peso = 12,
            horas = { 27.0 },
            score = { listOf(10.0, 11.0, 12.0).qualquer() },
            descricao = {
                listOf(
                    "CAMINHAO VW 24.280 BAU COMPLETO", "CAMINHAO MB ATEGO BAU COMPLETO", "CAMINHAO VOLVO VM BAU COMPLETO",
                    "CARRETA SIDER LATERAIS + TRASEIRA", "CAMINHAO IVECO TECTOR BAU COMPLETO", "CAVALO SCANIA R450 ENVELOPAMENTO"
                ).qualquer()
            },
            cliente = { transportadoras.qualquer() },
            rota = { listOf(SetorNome.CRIACAO, SetorNome.IMPRESSAO, SetorNome.PREPARACAO) }
        ),
        // Van / furgao: 1 dia e meio.
        Modelo(
            peso = 10,
            horas = { listOf(12.0, 14.0, 16.0).qualquer() },
            score = { listOf(4.0, 5.0).qualquer() },
            descricao = {
                listOf("SPRINTER", "MASTER", "DUCATO", "DAILY").qualquer() + " " +
                    listOf("COMPLETA", "LATERAIS + TRASEIRA", "ENVELOPAMENTO").qualquer()
            },
            cliente = { (transportadoras + empresas).qualquer() },
            rota = { listOf(SetorNome.CRIACAO, SetorNome.IMPRESSAO, SetorNome.RECORTE) }
        ),
        // Picape: meio dia.
        Modelo(
            peso = 14,
            horas = { listOf(4.0, 5.0, 6.0).qualquer() },
            score = { h -> if (h >= 6) 3.0 else 2.0 },
            descricao = {
                listOf("HILUX", "S10", "STRADA", "TORO", "SAVEIRO", "RANGER").qualquer() + " " +
                    listOf("LATERAIS", "PORTAS + CACAMBA", "ENVELOPAMENTO CAPO", "LOGO + FAIXAS").qualquer()
            },
            cliente = { empresas.qualquer() },
            rota = { listOf(SetorNome.CRIACAO, SetorNome.IMPRESSAO) }
        ),
        // Carro com servico curto: 2 horas.
        Modelo(
            peso = 26,
            horas = { listOf(1.5, 2.0, 2.0, 2.0, 3.0).qualquer() },
            score = { h -> if (h >= 3) 1.5 else 1.0 },
            descricao = {
                listOf("ONIX", "FIORINO", "KWID", "MOBI", "GOL", "HB20", "ARGO", "UP").qualquer() + " " +
                    listOf("LOGO PORTAS", "ADESIVO TRASEIRA", "LOGO + TELEFONE", "VIDRO TRASEIRO PERFURADO").qualquer()
            },
            cliente = { empresas.qualquer() },
            rota = { listOf(SetorNome.CRIACAO, SetorNome.RECORTE) }
        ),
        // Moto: 1 hora.
        Modelo(
            peso = 6,
            horas = { 1.0 },
            score = { 0.5 },
            descricao = { "MOTO ${listOf("CG 160", "BIZ", "FAN").qualquer()} BAU + TANQUE" },
            cliente = { empresas.qualquer() },
            rota = { listOf(SetorNome.CRIACAO, SetorNome.RECORTE) }
        )
    )

    /** O carro de servico curto - e o que cabe no Encaixe. */
    private val carroCurto get() = modelos[5]

    private fun sortearModelo(): Modelo {
        var n = sorte.nextInt(modelos.sumOf { it.peso })
        for (m in modelos) {
            n -= m.peso
            if (n < 0) return m
        }
        return modelos.last()
    }

    /** Retorna (carros criados, OS de frota criadas). */
    private fun gerarAgenda(): Pair<Int, Int> {
        val hoje = LocalDate.now(zona)
        val inicio = diaUtil(hoje.minusDays(30))
        val ultimo = hoje.plusDays(14)
        val colunas = adesivadorRepository.findByAtivoTrueOrderByOrdemAsc()
            .filter { it.tipo != TipoColunaAgenda.NOTURNO }
        var carros = 0
        var ordens = 0
        var bloqueios = 0

        for (coluna in colunas) {
            val encaixe = coluna.tipo == TipoColunaAgenda.ENCAIXE
            // Na Frota, quem recebe e despacha o carro e o proprio adesivador da coluna, se ele tem conta.
            val adesivadorDaColuna = operadores[SetorNome.FROTA].orEmpty()
                .firstOrNull { it.nome.trim().equals(coluna.nome.trim(), ignoreCase = true) }
            // Carros que ja estao na agenda (os de verdade) nao podem ser cobertos.
            val ocupadas = agendamentoRepository
                .doPeriodoDoAdesivador(inicio.minusWeeks(4), ultimo.plusWeeks(4), coluna.id!!)
                .flatMap { celulas(it.data, it.posicoesOcupadas) }
                .toSet()
            var posicao = sorte.nextInt(0, 3) // posicao na regua continua (9 faixas por dia util)
            while (true) {
                // Folgas entre um carro e outro; o Encaixe fica bem mais vazio.
                if (encaixe) posicao += sorte.nextInt(4, 14)
                else if (sorte.nextInt(100) < 25) posicao += sorte.nextInt(1, 4)
                // Almoco e sexta depois das 17:00 nao recebem inicio de carro.
                posicao = FaixasDoDia.livreAPartirDe(inicio, posicao)

                val dia = FaixasDoDia.diaUtilAFrente(inicio, posicao / FaixasDoDia.QUANTIDADE)
                if (dia.isAfter(ultimo)) break
                val slot = posicao % FaixasDoDia.QUANTIDADE + 1

                // De vez em quando alguem falta um dia inteiro.
                if (!encaixe && slot == 1 && bloqueios < 3 && sorte.nextInt(100) < 2 &&
                    celulas(dia, (0 until FaixasDoDia.QUANTIDADE).toList()).none { it in ocupadas }
                ) {
                    agendamentoRepository.save(
                        Agendamento(
                            data = dia, adesivador = coluna, slotInicio = 1, tipo = TipoAgendamento.INDISPONIVEL,
                            horasEstimadas = FaixasDoDia.horasUteisNoDia(dia), descricao = listOf("FALTA", "ATESTADO", "FOLGA").qualquer(),
                            status = StatusAgendamento.PROGRAMADO, observacao = MARCA_AGENDA, criadoPor = reserva
                        )
                    )
                    bloqueios++
                    posicao += FaixasDoDia.QUANTIDADE
                    continue
                }

                val modelo = if (encaixe) carroCurto else sortearModelo()
                val horas = modelo.horas()
                val posicoes = FaixasDoDia.posicoesAPartirDe(dia, slot - 1, BigDecimal(horas))
                if (celulas(dia, posicoes).any { it in ocupadas }) {
                    posicao++
                    continue
                }
                val ultimaRel = posicoes.last()
                val fimDia = FaixasDoDia.diaUtilAFrente(dia, ultimaRel / FaixasDoDia.QUANTIDADE)
                val comeca = dia.atTime(hora(FaixasDoDia.de(slot).inicio)).atZone(zona).toInstant()
                val termina = fimDia.atTime(hora(FaixasDoDia.de(ultimaRel % FaixasDoDia.QUANTIDADE + 1).fim))
                    .atZone(zona).toInstant()

                val vendedor = vendedores.qualquer()
                val status = when {
                    !termina.isAfter(agora) ->
                        if (horas <= 3 && sorte.nextInt(100) < 4) StatusAgendamento.NAO_VEIO else StatusAgendamento.CONCLUIDO
                    !comeca.isAfter(agora) -> StatusAgendamento.EXECUTANDO
                    else -> StatusAgendamento.PROGRAMADO
                }
                // O score se lanca depois, no relatorio: a semana passada ainda tem uns sem.
                val score = if (status == StatusAgendamento.CONCLUIDO &&
                    (termina.isBefore(agora.minus(7, ChronoUnit.DAYS)) || sorte.nextInt(100) < 60)
                ) BigDecimal(modelo.score(horas)) else null

                val os = if (status == StatusAgendamento.NAO_VEIO) null
                else ordemDeFrota(modelo, vendedor, comeca, termina, adesivadorDaColuna ?: operador(SetorNome.FROTA))
                if (os != null) ordens++

                agendamentoRepository.save(
                    Agendamento(
                        data = dia, adesivador = coluna, slotInicio = slot, tipo = TipoAgendamento.SERVICO,
                        horasEstimadas = BigDecimal(horas), descricao = modelo.descricao(),
                        vendedorCodigo = vendedor.nome.trim().take(1).uppercase(),
                        status = status, score = score, ordemServico = os, observacao = MARCA_AGENDA,
                        criadoPor = vendedor, criadoEm = os?.criadoEm ?: comeca.minus(3, ChronoUnit.DAYS)
                    )
                )
                carros++
                posicao = posicao - (slot - 1) + ultimaRel + 1
            }
        }
        return carros to ordens
    }

    /**
     * A OS por tras de um carro da agenda: aberta dias antes, passa pela producao, chega na
     * Frota antes do carro comecar, sai para o Patio quando o carro termina, e o comercial
     * libera para o Financeiro, que recebe e conclui. Carro muito a frente ainda nao tem OS.
     */
    private fun ordemDeFrota(
        modelo: Modelo, vendedor: Usuario, comeca: Instant, termina: Instant, naFrota: Usuario
    ): OrdemServico? {
        val producao = modelo.rota()
        val diasAntes = producao.size + sorte.nextInt(0, 3)
        var aberta = voltarDiasUteis(comeca, diasAntes, sorte.nextInt(8, 16))
        val limite = agora.minus(30, ChronoUnit.DAYS)
        if (aberta.isBefore(limite)) aberta = limite.plus(sorte.nextLong(0, 90), ChronoUnit.MINUTES)
        if (aberta.isAfter(agora)) return null
        val chegaNaFrota = comeca.minus(sorte.nextLong(30, 240), ChronoUnit.MINUTES).coerceAtLeast(aberta)

        val passos = mutableListOf<Passo>()
        var t = aberta
        val fatia = horasUteisEntre(aberta, chegaNaFrota) / (producao.size + 0.5)
        producao.forEachIndexed { i, setor ->
            t = somarHorasUteis(t, fatia * sorte.nextDouble(0.05, 0.25))
            passos += Passo.Receber(setor, operador(setor), t)
            // Pronto, o material vai para a Frota e espera o carro la - nao fica preso no setor.
            t = somarHorasUteis(t, minOf(fatia * sorte.nextDouble(0.3, 0.6), sorte.nextDouble(2.0, 9.0)))
                .coerceAtMost(chegaNaFrota).coerceAtLeast(t)
            passos += Passo.Despachar(setor, if (i == producao.lastIndex) SetorNome.FROTA else producao[i + 1], operador(setor), t)
        }
        t = comeca.coerceAtLeast(t.plusSeconds(60))
        passos += Passo.Receber(SetorNome.FROTA, naFrota, t)
        t = termina.coerceAtLeast(t.plusSeconds(60))
        passos += Passo.Despachar(SetorNome.FROTA, SetorNome.PATIO, naFrota, t)
        fecharNoFinanceiro(passos, SetorNome.PATIO, vendedor, t)

        return abrirOs(vendedor, modelo.cliente(), aberta, identificador = "Adesivacao", passos)
    }

    // --------------------------------------------------------------- acabamento

    private val pecas = listOf(
        "Placa ACM fachada", "Placa de obra", "Adesivo logo vitrine", "Adesivo recortado porta de vidro",
        "Banner lona 3x1", "Placa PVC sinalizacao", "Adesivo de parede", "Totem de recepcao",
        "Placas de identificacao de salas", "Adesivo jateado", "Faixa de lona", "Etiquetas de patrimonio",
        "Wind banner", "Adesivos de cardapio", "Placa de estacionamento"
    )
    private val rotasAcabamento = listOf(
        listOf(SetorNome.CRIACAO, SetorNome.IMPRESSAO, SetorNome.ACABAMENTO),
        listOf(SetorNome.CRIACAO, SetorNome.IMPRESSAO, SetorNome.ACABAMENTO),
        listOf(SetorNome.CRIACAO, SetorNome.RECORTE, SetorNome.ACABAMENTO),
        listOf(SetorNome.IMPRESSAO, SetorNome.ACABAMENTO),
        listOf(SetorNome.CRIACAO, SetorNome.IMPRESSAO, SetorNome.RECORTE, SetorNome.ACABAMENTO)
    )

    private fun gerarAcabamento(): Int {
        val quantidade = 45
        repeat(quantidade) {
            val vendedor = vendedores.qualquer()
            val aberta = somarHorasUteis(
                agora.minus(30, ChronoUnit.DAYS), sorte.nextDouble(0.0, 21.5 * 10)
            ).coerceAtMost(agora.minus(1, ChronoUnit.HOURS))
            val rota = rotasAcabamento.qualquer()
            val passos = mutableListOf<Passo>()
            var t = aberta
            rota.forEachIndexed { i, setor ->
                t = somarHorasUteis(t, sorte.nextDouble(0.3, 3.0))
                passos += Passo.Receber(setor, operador(setor), t)
                t = somarHorasUteis(t, sorte.nextDouble(1.5, 9.0))
                val proximo = if (i == rota.lastIndex) SetorNome.PRATELEIRA else rota[i + 1]
                passos += Passo.Despachar(setor, proximo, operador(setor), t)
            }
            fecharNoFinanceiro(passos, SetorNome.PRATELEIRA, vendedor, t)
            abrirOs(vendedor, empresas.qualquer(), aberta, pecas.qualquer(), passos)
        }
        return quantidade
    }

    // ------------------------------------------------------------ trajetorias

    private sealed class Passo(val quando: Instant) {
        class Receber(val setor: SetorNome, val quem: Usuario, t: Instant) : Passo(t)
        class Despachar(val de: SetorNome, val para: SetorNome, val quem: Usuario, t: Instant) : Passo(t)
        class Concluir(val quem: Usuario, t: Instant) : Passo(t)
    }

    /** Do Patio/Prateleira o comercial libera; o Financeiro recebe e conclui. */
    private fun fecharNoFinanceiro(passos: MutableList<Passo>, local: SetorNome, vendedor: Usuario, depoisDe: Instant) {
        var t = somarHorasUteis(depoisDe, sorte.nextDouble(1.0, 25.0))
        passos += Passo.Despachar(local, SetorNome.FINANCEIRO, vendedor, t)
        val quem = financeiros.ifEmpty { listOf(reserva) }.qualquer()
        t = somarHorasUteis(t, sorte.nextDouble(0.3, 6.0))
        passos += Passo.Receber(SetorNome.FINANCEIRO, quem, t)
        t = somarHorasUteis(t, sorte.nextDouble(1.0, 18.0))
        passos += Passo.Concluir(quem, t)
    }

    /**
     * Grava a OS e reproduz a trajetoria ate agora: o que tem data no passado vira evento;
     * o primeiro passo no futuro para tudo, e a OS fica onde estaria hoje.
     */
    private fun abrirOs(vendedor: Usuario, cliente: String, aberta: Instant, identificador: String, passos: List<Passo>): OrdemServico {
        val primeiro = (passos.first() as Passo.Receber).setor
        garantirRota(primeiro, passos)

        val os = ordemRepository.save(
            OrdemServico(numeroOsErp = (proximoNumero++).toString(), cliente = cliente, criadoPor = vendedor, criadoEm = aberta)
        )
        val fluxo = fluxoRepository.save(
            FluxoOs(ordemServico = os, identificadorFluxo = identificador, setorAtual = setores.getValue(primeiro), entrouNoSetorEm = aberta)
        )
        evento(fluxo, null, primeiro, TipoEvento.CRIACAO, vendedor, aberta, "Fluxo aberto pelo comercial. $MARCA")

        for (passo in passos) {
            if (passo.quando.isAfter(agora)) break
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
        fluxoRepository.save(fluxo)
        return os
    }

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

    /** Celulas (dia, faixa) da grade que um carro ocupa a partir de um dia. */
    private fun celulas(dia: LocalDate, posicoes: List<Int>) = posicoes.map {
        FaixasDoDia.diaUtilAFrente(dia, it / FaixasDoDia.QUANTIDADE) to it % FaixasDoDia.QUANTIDADE
    }

    private fun operador(setor: SetorNome): Usuario = operadores[setor].orEmpty().ifEmpty { listOf(reserva) }.qualquer()

    // ------------------------------------------------------ horario comercial

    private fun hora(texto: String) = LocalTime.parse(texto)

    private fun util(d: LocalDate) = d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY
    private fun diaUtil(d: LocalDate): LocalDate { var x = d; while (!util(x)) x = x.plusDays(1); return x }

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

    /** N dias uteis antes, numa hora cheia do expediente. */
    private fun voltarDiasUteis(de: Instant, dias: Int, horaDoDia: Int): Instant {
        var d = LocalDateTime.ofInstant(de, zona).toLocalDate()
        var restantes = dias
        while (restantes > 0) { d = d.minusDays(1); if (util(d)) restantes-- }
        return d.atTime(horaDoDia, sorte.nextInt(0, 60)).atZone(zona).toInstant()
    }

    companion object {
        const val MARCA = "[ficticio]"
        const val MARCA_AGENDA = "Lancamento ficticio para teste $MARCA"
        private val INICIO: LocalTime = LocalTime.of(7, 30)
        private val FIM: LocalTime = LocalTime.of(17, 30)
    }
}
