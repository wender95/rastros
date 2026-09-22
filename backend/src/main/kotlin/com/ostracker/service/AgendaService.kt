package com.ostracker.service

import com.ostracker.api.*
import com.ostracker.domain.*
import com.ostracker.repository.*
import com.ostracker.security.UsuarioAutenticado
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

@Service
class AgendaService(
    private val adesivadorRepository: AdesivadorRepository,
    private val agendamentoRepository: AgendamentoRepository,
    private val ordemRepository: OrdemServicoRepository,
    private val fluxoRepository: FluxoOsRepository,
    private val usuarioRepository: UsuarioRepository,
    private val historico: HistoricoAgendaService
) {

    // --------------------------------------------------------------- desfazer

    /**
     * Guarda como a agenda estava antes de mexer. A janela cobre os dias que o
     * reempilhamento pode remanejar, senao desfazer deixaria os vizinhos deslocados.
     */
    private fun registrarParaDesfazer(
        autor: UsuarioAutenticado,
        descricao: String,
        adesivadores: List<Adesivador>,
        apartirDe: LocalDate
    ) {
        val dias = diasUteisAPartirDe(apartirDe, 20)
        val ids = adesivadores.mapNotNull { it.id }.toSet()
        val linhas = ids.flatMap { id ->
            agendamentoRepository.doPeriodoDoAdesivador(dias.first(), dias.last(), id)
        }.map {
            LinhaSnapshot(
                id = it.id!!,
                data = it.data,
                adesivadorId = it.adesivador.id!!,
                slotInicio = it.slotInicio,
                horas = it.horas,
                tipo = it.tipo ?: TipoAgendamento.SERVICO,
                descricao = it.descricao,
                vendedorCodigo = it.vendedorCodigo,
                status = it.status,
                score = it.score,
                osId = it.ordemServico?.id,
                observacao = it.observacao
            )
        }
        historico.empilhar(autor.id, SnapshotAgenda(descricao, ids, dias.first(), dias.last(), linhas))
    }

    /** Volta a agenda ao estado anterior a ultima alteracao deste usuario. */
    @Transactional
    fun desfazer(autor: UsuarioAutenticado): String {
        garantirPodeEditar(autor)
        val snapshot = historico.desempilhar(autor.id)
            ?: throw RegraDeNegocioException("Nao ha nada para desfazer: o desfazer guarda as suas ultimas 25 alteracoes das ultimas 24 horas.")

        val atuais = snapshot.adesivadorIds.flatMap {
            agendamentoRepository.doPeriodoDoAdesivador(snapshot.inicio, snapshot.fim, it)
        }
        val guardados = snapshot.linhas.associateBy { it.id }

        // O que foi criado depois do retrato sai.
        atuais.filter { it.id !in guardados.keys }.forEach { agendamentoRepository.delete(it) }

        val existentes = atuais.associateBy { it.id }
        snapshot.linhas.forEach { linha ->
            val alvo = existentes[linha.id] ?: Agendamento(
                data = linha.data,
                adesivador = carregarAdesivador(linha.adesivadorId),
                slotInicio = linha.slotInicio,
                descricao = linha.descricao
            )
            alvo.data = linha.data
            alvo.adesivador = carregarAdesivador(linha.adesivadorId)
            alvo.slotInicio = linha.slotInicio
            alvo.horasEstimadas = linha.horas
            alvo.tipo = linha.tipo
            alvo.descricao = linha.descricao
            alvo.vendedorCodigo = linha.vendedorCodigo
            alvo.status = linha.status
            alvo.score = linha.score
            alvo.ordemServico = linha.osId?.let { carregarOrdem(it) }
            alvo.observacao = linha.observacao
            alvo.atualizadoEm = Instant.now()
            agendamentoRepository.save(alvo)
        }
        return snapshot.descricao
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
                diaSemana = nomeDoDia(dia.dayOfWeek),
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

        registrarParaDesfazer(autor, "incluir \"${req.descricao.trim()}\"", listOf(adesivador), req.data!!)

        val bloqueio = req.tipo == TipoAgendamento.INDISPONIVEL
        val agendamento = Agendamento(
            data = req.data!!,
            adesivador = adesivador,
            tipo = req.tipo,
            slotInicio = faixa,
            horasEstimadas = req.horasEstimadas!!,
            descricao = req.descricao.trim(),
            vendedorCodigo = if (bloqueio) null else normalizarCodigo(req.vendedorCodigo),
            status = req.status,
            // O score nao se lanca aqui: ele e atribuido no relatorio semanal, olhando o
            // servico entregue. Na agenda ele seria um palpite antes do trabalho existir.
            score = null,
            ordemServico = if (bloqueio) null else req.osId?.let { carregarOrdem(it) },
            observacao = req.observacao?.trim()?.takeIf { it.isNotEmpty() },
            criadoPor = usuarioRepository.findById(autor.id).orElse(null)
        )
        val salvo = agendamentoRepository.save(agendamento)
        reempilhar(salvo.data, adesivador, setOf(salvo.id!!))
        return carregar(salvo.id!!).paraResponse()
    }

    @Transactional
    fun atualizar(id: Long, req: NovoAgendamentoRequest, autor: UsuarioAutenticado): AgendamentoResponse {
        garantirPodeEditar(autor)
        val agendamento = carregar(id)

        registrarParaDesfazer(
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
        agendamento.status = req.status
        // O score vem do relatorio semanal; editar o carro na agenda nao o toca, e virar
        // bloqueio o apaga junto com o resto do que so faz sentido num servico.
        if (bloqueio) agendamento.score = null
        agendamento.ordemServico = if (bloqueio) null else req.osId?.let { carregarOrdem(it) }
        agendamento.observacao = req.observacao?.trim()?.takeIf { it.isNotEmpty() }
        agendamento.atualizadoEm = Instant.now()
        agendamentoRepository.save(agendamento)

        if (mudouDeLugar) reempilhar(agendamento.data, agendamento.adesivador, setOf(id))
        return carregar(id).paraResponse()
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
        registrarParaDesfazer(
            autor, "mudar as horas de \"${agendamento.descricao}\"",
            listOf(agendamento.adesivador), agendamento.data
        )

        agendamento.horasEstimadas = horas
        agendamento.atualizadoEm = Instant.now()
        agendamentoRepository.save(agendamento)

        reempilhar(agendamento.data, agendamento.adesivador, setOf(id))
        return semana(agendamento.data, autor)
    }

    /**
     * Redimensionar pela borda de cima: o servico passa a comecar em outro horario (ate em
     * outro dia) e termina onde ja terminava; as horas saem da conta entre os dois.
     *
     * Crescer para cima so em horario livre - quem esta acima nao e empurrado nem coberto.
     * Como o fim nao muda, ninguem abaixo e afetado: nao ha reempilhamento.
     */
    @Transactional
    fun alterarInicio(id: Long, novaData: LocalDate, novoSlot: Int, autor: UsuarioAutenticado): SemanaAgendaResponse {
        garantirPodeEditar(autor)
        val agendamento = carregar(id)
        validarFaixaInicial(novoSlot, novaData)
        if (agendamento.data == novaData && agendamento.slotInicio == novoSlot) return semana(novaData, autor)
        if (novaData.dayOfWeek == DayOfWeek.SATURDAY || novaData.dayOfWeek == DayOfWeek.SUNDAY) {
            throw RegraDeNegocioException("A agenda nao tem sabado nem domingo.")
        }

        val fimDia = agendamento.ultimoDia
        val fimFaixa = agendamento.posicoesOcupadas.last() % FaixasDoDia.QUANTIDADE
        if (novaData.isAfter(fimDia) || (novaData == fimDia && novoSlot - 1 > fimFaixa)) {
            throw RegraDeNegocioException("O inicio precisa ficar antes do fim do servico.")
        }
        var dias = 0
        var dia = novaData
        while (dia.isBefore(fimDia)) {
            dia = FaixasDoDia.diaUtilAFrente(dia, 1)
            dias++
        }
        val posicoes = ((novoSlot - 1)..(dias * FaixasDoDia.QUANTIDADE + fimFaixa))
            .filterNot { FaixasDoDia.bloqueada(novaData, it) }
        val horas = FaixasDoDia.horasDasPosicoes(posicoes)
        if (horas < BigDecimal("0.5")) throw RegraDeNegocioException("O servico precisa de ao menos meia hora.")

        // As faixas que o card ganha por cima precisam estar livres.
        val celulasNovas = posicoes.map { celula(novaData, it) }.toSet() -
            agendamento.posicoesOcupadas.map { celula(agendamento.data, it) }.toSet()
        if (celulasNovas.isNotEmpty()) {
            val vizinho = agendamentoRepository
                .doPeriodoDoAdesivador(novaData.minusWeeks(SEMANAS_ANTERIORES_CONTINUACAO), fimDia, agendamento.adesivador.id!!)
                .filter { it.id != id }
                .firstOrNull { outro -> outro.posicoesOcupadas.any { celula(outro.data, it) in celulasNovas } }
            if (vizinho != null) {
                throw RegraDeNegocioException("\"${vizinho.descricao}\" ja ocupa esse horario. Mova-o antes de aumentar o card para cima.")
            }
        }

        registrarParaDesfazer(
            autor, "mudar o inicio de \"${agendamento.descricao}\"",
            listOf(agendamento.adesivador), minOf(agendamento.data, novaData)
        )
        agendamento.data = novaData
        agendamento.slotInicio = novoSlot
        agendamento.horasEstimadas = horas
        agendamento.atualizadoEm = Instant.now()
        agendamentoRepository.save(agendamento)
        return semana(novaData, autor)
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

        registrarParaDesfazer(
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

        if (ocupante == null) {
            colocar(arrastado, destinoAdesivador, destinoData, destinoSlot, faixasDoArrastado)
        } else if (origemAdesivador.id == destinoAdesivador.id) {
            trocarNaMesmaColuna(arrastado, ocupante)
        } else {
            val faixasDoOcupante = ocupante.posicoesOcupadas.size
            val slotDoOcupante = ocupante.slotInicio
            colocar(arrastado, destinoAdesivador, destinoData, slotDoOcupante, faixasDoArrastado)
            colocar(ocupante, origemAdesivador, origemData, origemSlot, faixasDoOcupante)
        }

        val editados = setOfNotNull(arrastado.id, ocupante?.id)
        if (origemAdesivador.id == destinoAdesivador.id) {
            // Mesma coluna: a janela comeca no mais cedo dos dois lugares, senao quem
            // voltou para a origem ficaria fora da conta.
            reempilhar(minOf(origemData, destinoData), destinoAdesivador, editados)
        } else {
            reempilhar(destinoData, destinoAdesivador, editados)
            reempilhar(origemData, origemAdesivador, editados)
        }

        return semana(destinoData, autor)
    }

    /**
     * Troca na mesma coluna: os dois trocam de ordem e continuam encostados como estavam.
     * Quem vinha depois passa a comecar onde o primeiro comecava; o outro vem logo em
     * seguida, com o mesmo espaco que havia entre eles. O par termina onde terminava,
     * entao ninguem abaixo e empurrado.
     */
    private fun trocarNaMesmaColuna(um: Agendamento, outro: Agendamento) {
        val base = minOf(um.data, outro.data)
        fun inicio(a: Agendamento) = diasUteisEntre(base, a.data) * FaixasDoDia.QUANTIDADE + a.slotInicio - 1
        fun fim(a: Agendamento) = diasUteisEntre(base, a.data) * FaixasDoDia.QUANTIDADE + a.posicoesOcupadas.last()

        val (primeiro, segundo) = if (inicio(um) <= inicio(outro)) um to outro else outro to um
        val faixasPrimeiro = primeiro.posicoesOcupadas.size
        val faixasSegundo = segundo.posicoesOcupadas.size
        // Horarios de trabalho vagos entre os dois; o almoco no meio nao e espaco.
        val espaco = ((fim(primeiro) + 1) until inicio(segundo)).count { !FaixasDoDia.bloqueada(base, it) }

        val novoInicioSegundo = FaixasDoDia.livreAPartirDe(base, inicio(primeiro))
        val fimSegundo = posicoesDeFaixas(base, novoInicioSegundo, faixasSegundo).last()
        var novoInicioPrimeiro = fimSegundo + 1
        repeat(espaco) { novoInicioPrimeiro = FaixasDoDia.livreAPartirDe(base, novoInicioPrimeiro) + 1 }
        novoInicioPrimeiro = FaixasDoDia.livreAPartirDe(base, novoInicioPrimeiro)

        val adesivador = primeiro.adesivador
        colocar(segundo, adesivador, base, novoInicioSegundo, faixasSegundo, posicaoAbsoluta = true)
        colocar(primeiro, adesivador, base, novoInicioPrimeiro, faixasPrimeiro, posicaoAbsoluta = true)
    }

    /**
     * Poe o servico num lugar com a mesma quantidade de faixas que ele tinha. Com
     * `posicaoAbsoluta`, `slotOuPosicao` e a posicao na regua a partir de `dia`.
     */
    private fun colocar(
        item: Agendamento, adesivador: Adesivador, dia: LocalDate, slotOuPosicao: Int, faixas: Int,
        posicaoAbsoluta: Boolean = false
    ) {
        val posicao = if (posicaoAbsoluta) slotOuPosicao else slotOuPosicao - 1
        item.data = FaixasDoDia.diaUtilAFrente(dia, posicao / FaixasDoDia.QUANTIDADE)
        item.slotInicio = posicao % FaixasDoDia.QUANTIDADE + 1
        item.adesivador = adesivador
        item.horasEstimadas = FaixasDoDia.horasDasPosicoes(posicoesDeFaixas(item.data, item.slotInicio - 1, faixas))
        item.atualizadoEm = Instant.now()
        agendamentoRepository.save(item)
    }

    /** As N primeiras posicoes de trabalho a partir de uma posicao (almoco e sexta 17h nao contam). */
    private fun posicoesDeFaixas(base: LocalDate, inicio: Int, quantidade: Int): List<Int> =
        generateSequence(inicio) { it + 1 }.filterNot { FaixasDoDia.bloqueada(base, it) }.take(maxOf(1, quantidade)).toList()

    /** Dias uteis de um dia ate outro (0 no mesmo dia), como a regua da agenda conta. */
    private fun diasUteisEntre(de: LocalDate, ate: LocalDate): Int {
        var dias = 0
        var dia = de
        while (dia.isBefore(ate)) {
            dia = FaixasDoDia.diaUtilAFrente(dia, 1)
            dias++
        }
        return dias
    }

    @Transactional
    fun alterarStatus(id: Long, novo: StatusAgendamento, autor: UsuarioAutenticado): AgendamentoResponse {
        val agendamento = carregar(id)
        garantirPodeEditar(autor)
        registrarParaDesfazer(
            autor, "mudar o status de \"${agendamento.descricao}\"",
            listOf(agendamento.adesivador), agendamento.data
        )

        // Marcar o andamento e o que registra a entrada e a saida reais do servico:
        // com os dois carimbos, a produtividade deixa de ser estimativa.
        val agora = Instant.now()
        when (novo) {
            StatusAgendamento.EXECUTANDO -> if (agendamento.iniciadoEm == null) agendamento.iniciadoEm = agora
            StatusAgendamento.CONCLUIDO -> {
                if (agendamento.iniciadoEm == null) agendamento.iniciadoEm = agora
                agendamento.concluidoEm = agora
            }
            else -> Unit
        }
        agendamento.status = novo
        agendamento.atualizadoEm = agora
        return agendamentoRepository.save(agendamento).paraResponse()
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
        agendamento.score = score
        agendamento.atualizadoEm = Instant.now()
        agendamentoRepository.save(agendamento)
        return agendamento.data
    }

    @Transactional
    fun vincularOs(id: Long, req: VinculoOsRequest, autor: UsuarioAutenticado): AgendamentoResponse {
        garantirPodeEditar(autor)
        val agendamento = carregar(id)
        registrarParaDesfazer(
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
        return agendamentoRepository.save(agendamento).paraResponse()
    }

    @Transactional
    fun excluir(id: Long, autor: UsuarioAutenticado) {
        garantirPodeEditar(autor)
        val agendamento = agendamentoRepository.findById(id)
            .orElseThrow { NaoEncontradoException("Agendamento $id nao encontrado.") }
        val data = agendamento.data
        val adesivador = agendamento.adesivador
        registrarParaDesfazer(autor, "excluir \"${agendamento.descricao}\"", listOf(adesivador), data)
        // Nada a reempilhar: tirar um servico abre um buraco, e ninguem sobe para ocupa-lo.
        agendamentoRepository.delete(agendamento)
    }

    // --------------------------------------------------------- reempilhamento

    /**
     * Reacomoda a agenda de um adesivador depois de uma edicao, empurrando para baixo quem
     * a edicao atingiu.
     *
     * Regras:
     * - um servico nunca sobe: ele fica onde esta, a menos que alguem o empurre;
     * - so e empurrado quem encosta num servico `editado` ou num que ja foi empurrado
     *   agora - a "onda" da edicao. Uma sobreposicao antiga entre dois servicos que
     *   ninguem tocou (a base importada tem varias) fica como esta: corrigi-la aqui
     *   arrastaria a agenda inteira por semanas a cada salvamento;
     * - na mesma faixa, o editado fica e o outro desce: marcar ferias as 07:30 de um dia
     *   ocupado empurra os servicos daquele dia, e nao o bloqueio;
     * - o servico que passa do fim do dia continua no proximo dia util;
     * - o almoco e pulado na hora de escolher onde um servico comeca.
     */
    fun reempilhar(apartirDe: LocalDate, adesivador: Adesivador, editados: Set<Long>, diasUteis: Int = 20) {
        // Os dias anteriores entram so como obstaculo: um servico longo que comecou antes
        // ainda ocupa o comeco da janela.
        val anteriores = diasUteisAntesDe(apartirDe, DIAS_ANTERIORES_OBSTACULO)
        val dias = anteriores + diasUteisAPartirDe(apartirDe, diasUteis)
        val posicaoDoDia = dias.withIndex().associate { (indice, dia) -> dia to indice }
        val faixasDoDia = FaixasDoDia.QUANTIDADE
        val limiteDaJanela = dias.size * faixasDoDia

        val itens = agendamentoRepository
            .doPeriodoDoAdesivador(dias.first(), dias.last(), adesivador.id!!)
            .filter { posicaoDoDia.containsKey(it.data) }
            .sortedWith(compareBy({ it.data }, { it.slotInicio }, { if (it.id in editados) 0 else 1 }, { it.id }))

        // Ultima posicao tomada por qualquer servico ja acomodado.
        var ocupadoAte = -1
        // Ultima posicao tomada por quem foi editado ou empurrado nesta passada.
        var ondaAte = -1

        for (item in itens) {
            val atual = posicaoDoDia[item.data]!! * faixasDoDia + (item.slotInicio - 1)
            val naOnda = item.id in editados || atual <= ondaAte
            if (!naOnda) {
                ocupadoAte = maxOf(ocupadoAte, FaixasDoDia.posicoesAPartirDe(dias.first(), atual, item.horas).last())
                continue
            }

            var posicao = maxOf(atual, ocupadoAte + 1)
            // O almoco e a sexta depois das 17:00 nunca recebem o inicio de um servico.
            posicao = FaixasDoDia.livreAPartirDe(dias.first(), posicao)

            if (posicao >= limiteDaJanela) {
                throw RegraDeNegocioException(
                    "Nao ha espaco na agenda de ${adesivador.nome} nos proximos $diasUteis dias uteis " +
                        "para encaixar \"${item.descricao}\"."
                )
            }

            val novaData = dias[posicao / faixasDoDia]
            val novaFaixa = (posicao % faixasDoDia) + 1
            if (item.data != novaData || item.slotInicio != novaFaixa) {
                item.data = novaData
                item.slotInicio = novaFaixa
                item.atualizadoEm = Instant.now()
                agendamentoRepository.save(item)
            }
            val fim = FaixasDoDia.posicoesAPartirDe(dias.first(), posicao, item.horas).last()
            ondaAte = maxOf(ondaAte, fim)
            ocupadoAte = maxOf(ocupadoAte, fim)
        }
    }

    /** A agenda e de segunda a sexta; fins de semana nao entram na conta. */
    private fun diasUteisAPartirDe(inicio: LocalDate, quantidade: Int): List<LocalDate> {
        val dias = mutableListOf<LocalDate>()
        var dia = inicio
        while (dias.size < quantidade) {
            if (ehDiaUtil(dia)) dias += dia
            dia = dia.plusDays(1)
        }
        return dias
    }

    private fun diasUteisAntesDe(inicio: LocalDate, quantidade: Int): List<LocalDate> {
        val dias = ArrayDeque<LocalDate>()
        var dia = inicio.minusDays(1)
        while (dias.size < quantidade) {
            if (ehDiaUtil(dia)) dias.addFirst(dia)
            dia = dia.minusDays(1)
        }
        return dias.toList()
    }

    private fun ehDiaUtil(dia: LocalDate) =
        dia.dayOfWeek != DayOfWeek.SATURDAY && dia.dayOfWeek != DayOfWeek.SUNDAY

    // ------------------------------------------------------------------ helpers

    private fun podeEditar(autor: UsuarioAutenticado) =
        autor.perfil == PerfilNome.VENDEDOR ||
            autor.perfil == PerfilNome.DIRETORIA ||
            autor.perfil == PerfilNome.ADMIN

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

    private fun nomeDoDia(dia: DayOfWeek) = when (dia) {
        DayOfWeek.MONDAY -> "SEG"
        DayOfWeek.TUESDAY -> "TER"
        DayOfWeek.WEDNESDAY -> "QUA"
        DayOfWeek.THURSDAY -> "QUI"
        DayOfWeek.FRIDAY -> "SEX"
        DayOfWeek.SATURDAY -> "SAB"
        DayOfWeek.SUNDAY -> "DOM"
    }

    private fun Adesivador.paraResponse() =
        AdesivadorResponse(id!!, nome, tipo, ordem, ativo)

    /**
     * Varios agendamentos de uma vez: os fluxos de material de todas as OS vem numa
     * consulta so, em vez de uma por carro.
     */
    private fun List<Agendamento>.paraResponses(): List<AgendamentoResponse> {
        val fluxosPorOs = fluxosDasOs(mapNotNull { it.ordemServico?.id }.toSet())
        return map { it.paraResponse(fluxosPorOs) }
    }

    private fun fluxosDasOs(osIds: Set<Int>): Map<Int, List<FluxoOs>> =
        if (osIds.isEmpty()) emptyMap()
        else fluxoRepository.findByOrdemServicoIdInOrderByIdAsc(osIds).groupBy { it.ordemServico.id!! }

    private fun Agendamento.paraResponse(
        fluxosPorOs: Map<Int, List<FluxoOs>> = fluxosDasOs(setOfNotNull(ordemServico?.id))
    ): AgendamentoResponse {
        val posicoes = posicoesOcupadas
        val ultima = FaixasDoDia.faixaDaPosicao(posicoes.last())
        return AgendamentoResponse(
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
            observacao = observacao,
            material = ordemServico?.let { montarMaterial(it, fluxosPorOs[it.id].orEmpty()) }
        )
    }

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
            osCancelada = os.cancelada,
            pronto = pronto,
            fluxos = fluxos.map {
                MaterialFluxoResponse(it.id!!, it.identificadorFluxo, it.setorAtual.nome, it.statusAtual)
            }
        )
    }

    private companion object {
        /** Quantos dias uteis antes da edicao entram como obstaculo (servico longo em curso). */
        const val DIAS_ANTERIORES_OBSTACULO = 10

        /** Ate quantas semanas atras procurar servico longo que ainda chega na semana vista. */
        const val SEMANAS_ANTERIORES_CONTINUACAO = 3L
    }
}
