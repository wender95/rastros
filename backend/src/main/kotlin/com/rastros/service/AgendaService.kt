package com.rastros.service

import com.rastros.api.*
import com.rastros.domain.*
import com.rastros.repository.*
import com.rastros.security.UsuarioAutenticado
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/** Quantos cards cabem numa acao de varios de uma vez. */
private const val MAXIMO_POR_LOTE = 200

@Service
class AgendaService(
    private val adesivadorRepository: AdesivadorRepository,
    private val agendamentoRepository: AgendamentoRepository,
    private val ordemRepository: OrdemServicoRepository,
    private val usuarioRepository: UsuarioRepository,
    private val historico: HistoricoAgendaService,
    private val agendaDaFrota: AgendaDaFrotaService,
    private val fluxoService: FluxoService,
    private val configuracao: ConfiguracaoAgendaService,
    private val pausas: PausasDosProjetos,
    private val retratos: DesfazerAgenda,
    private val reempilhamento: ReempilhamentoAgenda,
    private val resposta: AgendaParaResposta
) {

    // --------------------------------------------------------------- desfazer

    /** Volta a agenda ao estado anterior a ultima alteracao deste usuario. */
    @Transactional
    fun desfazer(autor: UsuarioAutenticado): String {
        garantirPodeEditar(autor)
        return retratos.desfazer(autor)
    }

    // ---------------------------------------------------------------- consulta

    @Transactional(readOnly = true)
    fun colunas(): List<AdesivadorResponse> =
        adesivadorRepository.findAllByOrderByOrdemAsc().map { it.paraResponse() }

    /** Bloco semanal (segunda a sexta) no mesmo formato do cronograma. */
    @Transactional(readOnly = true)
    fun semana(dataReferencia: LocalDate, autor: UsuarioAutenticado? = null): SemanaAgendaResponse {
        // A semana e cortada na virada do mes, como as abas da planilha: 28/09 a 02/10 vira
        // 28-30/09 em setembro e 01-02/10 em outubro.
        val (segunda, sexta) = FaixasDoDia.semanaNoMes(dataReferencia)
        val diasDoPeriodo = generateSequence(segunda) { it.plusDays(1) }.takeWhile { !it.isAfter(sexta) }
            .filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }.toList()

        val agendamentos = agendamentoRepository.doPeriodo(segunda, sexta)
        // Servicos que comecaram antes e ainda ocupam o comeco desta semana: sem eles, a
        // segunda de manha parece livre e soltar um carro ali "nao obedece".
        val continuacoes = agendamentoRepository
            .doPeriodo(segunda.minusWeeks(SEMANAS_ANTERIORES_CONTINUACAO), segunda.minusDays(1))
            .filter { !it.ultimoDia.isBefore(segunda) }
        // Um adesivador removido some da agenda, mas nao das semanas em que trabalhou.
        val comCarro = (agendamentos + continuacoes).mapNotNull { it.adesivador.id }.toSet()
        val colunas = adesivadorRepository.findAllByOrderByOrdemAsc().filter { it.ativo || it.id in comCarro }

        val todasAsRespostas = (agendamentos + continuacoes).paraResponses()
        val respostas = todasAsRespostas.take(agendamentos.size)
        val dias = diasDoPeriodo.map { dia ->
            DiaAgendaResponse(
                data = dia,
                diaSemana = AgendaParaResposta.nomeDoDia(dia.dayOfWeek),
                agendamentos = respostas.filter { it.data == dia }
            )
        }

        // A carga conta so as horas que caem nos dias mostrados - um caminhao que comeca na
        // quinta leva para esta semana so o que ocupa de quinta e sexta, e o que veio da
        // semana anterior tambem entra. Assim ela nunca passa da capacidade dos dias.
        val diasMostrados = diasDoPeriodo.toSet()
        fun horasNoPeriodo(a: Agendamento) = a.posicoesOcupadas
            .filter { FaixasDoDia.diaUtilAFrente(a.data, it / FaixasDoDia.QUANTIDADE) in diasMostrados }
            .fold(BigDecimal.ZERO) { soma, p -> soma + FaixasDoDia.de(FaixasDoDia.faixaDaPosicao(p)).horas }

        fun somaPorColuna(filtro: (Agendamento) -> Boolean) = colunas.map { coluna ->
            val total = (agendamentos + continuacoes)
                .filter { it.adesivador.id == coluna.id && filtro(it) }
                .fold(BigDecimal.ZERO) { acumulado, a -> acumulado + horasNoPeriodo(a) }
            TotalPorColunaResponse(coluna.id!!, total)
        }

        return SemanaAgendaResponse(
            inicio = segunda,
            fim = sexta,
            colunas = colunas.map { it.paraResponse() },
            dias = dias,
            continuacoes = todasAsRespostas.drop(agendamentos.size),
            faixas = FaixasDoDia.TODAS.map {
                FaixaHorariaResponse(it.indice, it.inicio, it.fim, it.rotulo, it.horas, it.almoco)
            },
            horasUteisDoDia = FaixasDoDia.HORAS_UTEIS_DO_DIA,
            horasUteisDaSemana = diasDoPeriodo.fold(BigDecimal.ZERO) { soma, dia -> soma + FaixasDoDia.horasUteisNoDia(dia) },
            horasOcupadas = somaPorColuna { it.ehServico },
            horasIndisponiveis = somaPorColuna { !it.ehServico },
            ultimaAcao = autor?.let { historico.ultima(it.id) }
        )
    }

    @Transactional(readOnly = true)
    fun porOrdemServico(osId: Int): List<AgendamentoResponse> =
        agendamentoRepository.findByOrdemServicoIdOrderByDataAsc(osId).paraResponses()

    @Transactional(readOnly = true)
    fun buscar(termo: String, somenteSemOs: Boolean): List<AgendamentoResponse> =
        agendamentoRepository.buscar(termo.trim(), somenteSemOs).take(100).paraResponses()

    // ------------------------------------------------------------------ escrita

    @Transactional
    fun criar(req: NovoAgendamentoRequest, autor: UsuarioAutenticado): AgendamentoResponse {
        garantirPodeEditar(autor)
        val adesivador = carregarAdesivador(req.adesivadorId!!)
        val faixa = validarFaixaInicial(req.slotInicio!!, req.data!!)

        retratos.registrar(autor, "incluir \"${req.descricao.trim()}\"", listOf(adesivador), req.data!!)

        val bloqueio = req.tipo == TipoAgendamento.INDISPONIVEL
        val agendamento = Agendamento(
            data = req.data!!,
            adesivador = adesivador,
            tipo = req.tipo,
            slotInicio = faixa,
            horasEstimadas = req.horasEstimadas!!,
            descricao = req.descricao.trim(),
            vendedorCodigo = if (bloqueio) null else normalizarCodigo(req.vendedorCodigo),
            status = if (req.etiquetaId != null) StatusAgendamento.PROGRAMADO else req.status,
            etiquetaId = if (bloqueio) null else req.etiquetaId?.let { configuracao.etiqueta(it).id },
            // O score nao se lanca aqui: ele e atribuido no relatorio semanal, olhando o
            // servico entregue. Na agenda ele seria um palpite antes do trabalho existir.
            score = null,
            ordemServico = if (bloqueio) null else req.osId?.let { carregarOrdem(it) },
            observacao = req.observacao?.trim()?.takeIf { it.isNotEmpty() },
            criadoPor = usuarioRepository.findById(autor.id).orElse(null)
        )
        val salvo = agendamentoRepository.save(agendamento)
        agendaDaFrota.ajustarAoVincular(salvo)
        salvo.ordemServico?.let { fluxoService.acompanharProjetos(it) }
        reempilhamento.reempilhar(salvo.data, adesivador, setOf(salvo.id!!))
        return carregar(salvo.id!!).paraResponse()
    }

    @Transactional
    fun atualizar(id: Long, req: NovoAgendamentoRequest, autor: UsuarioAutenticado): AgendamentoResponse {
        garantirPodeEditar(autor)
        val agendamento = carregar(id)

        retratos.registrar(
            autor, "editar \"${agendamento.descricao}\"",
            listOf(agendamento.adesivador, carregarAdesivador(req.adesivadorId!!)),
            minOf(agendamento.data, req.data!!)
        )

        // So o que decide o lugar na grade pede reempilhar. Salvar descricao, OS ou status
        // nao pode empurrar ninguem.
        val novoAdesivador = carregarAdesivador(req.adesivadorId!!)
        val mudouDeLugar = agendamento.data != req.data ||
            agendamento.adesivador.id != novoAdesivador.id ||
            agendamento.slotInicio != req.slotInicio ||
            agendamento.horas.compareTo(req.horasEstimadas!!) != 0 ||
            (agendamento.tipo ?: TipoAgendamento.SERVICO) != req.tipo

        val bloqueio = req.tipo == TipoAgendamento.INDISPONIVEL
        agendamento.data = req.data!!
        agendamento.adesivador = novoAdesivador
        agendamento.tipo = req.tipo
        agendamento.slotInicio = validarFaixaInicial(req.slotInicio!!, req.data!!)
        agendamento.horasEstimadas = req.horasEstimadas!!
        agendamento.descricao = req.descricao.trim()
        agendamento.vendedorCodigo = if (bloqueio) null else normalizarCodigo(req.vendedorCodigo)
        agendamento.status = if (req.etiquetaId != null && !bloqueio) StatusAgendamento.PROGRAMADO else req.status
        agendamento.etiquetaId = if (bloqueio) null else req.etiquetaId?.let { configuracao.etiqueta(it).id }
        // O score vem do relatorio semanal; editar o carro na agenda nao o toca, e virar
        // bloqueio o apaga junto com o resto do que so faz sentido num servico.
        if (bloqueio) agendamento.score = null
        agendamento.ordemServico = if (bloqueio) null else req.osId?.let { carregarOrdem(it) }
        agendamento.observacao = req.observacao?.trim()?.takeIf { it.isNotEmpty() }
        agendamento.atualizadoEm = Instant.now()
        agendamentoRepository.save(agendamento)
        agendaDaFrota.ajustarAoVincular(agendamento)
        agendamento.ordemServico?.let { fluxoService.acompanharProjetos(it) }
        espalharNasPartes(agendamento)

        if (mudouDeLugar) reempilhamento.reempilhar(agendamento.data, agendamento.adesivador, setOf(id))
        return carregar(id).paraResponse()
    }

    /**
     * Continua o mesmo servico noutro lugar da grade: uma parte a mais, nao uma copia.
     *
     * A planilha antiga escrevia o nome do carro em duas semanas quando o servico virava a
     * semana; aqui as duas partes continuam sendo **um servico so**: nome, vendedor, OS,
     * observacao e estado sao os mesmos e andam juntos. Cada parte tem apenas o seu lugar
     * e o seu tamanho.
     */
    @Transactional
    fun dividirEmParte(id: Long, req: NovaParteRequest, autor: UsuarioAutenticado): AgendamentoResponse {
        garantirPodeEditar(autor)
        val origem = carregar(id)
        val adesivador = carregarAdesivador(req.adesivadorId!!)
        val faixa = validarFaixaInicial(req.slotInicio!!, req.data!!)

        retratos.registrar(
            autor, "continuar \"${origem.descricao}\"", listOf(adesivador), req.data!!
        )

        // A chave do conjunto e a parte mais antiga: a primeira divisao a grava nas duas.
        val grupo = origem.grupoId ?: id
        if (origem.grupoId == null) {
            origem.grupoId = grupo
            agendamentoRepository.save(origem)
        }

        val salvo = agendamentoRepository.save(
            novaParte(origem, grupo, adesivador, req.data!!, faixa, req.horasEstimadas!!, autor)
        )
        reempilhamento.reempilhar(salvo.data, adesivador, setOf(salvo.id!!))
        return carregar(salvo.id!!).paraResponse()
    }

    /** Uma parte nova do mesmo servico: o que e do servico vem da origem; o lugar, de fora. */
    private fun novaParte(
        origem: Agendamento, grupo: Long?, adesivador: Adesivador, dia: LocalDate, faixa: Int,
        horas: BigDecimal, autor: UsuarioAutenticado
    ) = Agendamento(
        data = dia,
        adesivador = adesivador,
        tipo = origem.tipo,
        slotInicio = faixa,
        horasEstimadas = horas,
        descricao = origem.descricao,
        vendedorCodigo = origem.vendedorCodigo,
        status = origem.status,
        etiquetaId = origem.etiquetaId,
        atribuidos = origem.atribuidos.toMutableSet(),
        score = null,
        ordemServico = origem.ordemServico,
        observacao = origem.observacao,
        grupoId = grupo,
        // Estender na agenda um servico ja iniciado (ex.: continua na terca) nao o "desinicia":
        // o que o adesivador registrou vale para a copia nova tambem.
        iniciadoEm = origem.iniciadoEm,
        iniciadoPor = origem.iniciadoPor,
        inicioRegistradoPor = origem.inicioRegistradoPor,
        concluidoEm = origem.concluidoEm,
        conclusaoRegistradaPor = origem.conclusaoRegistradaPor,
        criadoPor = usuarioRepository.findById(autor.id).orElse(null)
    )

    /**
     * A alca do canto na agenda: em vez de esticar o card, **replica** o servico em cada
     * espaco de destino. Cada copia e uma parte do mesmo servico - nome, OS, vendedor e
     * estado andam juntos -, e um Ctrl+Z desfaz todas as copias de uma vez.
     *
     * Os espacos precisam estar livres: se algum ja estiver ocupado, nenhuma copia e feita.
     */
    @Transactional
    fun replicar(id: Long, destinos: List<NovaParteRequest>, autor: UsuarioAutenticado): Int {
        garantirPodeEditar(autor)
        if (destinos.isEmpty()) return 0
        if (destinos.size > MAXIMO_POR_LOTE) throw RegraDeNegocioException("No maximo ${MAXIMO_POR_LOTE} copias de uma vez.")
        val origem = carregar(id)

        // Primeiro confere todos os espacos; so depois cria.
        val alvos = conferirEspacosLivres(destinos, "Nenhuma copia foi feita.")
        registrarRetrato(autor, "replicar \"${origem.descricao}\"", alvos)

        // A chave do conjunto e a parte mais antiga: a primeira copia a grava na origem.
        // Um bloqueio (INDISPONIVEL) se repete como blocos soltos: cada linha e independente.
        val grupo = if (origem.ehServico) origem.grupoId ?: id else null
        if (grupo != null && origem.grupoId == null) {
            origem.grupoId = grupo
            agendamentoRepository.save(origem)
        }
        alvos.forEach { (adesivador, faixa, d) ->
            agendamentoRepository.save(novaParte(origem, grupo, adesivador, d.data!!, faixa, d.horasEstimadas!!, autor))
        }
        return alvos.size
    }

    /**
     * O "N" da agenda sobre varios espacos escolhidos com o mouse: cada espaco vira um
     * bloco INDISPONIVEL de um espaco so, como os outros itens repetidos linha a linha.
     * Um Ctrl+Z desfaz todos.
     */
    @Transactional
    fun marcarIndisponivel(lugares: List<NovaParteRequest>, autor: UsuarioAutenticado): Int {
        garantirPodeEditar(autor)
        if (lugares.isEmpty()) return 0
        if (lugares.size > MAXIMO_POR_LOTE) throw RegraDeNegocioException("No maximo ${MAXIMO_POR_LOTE} espacos de uma vez.")
        val alvos = conferirEspacosLivres(lugares, "Nada foi marcado.")
        registrarRetrato(autor, "marcar indisponivel", alvos)
        val quem = usuarioRepository.findById(autor.id).orElse(null)
        alvos.forEach { (adesivador, faixa, d) ->
            agendamentoRepository.save(
                Agendamento(
                    data = d.data!!, adesivador = adesivador, tipo = TipoAgendamento.INDISPONIVEL, slotInicio = faixa,
                    horasEstimadas = d.horasEstimadas!!, descricao = "INDISPONÍVEL", criadoPor = quem
                )
            )
        }
        return alvos.size
    }

    /** Cada espaco pedido tem de estar livre; se um nao estiver, nada e feito. */
    private fun conferirEspacosLivres(destinos: List<NovaParteRequest>, senao: String) = destinos.map { d ->
        val adesivador = carregarAdesivador(d.adesivadorId!!)
        val faixa = validarFaixaInicial(d.slotInicio!!, d.data!!)
        val celulas = FaixasDoDia.posicoesOcupadas(d.data, faixa, d.horasEstimadas!!).map { celula(d.data, it) }.toSet()
        val noCaminho = agendamentoRepository
            .doPeriodoDoAdesivador(d.data.minusWeeks(SEMANAS_ANTERIORES_CONTINUACAO), celulas.maxOf { it.first }, adesivador.id!!)
            .firstOrNull { outro -> outro.posicoesOcupadas.any { celula(outro.data, it) in celulas } }
        if (noCaminho != null) {
            throw RegraDeNegocioException("\"${noCaminho.descricao}\" ja ocupa um desses espacos. $senao")
        }
        Triple(adesivador, faixa, d)
    }

    /** Um retrato so para o lote inteiro: um Ctrl+Z desfaz tudo. */
    private fun registrarRetrato(autor: UsuarioAutenticado, acao: String, alvos: List<Triple<Adesivador, Int, NovaParteRequest>>) {
        val primeiro = alvos.minOf { it.third.data!! }
        val ultimo = alvos.maxOf { it.third.data!! }
        retratos.registrar(
            autor, acao, alvos.map { it.first }.distinctBy { it.id }, primeiro,
            DiasUteisDaAgenda.entre(primeiro, ultimo) + 20
        )
    }

    /** As outras partes do mesmo servico (vazio quando ele tem uma parte so). */
    private fun outrasPartes(agendamento: Agendamento): List<Agendamento> {
        val grupo = agendamento.grupoId ?: return emptyList()
        return agendamentoRepository.findByGrupoIdOrderByDataAscSlotInicioAsc(grupo)
            .filter { it.id != agendamento.id }
    }

    /**
     * Passa para as outras partes o que e do servico inteiro. Lugar, tamanho e score sao
     * de cada parte e nao se copiam.
     */
    private fun espalharNasPartes(agendamento: Agendamento) {
        val outras = outrasPartes(agendamento)
        if (outras.isEmpty()) return
        val agora = Instant.now()
        outras.forEach { parte ->
            parte.descricao = agendamento.descricao
            parte.vendedorCodigo = agendamento.vendedorCodigo
            parte.status = agendamento.status
            parte.etiquetaId = agendamento.etiquetaId
            parte.atribuidos = agendamento.atribuidos.toMutableSet()
            parte.ordemServico = agendamento.ordemServico
            parte.observacao = agendamento.observacao
            parte.tipo = agendamento.tipo
            parte.atualizadoEm = agora
        }
        agendamentoRepository.saveAll(outras)
    }

    /** Redimensionar arrastando a borda de baixo do card. */
    @Transactional
    fun alterarHoras(id: Long, horas: BigDecimal, autor: UsuarioAutenticado): SemanaAgendaResponse {
        garantirPodeEditar(autor)
        val agendamento = carregar(id)
        if (horas < BigDecimal("0.5")) {
            throw RegraDeNegocioException("O servico precisa de ao menos meia hora.")
        }
        if (agendamento.horas.compareTo(horas) == 0) return semana(agendamento.data, autor)
        retratos.registrar(
            autor, "mudar as horas de \"${agendamento.descricao}\"",
            listOf(agendamento.adesivador), agendamento.data
        )

        agendamento.horasEstimadas = horas
        agendamento.atualizadoEm = Instant.now()
        agendamentoRepository.save(agendamento)

        reempilhamento.reempilhar(agendamento.data, agendamento.adesivador, setOf(id))
        return semana(agendamento.data, autor)
    }

    /** Dia e faixa (0 a 8) da grade que uma posicao da regua de um servico ocupa. */
    private fun celula(inicio: LocalDate, posicao: Int) =
        FaixasDoDia.diaUtilAFrente(inicio, posicao / FaixasDoDia.QUANTIDADE) to posicao % FaixasDoDia.QUANTIDADE

    /**
     * Move um agendamento arrastado na grade.
     *
     * Se a faixa de destino ja estiver ocupada, os dois **trocam de lugar** - nada e
     * apagado. Em seguida o dia e reempilhado: quem nao couber desce, e se passar do fim
     * do dia continua no dia util seguinte.
     */
    @Transactional
    fun mover(id: Long, req: MoverAgendamentoRequest, autor: UsuarioAutenticado): SemanaAgendaResponse {
        garantirPodeEditar(autor)

        val arrastado = carregar(id)
        val destinoAdesivador = carregarAdesivador(req.adesivadorId!!)
        val destinoData = req.data!!
        val destinoSlot = validarFaixaInicial(req.slotInicio!!, destinoData)

        val origemData = arrastado.data
        val origemAdesivador = arrastado.adesivador
        val origemSlot = arrastado.slotInicio

        val mesmaFaixa = origemData == destinoData &&
            origemAdesivador.id == destinoAdesivador.id &&
            origemSlot == destinoSlot
        if (mesmaFaixa) return semana(destinoData, autor)

        retratos.registrar(
            autor, "mover \"${arrastado.descricao}\"",
            listOf(origemAdesivador, destinoAdesivador),
            minOf(origemData, destinoData)
        )

        // Quem esta na faixa de destino? Soltar no meio de um servico longo pega o servico
        // inteiro; as posicoes pulam o almoco, entao a conta nao e um intervalo continuo.
        val ocupante = agendamentoRepository
            .findByDataAndAdesivadorIdOrderBySlotInicioAsc(destinoData, destinoAdesivador.id!!)
            .firstOrNull { it.id != arrastado.id && (destinoSlot - 1) in it.posicoesOcupadas }

        // Cada card leva consigo a quantidade de horarios que tem na grade: as faixas nao tem
        // todas o mesmo tamanho (a das 07:30 e a das 13:30 tem 1h30), entao manter as horas
        // faria um card de 3 horarios virar 4 em outro ponto do dia. As horas se ajustam.
        val faixasDoArrastado = arrastado.posicoesOcupadas.size

        // Quando a tela manda o tamanho, ele vale no lugar novo - e o caso da agenda de
        // espacos de trabalho, onde o que se conserva e a quantidade de espacos.
        val horasPedidas = mapOf(
            arrastado.id to req.horasEstimadas,
            ocupante?.id to req.horasDoOcupante,
        ).filterValues { it != null }.mapValues { it.value!! }

        if (ocupante == null) {
            colocar(arrastado, destinoAdesivador, destinoData, destinoSlot, faixasDoArrastado, horas = horasPedidas[arrastado.id])
        } else if (origemAdesivador.id == destinoAdesivador.id) {
            trocarNaMesmaColuna(arrastado, ocupante, horasPedidas)
        } else {
            val faixasDoOcupante = ocupante.posicoesOcupadas.size
            val slotDoOcupante = ocupante.slotInicio
            colocar(arrastado, destinoAdesivador, destinoData, slotDoOcupante, faixasDoArrastado, horas = horasPedidas[arrastado.id])
            colocar(ocupante, origemAdesivador, origemData, origemSlot, faixasDoOcupante, horas = horasPedidas[ocupante.id])
        }

        val editados = setOfNotNull(arrastado.id, ocupante?.id)
        if (origemAdesivador.id == destinoAdesivador.id) {
            // Mesma coluna: a janela comeca no mais cedo dos dois lugares, senao quem
            // voltou para a origem ficaria fora da conta.
            reempilhamento.reempilhar(minOf(origemData, destinoData), destinoAdesivador, editados)
        } else {
            reempilhamento.reempilhar(destinoData, destinoAdesivador, editados)
            reempilhamento.reempilhar(origemData, origemAdesivador, editados)
        }

        return semana(destinoData, autor)
    }

    /**
     * Troca na mesma coluna: os dois trocam de ordem e continuam encostados como estavam.
     * Quem vinha depois passa a comecar onde o primeiro comecava; o outro vem logo em
     * seguida, com o mesmo espaco que havia entre eles. O par termina onde terminava,
     * entao ninguem abaixo e empurrado.
     */
    private fun trocarNaMesmaColuna(um: Agendamento, outro: Agendamento, horasPedidas: Map<Long?, BigDecimal> = emptyMap()) {
        val base = minOf(um.data, outro.data)
        fun inicio(a: Agendamento) = DiasUteisDaAgenda.entre(base, a.data) * FaixasDoDia.QUANTIDADE + a.slotInicio - 1
        fun fim(a: Agendamento) = DiasUteisDaAgenda.entre(base, a.data) * FaixasDoDia.QUANTIDADE + a.posicoesOcupadas.last()

        val (primeiro, segundo) = if (inicio(um) <= inicio(outro)) um to outro else outro to um
        val faixasPrimeiro = primeiro.posicoesOcupadas.size
        val faixasSegundo = segundo.posicoesOcupadas.size
        // Horarios de trabalho vagos entre os dois; o almoco no meio nao e espaco.
        val espaco = ((fim(primeiro) + 1) until inicio(segundo)).count { !FaixasDoDia.bloqueada(base, it) }

        val novoInicioSegundo = FaixasDoDia.livreAPartirDe(base, inicio(primeiro))
        // Onde o de baixo passa a terminar: pelas horas que a tela pediu, se ela pediu.
        val fimSegundo = horasPedidas[segundo.id]
            ?.let { FaixasDoDia.posicoesAPartirDe(base, novoInicioSegundo, it).last() }
            ?: posicoesDeFaixas(base, novoInicioSegundo, faixasSegundo).last()
        var novoInicioPrimeiro = fimSegundo + 1
        repeat(espaco) { novoInicioPrimeiro = FaixasDoDia.livreAPartirDe(base, novoInicioPrimeiro) + 1 }
        novoInicioPrimeiro = FaixasDoDia.livreAPartirDe(base, novoInicioPrimeiro)

        val adesivador = primeiro.adesivador
        colocar(segundo, adesivador, base, novoInicioSegundo, faixasSegundo, posicaoAbsoluta = true, horas = horasPedidas[segundo.id])
        colocar(primeiro, adesivador, base, novoInicioPrimeiro, faixasPrimeiro, posicaoAbsoluta = true, horas = horasPedidas[primeiro.id])
    }

    /**
     * Poe o servico num lugar com a mesma quantidade de faixas que ele tinha. Com
     * `posicaoAbsoluta`, `slotOuPosicao` e a posicao na regua a partir de `dia`.
     */
    private fun colocar(
        item: Agendamento, adesivador: Adesivador, dia: LocalDate, slotOuPosicao: Int, faixas: Int,
        posicaoAbsoluta: Boolean = false, horas: BigDecimal? = null
    ) {
        val posicao = if (posicaoAbsoluta) slotOuPosicao else slotOuPosicao - 1
        item.data = FaixasDoDia.diaUtilAFrente(dia, posicao / FaixasDoDia.QUANTIDADE)
        item.slotInicio = posicao % FaixasDoDia.QUANTIDADE + 1
        item.adesivador = adesivador
        item.horasEstimadas = horas
            ?: FaixasDoDia.horasDasPosicoes(posicoesDeFaixas(item.data, item.slotInicio - 1, faixas))
        item.atualizadoEm = Instant.now()
        agendamentoRepository.save(item)
    }

    /** As N primeiras posicoes de trabalho a partir de uma posicao (almoco e sexta 17h nao contam). */
    private fun posicoesDeFaixas(base: LocalDate, inicio: Int, quantidade: Int): List<Int> =
        generateSequence(inicio) { it + 1 }.filterNot { FaixasDoDia.bloqueada(base, it) }.take(maxOf(1, quantidade)).toList()

    /**
     * Coluna Noturno: quem vai fazer o servico a noite. Vale para todas as partes do servico;
     * lista vazia tira todo mundo.
     */
    @Transactional
    fun atribuir(id: Long, adesivadorIds: List<Int>, autor: UsuarioAutenticado): AgendamentoResponse {
        val agendamento = carregar(id)
        garantirPodeEditar(autor)
        if (agendamento.adesivador.tipo != TipoColunaAgenda.NOTURNO) {
            throw RegraDeNegocioException("So os servicos da coluna Noturno recebem adesivadores atribuidos.")
        }
        if (!agendamento.ehServico) throw RegraDeNegocioException("Um bloqueio nao recebe adesivadores.")
        val escolhidos = adesivadorRepository.findAllById(adesivadorIds.distinct())
        if (escolhidos.size != adesivadorIds.distinct().size || escolhidos.any { it.tipo != TipoColunaAgenda.ADESIVADOR }) {
            throw RegraDeNegocioException("Escolha so adesivadores da agenda.")
        }
        retratos.registrar(
            autor, "atribuir adesivadores a \"${agendamento.descricao}\"",
            listOf(agendamento.adesivador), agendamento.data
        )
        agendamento.atribuidos = escolhidos.toMutableSet()
        agendamento.atualizadoEm = Instant.now()
        agendamentoRepository.save(agendamento)
        espalharNasPartes(agendamento)
        return carregar(id).paraResponse()
    }

    @Transactional
    fun alterarStatus(id: Long, novo: StatusAgendamento, autor: UsuarioAutenticado, etiquetaId: Long? = null): AgendamentoResponse {
        val agendamento = carregar(id)
        garantirPodeEditar(autor)
        retratos.registrar(
            autor, "mudar o status de \"${agendamento.descricao}\"",
            listOf(agendamento.adesivador), agendamento.data
        )

        return aplicarStatus(agendamento, novo, etiquetaId?.let { configuracao.etiqueta(it).id }).paraResponse()
    }

    /**
     * Marcar o andamento e o que registra a entrada e a saida reais do servico: com os dois
     * carimbos, a produtividade deixa de ser estimativa. O estado vale para todas as partes.
     *
     * Um status criado na agenda ([etiqueta]) e de servico que ainda nao comecou: o card
     * fica Programado, com o nome e a cor dele.
     */
    private fun aplicarStatus(agendamento: Agendamento, pedido: StatusAgendamento, etiqueta: Long? = null): Agendamento {
        val agora = Instant.now()
        val novo = if (etiqueta != null) StatusAgendamento.PROGRAMADO else pedido
        agendamento.etiquetaId = etiqueta
        // Mudar o status pela agenda nao grava horario: inicio, pausa e conclusao so contam
        // quando o adesivador registra na agenda dele (ou alguem com permissao, pelo painel).
        agendamento.status = novo
        agendamento.atualizadoEm = agora
        // Saiu de "executando" (concluiu, voltou para programado...): a pausa aberta termina aqui.
        if (novo != StatusAgendamento.EXECUTANDO) pausas.fecharAbertas(agendamento, agora)
        val salvo = agendamentoRepository.save(agendamento)
        espalharNasPartes(salvo)
        return salvo
    }

    // ------------------------------------------------------ varios de uma vez

    /**
     * Guarda, num retrato so, a agenda de todos os cards escolhidos: um Ctrl+Z desfaz o lote
     * inteiro. A janela vai do primeiro dia escolhido ate 20 dias uteis depois do ultimo.
     */
    private fun registrarLote(autor: UsuarioAutenticado, descricao: String, itens: List<Agendamento>) {
        val primeiro = itens.minOf { it.data }
        val ultimo = itens.maxOf { it.ultimoDia }
        retratos.registrar(
            autor, descricao, itens.map { it.adesivador }.distinctBy { it.id }, primeiro,
            DiasUteisDaAgenda.entre(primeiro, ultimo) + 20
        )
    }

    private fun carregarVarios(ids: List<Long>): List<Agendamento> {
        if (ids.isEmpty()) throw RegraDeNegocioException("Escolha ao menos um card.")
        if (ids.size > MAXIMO_POR_LOTE) throw RegraDeNegocioException("Escolha no maximo $MAXIMO_POR_LOTE cards de uma vez.")
        return agendamentoRepository.findAllById(ids.distinct()).also {
            if (it.isEmpty()) throw NaoEncontradoException("Nenhum dos cards escolhidos existe mais.")
        }
    }

    /** Muda o estado de varios cards de uma vez (selecao com Shift). Bloqueio fica como esta. */
    @Transactional
    fun alterarStatusDeVarios(ids: List<Long>, pedido: StatusAgendamento, autor: UsuarioAutenticado, etiquetaId: Long? = null): Int {
        garantirPodeEditar(autor)
        val etiqueta = etiquetaId?.let { configuracao.etiqueta(it).id }
        val novo = if (etiqueta != null) StatusAgendamento.PROGRAMADO else pedido
        val itens = carregarVarios(ids).filter { it.ehServico && (it.status != novo || it.etiquetaId != etiqueta) }
        if (itens.isEmpty()) return 0
        registrarLote(autor, "mudar o status de ${itens.size} cards", itens)
        itens.forEach { aplicarStatus(it, novo, etiqueta) }
        return itens.size
    }

    /**
     * Arrasta varios cards juntos (selecao com Shift): cada um vai para o lugar que a tela
     * calculou, mantendo a posicao de um em relacao ao outro.
     *
     * Diferente do mover de um card so, aqui ninguem troca de lugar: se algum card do grupo
     * cair em cima de um card de fora, **nada e movido** e a mensagem diz quem esta no
     * caminho. Um Ctrl+Z desfaz o grupo inteiro.
     */
    @Transactional
    fun moverVarios(destinos: List<com.rastros.api.DestinoNoLote>, autor: UsuarioAutenticado): Int {
        garantirPodeEditar(autor)
        val itens = carregarVarios(destinos.mapNotNull { it.id })
        val porId = itens.associateBy { it.id!! }
        val alvos = destinos.map { d ->
            val item = porId[d.id!!] ?: throw NaoEncontradoException("Card ${d.id} nao existe mais.")
            val dia = d.data!!
            if (dia.dayOfWeek == DayOfWeek.SATURDAY || dia.dayOfWeek == DayOfWeek.SUNDAY) {
                throw RegraDeNegocioException("A agenda nao tem sabado nem domingo.")
            }
            Triple(item, carregarAdesivador(d.adesivadorId!!), d)
        }

        // Primeiro confere, depois mexe: se alguem de fora estiver no caminho, nada muda.
        val movidos = itens.mapNotNull { it.id }.toSet()
        val ocupadasPeloGrupo = mutableMapOf<Pair<Int, Pair<LocalDate, Int>>, Agendamento>()
        alvos.forEach { (item, adesivador, d) ->
            val slot = validarFaixaInicial(d.slotInicio!!, d.data!!)
            val celulas = FaixasDoDia.posicoesOcupadas(d.data, slot, d.horasEstimadas!!).map { celula(d.data, it) }
            celulas.forEach { c ->
                val outro = ocupadasPeloGrupo.put(adesivador.id!! to c, item)
                if (outro != null && outro.id != item.id) {
                    throw RegraDeNegocioException(
                        "\"${outro.descricao}\" e \"${item.descricao}\" cairiam no mesmo lugar. Nada foi movido."
                    )
                }
            }
            val fim = celulas.maxOf { it.first }
            val noCaminho = agendamentoRepository
                .doPeriodoDoAdesivador(d.data.minusWeeks(SEMANAS_ANTERIORES_CONTINUACAO), fim, adesivador.id!!)
                .filter { it.id !in movidos }
                .firstOrNull { outro -> outro.posicoesOcupadas.any { celula(outro.data, it) in celulas } }
            if (noCaminho != null) {
                throw RegraDeNegocioException(
                    "\"${noCaminho.descricao}\" esta no caminho de \"${item.descricao}\". Nada foi movido."
                )
            }
        }

        // Um retrato so, cobrindo de onde saem e para onde vao.
        val colunas = (itens.map { it.adesivador } + alvos.map { it.second }).distinctBy { it.id }
        val primeiro = (itens.map { it.data } + alvos.map { it.third.data!! }).min()
        val ultimo = (itens.map { it.ultimoDia } + alvos.map { it.third.data!! }).max()
        retratos.registrar(
            autor, "mover ${itens.size} cards", colunas, primeiro, DiasUteisDaAgenda.entre(primeiro, ultimo) + 20
        )

        alvos.forEach { (item, adesivador, d) ->
            item.data = d.data!!
            item.adesivador = adesivador
            item.slotInicio = d.slotInicio!!
            item.horasEstimadas = d.horasEstimadas
            item.atualizadoEm = Instant.now()
        }
        agendamentoRepository.saveAll(itens)
        return itens.size
    }

    /** Exclui varios cards de uma vez (selecao com Shift). Um Ctrl+Z traz todos de volta. */
    @Transactional
    fun excluirVarios(ids: List<Long>, autor: UsuarioAutenticado): Int {
        garantirPodeEditar(autor)
        val itens = carregarVarios(ids)
        registrarLote(autor, "excluir ${itens.size} cards", itens)
        val apagados = itens.mapNotNull { it.id }.toSet()
        itens.forEach { item ->
            val irmas = outrasPartes(item).filter { it.id !in apagados }
            agendamentoRepository.delete(item)
            // Sobrando uma parte so, ela deixa de ser "parte" e volta a ser um servico comum.
            if (irmas.size == 1) {
                irmas.first().grupoId = null
                agendamentoRepository.save(irmas.first())
            }
        }
        return itens.size
    }

    /**
     * Lanca o score de um servico. **Vem do relatorio semanal, nao da agenda.**
     *
     * Na planilha o score era digitado na propria grade porque nao havia outro lugar para
     * escrever; a agenda e o plano do dia, e o score e um julgamento do que foi entregue.
     * Devolve a data do servico, para o chamador remontar a semana certa do relatorio.
     */
    @Transactional
    fun alterarScore(id: Long, score: BigDecimal?, autor: UsuarioAutenticado): LocalDate {
        garantirPodeEditar(autor)
        val agendamento = carregar(id)
        if (!agendamento.ehServico) {
            throw RegraDeNegocioException("Bloqueio de horario nao recebe score.")
        }
        if (score != null && score < BigDecimal.ZERO) {
            throw RegraDeNegocioException("O score nao pode ser negativo.")
        }
        // O score e do servico: vale para todas as copias dele (e conta uma vez so).
        val agora = Instant.now()
        (listOf(agendamento) + outrasPartes(agendamento)).forEach {
            it.score = score
            it.atualizadoEm = agora
        }
        agendamentoRepository.save(agendamento)
        agendamentoRepository.saveAll(outrasPartes(agendamento))
        return agendamento.data
    }

    @Transactional
    fun vincularOs(id: Long, req: VinculoOsRequest, autor: UsuarioAutenticado): AgendamentoResponse {
        garantirPodeEditar(autor)
        val agendamento = carregar(id)
        retratos.registrar(
            autor, "vincular OS em \"${agendamento.descricao}\"",
            listOf(agendamento.adesivador), agendamento.data
        )

        val numero = req.numeroOsErp?.trim()
        agendamento.ordemServico = when {
            req.osId != null -> carregarOrdem(req.osId)
            numero.isNullOrEmpty() -> null
            else -> {
                val encontradas = ordemRepository.findByNumeroOsErpIgnoreCase(numero)
                when {
                    encontradas.isEmpty() ->
                        throw NaoEncontradoException("Nenhuma OS com o numero $numero foi encontrada.")
                    encontradas.size > 1 ->
                        throw RegraDeNegocioException(
                            "Existe mais de uma OS com o numero $numero. Selecione pela lista."
                        )
                    else -> encontradas.first()
                }
            }
        }
        agendamento.atualizadoEm = Instant.now()
        val salvo = agendamentoRepository.save(agendamento)
        agendaDaFrota.ajustarAoVincular(salvo)
        salvo.ordemServico?.let { fluxoService.acompanharProjetos(it) }
        espalharNasPartes(salvo)
        return salvo.paraResponse()
    }

    @Transactional
    fun excluir(id: Long, autor: UsuarioAutenticado) {
        garantirPodeEditar(autor)
        val agendamento = agendamentoRepository.findById(id)
            .orElseThrow { NaoEncontradoException("Agendamento $id nao encontrado.") }
        val data = agendamento.data
        val adesivador = agendamento.adesivador
        retratos.registrar(autor, "excluir \"${agendamento.descricao}\"", listOf(adesivador), data)
        // Nada a reempilhar: tirar um servico abre um buraco, e ninguem sobe para ocupa-lo.
        val irmas = outrasPartes(agendamento)
        agendamentoRepository.delete(agendamento)
        // Sobrando uma parte so, ela deixa de ser "parte" e volta a ser um servico comum.
        if (irmas.size == 1) {
            irmas.first().grupoId = null
            agendamentoRepository.save(irmas.first())
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun podeEditar(autor: UsuarioAutenticado) =
        autor.tem(SecaoDoSistema.AGENDA_EDITAR)

    private fun garantirPodeEditar(autor: UsuarioAutenticado) {
        if (!podeEditar(autor)) {
            throw PermissaoNegadaException("Apenas o comercial, a diretoria ou o administrador editam a agenda.")
        }
    }

    /** O almoco e a sexta depois das 17:00 nao sao horario de trabalho: nao recebem servico nem bloqueio. */
    private fun validarFaixaInicial(slot: Int, dia: LocalDate): Int {
        if (FaixasDoDia.fechadaNoDia(dia, slot)) {
            throw RegraDeNegocioException("Na sexta o expediente vai ate as 17:00: nao ha horario das 17:00 as 18:00.")
        }
        if (slot !in 1..FaixasDoDia.QUANTIDADE) {
            throw RegraDeNegocioException("Faixa de horario $slot nao existe.")
        }
        if (FaixasDoDia.ehAlmoco(slot)) {
            val faixa = FaixasDoDia.de(slot)
            throw RegraDeNegocioException(
                "O horario de almoco (${faixa.rotulo}) nao e um horario disponivel para trabalho."
            )
        }
        return slot
    }

    private fun normalizarCodigo(codigo: String?) =
        codigo?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }

    private fun carregar(id: Long) = agendamentoRepository.findById(id)
        .orElseThrow { NaoEncontradoException("Agendamento $id nao encontrado.") }

    private fun carregarAdesivador(id: Int) = adesivadorRepository.findById(id)
        .orElseThrow { NaoEncontradoException("Adesivador $id nao encontrado.") }

    private fun carregarOrdem(id: Int) = ordemRepository.findById(id)
        .orElseThrow { NaoEncontradoException("Ordem de servico $id nao encontrada.") }

    private fun Adesivador.paraResponse() = resposta.de(this)

    private fun List<Agendamento>.paraResponses() = resposta.de(this)

    private fun Agendamento.paraResponse() = resposta.de(this)

    private companion object {
        /** Ate quantas semanas atras procurar servico longo que ainda chega na semana vista. */
        const val SEMANAS_ANTERIORES_CONTINUACAO = 3L
    }
}
