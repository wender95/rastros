package com.rastros.service

import com.rastros.api.*
import com.rastros.domain.*
import com.rastros.repository.*
import com.rastros.security.UsuarioAutenticado
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Uma OS parada num setor, com as datas que o painel mostra. */
data class OsNoSetorResponse(
    val fluxoId: Int,
    val osId: Int,
    val numeroOsErp: String,
    val cliente: String?,
    /** O que a OS manda fazer, como veio do ERP. */
    val servico: String?,
    val identificadorFluxo: String,
    val status: StatusFluxo,
    val recebidoPor: String?,
    /** Quando a OS entrou no sistema. */
    val dataOs: Instant,
    /** Quando chegou neste setor (despachada para ele, ou aberta direto nele). */
    val chegouEm: Instant
)

/** O que os projetos da agenda fizeram com a OS - para a tela dizer ao adesivador. */
data class ProjetosDaOs(
    /** Fluxos recebidos na Frota agora, em nome de quem iniciou o projeto. */
    val recebidas: Int = 0,
    /** Fluxos que sairam da Frota para o Patio agora. */
    val paraOPatio: Int = 0,
    /** Outros projetos da mesma OS que ainda nao terminaram (seguram a ida ao Patio). */
    val faltam: List<String> = emptyList(),
    /** Nenhum fluxo da OS esta na Frota, mas ainda ha material em producao vindo. */
    val osACaminho: Boolean = false
)

@Service
class FluxoService(
    private val ordemRepository: OrdemServicoRepository,
    private val fluxoRepository: FluxoOsRepository,
    private val eventoRepository: EventoMovimentacaoRepository,
    private val setorRepository: SetorRepository,
    private val usuarioRepository: UsuarioRepository,
    private val matriz: MatrizTransicaoService,
    private val agendaDaFrota: AgendaDaFrotaService,
    private val agendamentoRepository: AgendamentoRepository
) {

    // ---------------------------------------------------------------- criacao

    @Transactional
    fun criarOrdem(req: NovaOrdemRequest, autor: UsuarioAutenticado): OrdemResponse {
        garantirPodeAbrirOs(autor)
        val usuario = carregarUsuario(autor.id)
        val iniciaisValidos = matriz.setoresIniciais().mapNotNull { it.id }.toSet()
        // Aviso claro para o caso comum; o UNIQUE do banco segura dois cadastros simultaneos.
        ordemRepository.findByNumeroAtivo(normalizarNumeroOs(req.numeroOsErp))?.let {
            throw RegraDeNegocioException(
                "A OS ${it.numeroOsErp} ja esta no sistema (ja foi enviada). Nada foi criado."
            )
        }

        val os = OrdemServico(
            numeroOsErp = req.numeroOsErp.trim(),
            cliente = req.cliente?.trim()?.takeIf { it.isNotEmpty() },
            servico = req.servico?.trim()?.takeIf { it.isNotEmpty() },
            criadoPor = usuario
        )
        ordemRepository.save(os)

        req.fluxos.forEach { novo -> criarFluxo(os, novo, usuario, iniciaisValidos) }
        acompanharProjetos(os)

        return os.paraResponse(fluxoRepository.findByOrdemServicoIdOrderByIdAsc(os.id!!))
    }

    @Transactional
    fun adicionarFluxo(osId: Int, novo: NovoFluxoRequest, autor: UsuarioAutenticado): OrdemResponse {
        garantirPodeAbrirOs(autor)
        val os = ordemRepository.findById(osId)
            .orElseThrow { NaoEncontradoException("Ordem de servico $osId nao encontrada.") }
        if (os.cancelada) throw RegraDeNegocioException("A OS ${os.numeroOsErp} esta cancelada.")

        criarFluxo(os, novo, carregarUsuario(autor.id), matriz.setoresIniciais().mapNotNull { it.id }.toSet())
        acompanharProjetos(os)
        return os.paraResponse(fluxoRepository.findByOrdemServicoIdOrderByIdAsc(os.id!!))
    }

    private fun criarFluxo(
        os: OrdemServico,
        novo: NovoFluxoRequest,
        usuario: Usuario,
        iniciaisValidos: Set<Int>
    ): FluxoOs {
        val setorInicial = setorRepository.findById(novo.setorInicialId!!)
            .orElseThrow { NaoEncontradoException("Setor ${novo.setorInicialId} nao encontrado.") }

        if (setorInicial.id !in iniciaisValidos) {
            throw RegraDeNegocioException(
                "${setorInicial.nome} nao e um setor inicial valido para abertura de fluxo."
            )
        }

        val fluxo = fluxoRepository.save(
            FluxoOs(
                ordemServico = os,
                identificadorFluxo = novo.identificador.trim().ifEmpty { "Principal" },
                setorAtual = setorInicial,
                statusAtual = StatusFluxo.AGUARDANDO_RECEBIMENTO,
                entrouNoSetorEm = Instant.now()
            )
        )
        registrarEvento(fluxo, null, setorInicial, TipoEvento.CRIACAO, usuario, novo.observacao)
        return fluxo
    }

    // ------------------------------------------------------------ movimentacao

    /** RN01: grava o primeiro usuario que clicou em Receber naquele setor. */
    @Transactional
    fun receber(fluxoId: Int, req: ReceberRequest, autor: UsuarioAutenticado): FluxoDetalheResponse {
        val fluxo = carregarFluxo(fluxoId)
        garantirFluxoAtivo(fluxo)
        matriz.validarExecutorRecebimento(fluxo, autor)

        if (fluxo.statusAtual != StatusFluxo.AGUARDANDO_RECEBIMENTO) {
            throw RegraDeNegocioException(
                "Este fluxo ja foi recebido em ${fluxo.setorAtual.nome}" +
                    (fluxo.recebidoPor?.let { " por ${it.nome}" } ?: "") + "."
            )
        }

        receberAgora(fluxo, carregarUsuario(autor.id), req.observacao)
        // A Frota pegou o carro: na agenda ele passa a Executando.
        if (fluxo.setorAtual.nome == SetorNome.FROTA) agendaDaFrota.frotaRecebeu(fluxo.ordemServico)
        return detalhe(fluxo.id!!, autor)
    }

    @Transactional
    fun despachar(fluxoId: Int, req: DespacharRequest, autor: UsuarioAutenticado): FluxoDetalheResponse {
        val fluxo = carregarFluxo(fluxoId)
        garantirFluxoAtivo(fluxo)
        matriz.validarExecutorDespacho(fluxo, autor)

        // Locais fisicos (Prateleira/Patio) nao possuem etapa de recebimento.
        if (!fluxo.setorAtual.nome.localFisico && fluxo.statusAtual != StatusFluxo.EM_PROCESSAMENTO) {
            throw RegraDeNegocioException("Receba o fluxo em ${fluxo.setorAtual.nome} antes de despacha-lo.")
        }

        val destino = setorRepository.findById(req.setorDestinoId!!)
            .orElseThrow { NaoEncontradoException("Setor ${req.setorDestinoId} nao encontrado.") }
        matriz.validarDestino(fluxo, destino)

        val ehRetorno = matriz.destinosDe(fluxo).first { it.setorId == destino.id }.retorno
        val tipo = when {
            destino.nome == SetorNome.FINANCEIRO -> TipoEvento.ENTREGA
            ehRetorno -> TipoEvento.RETORNO
            else -> TipoEvento.DESPACHO
        }
        val saiuDaFrota = fluxo.setorAtual.nome == SetorNome.FROTA
        levarPara(fluxo, destino, tipo, carregarUsuario(autor.id), req.observacao)
        // Carro pronto no Patio: na agenda ele fica Concluido.
        if (saiuDaFrota && destino.nome == SetorNome.PATIO) agendaDaFrota.frotaEntregouNoPatio(fluxo.ordemServico)
        return detalhe(fluxo.id!!, autor)
    }

    /**
     * Devolve a OS ao setor de onde ela veio - o botao Devolver da tela do setor. So depois
     * de recebida: quem devolve e quem conferiu o material.
     */
    @Transactional
    fun devolver(fluxoId: Int, req: ReceberRequest, autor: UsuarioAutenticado): FluxoDetalheResponse {
        val fluxo = carregarFluxo(fluxoId)
        garantirFluxoAtivo(fluxo)
        matriz.validarExecutorDespacho(fluxo, autor)
        if (fluxo.statusAtual != StatusFluxo.EM_PROCESSAMENTO) {
            throw RegraDeNegocioException("Receba a OS em ${fluxo.setorAtual.nome} antes de devolve-la.")
        }
        val anterior = fluxo.setorAnterior
            ?: throw RegraDeNegocioException(
                "Esta OS entrou em ${fluxo.setorAtual.nome} pelo comercial: nao ha setor anterior para devolver."
            )
        val saiuDaFrota = fluxo.setorAtual.nome == SetorNome.FROTA
        levarPara(fluxo, anterior, TipoEvento.RETORNO, carregarUsuario(autor.id), req.observacao)
        // Voltou da Frota sem terminar: o carro deixa de estar em execucao.
        if (saiuDaFrota) agendaDaFrota.frotaDevolveu(fluxo.ordemServico)
        return detalhe(fluxo.id!!, autor)
    }

    /** Chegar a um setor - inclusive o Financeiro - e so chegar: o fluxo fica esperando ser recebido. */
    private fun levarPara(fluxo: FluxoOs, destino: Setor, tipo: TipoEvento, usuario: Usuario, observacao: String?) {
        val origem = fluxo.setorAtual
        fluxo.setorAnterior = origem
        fluxo.setorAtual = destino
        fluxo.recebidoPor = null
        fluxo.recebidoEm = null
        fluxo.entrouNoSetorEm = Instant.now()
        fluxo.statusAtual = StatusFluxo.AGUARDANDO_RECEBIMENTO
        fluxoRepository.save(fluxo)
        registrarEvento(fluxo, origem, destino, tipo, usuario, observacao)
        // Chegou na Frota: se o adesivador ja iniciou o projeto, ela e recebida na hora.
        if (destino.nome == SetorNome.FROTA) acompanharProjetos(fluxo.ordemServico)
    }

    /** RN01: quem recebe fica gravado no fluxo e na linha do tempo. */
    private fun receberAgora(fluxo: FluxoOs, usuario: Usuario, observacao: String?) {
        fluxo.statusAtual = StatusFluxo.EM_PROCESSAMENTO
        fluxo.recebidoPor = usuario
        fluxo.recebidoEm = Instant.now()
        fluxoRepository.save(fluxo)
        registrarEvento(fluxo, fluxo.setorAnterior, fluxo.setorAtual, TipoEvento.RECEBIMENTO, usuario, observacao)
    }

    // ------------------------------------------------------- projetos da agenda

    /**
     * Na Frota, quem move a OS sao os **projetos da agenda**: o adesivador nao recebe nem
     * despacha, ele inicia e conclui o projeto, e a OS acompanha.
     *
     * - Projeto iniciado e OS esperando na Frota: a OS e recebida em nome de quem iniciou -
     *   na hora, se ja estava la, ou assim que chegar.
     * - Todos os projetos da OS concluidos: a OS sai da Frota para o Patio em nome de quem
     *   concluiu por ultimo - na hora, ou assim que chegar, se o material ainda vinha vindo.
     *   Uma OS de frota com tres carros so vai para o Patio quando o terceiro terminar.
     *
     * Chamado depois de tudo que pode mudar esse quadro; nao repete nada, so faz o que falta.
     * So conta projeto iniciado **pelo adesivador da Frota** na propria agenda: estado mudado
     * no escritorio nao move OS.
     */
    @Transactional
    fun acompanharProjetos(os: OrdemServico): ProjetosDaOs {
        val projetos = agendamentoRepository.findByOrdemServicoIdOrderByDataAsc(os.id!!).filter { it.ehServico }
        if (projetos.none { daFrota(it.iniciadoPor) }) return ProjetosDaOs()

        val fluxos = fluxoRepository.findByOrdemServicoIdOrderByIdAsc(os.id!!).filter { !it.encerrado }
        val naFrota = fluxos.filter { it.setorAtual.nome == SetorNome.FROTA }
        val pendentes = projetos.filter { it.status in PROJETO_PENDENTE }
        val emAndamento = projetos
            .filter { it.status == StatusAgendamento.EXECUTANDO && daFrota(it.iniciadoPor) }
            .minByOrNull { it.iniciadoEm ?: Instant.MAX }
        val ultimoConcluido = projetos
            .filter { it.status == StatusAgendamento.CONCLUIDO && daFrota(it.iniciadoPor) }
            .maxByOrNull { it.concluidoEm ?: Instant.MIN }
        val prontaParaOPatio = pendentes.isEmpty() && ultimoConcluido != null

        // Recebe quem esta com o projeto; terminado tudo, quem concluiu por ultimo.
        val quem = emAndamento ?: ultimoConcluido?.takeIf { prontaParaOPatio }
        var recebidas = 0
        if (quem != null) {
            naFrota.filter { it.statusAtual == StatusFluxo.AGUARDANDO_RECEBIMENTO }.forEach {
                receberAgora(it, quem.iniciadoPor!!, "Recebida pelo projeto \"${quem.descricao}\", iniciado na agenda.")
                recebidas++
            }
        }

        var paraOPatio = 0
        val patio = setorRepository.findByNome(SetorNome.PATIO)
        if (prontaParaOPatio && patio != null) {
            naFrota
                .filter { it.statusAtual == StatusFluxo.EM_PROCESSAMENTO }
                .filter { f -> matriz.destinosDe(f).any { it.setorId == patio.id } }
                .forEach {
                    levarPara(
                        it, patio, TipoEvento.DESPACHO, ultimoConcluido!!.iniciadoPor!!,
                        "Despachada ao concluir o projeto \"${ultimoConcluido.descricao}\" na agenda."
                    )
                    paraOPatio++
                }
        }

        return ProjetosDaOs(
            recebidas = recebidas,
            paraOPatio = paraOPatio,
            faltam = pendentes.map { it.descricao }.distinct(),
            osACaminho = naFrota.isEmpty() && fluxos.any { !it.setorAtual.nome.saiuDaProducao }
        )
    }

    /** As OS que estao num setor agora, esperando ou sendo trabalhadas; a mais antiga primeiro. */
    @Transactional(readOnly = true)
    fun noSetor(nome: SetorNome): List<OsNoSetorResponse> {
        val setor = setorRepository.findByNome(nome) ?: return emptyList()
        return fluxoRepository.findBySetorAtualIdAndEncerradoFalseOrderByEntrouNoSetorEmAsc(setor.id!!)
            .filter { it.statusAtual != StatusFluxo.CANCELADA }
            .map {
                OsNoSetorResponse(
                    fluxoId = it.id!!,
                    osId = it.ordemServico.id!!,
                    numeroOsErp = it.ordemServico.numeroOsErp,
                    cliente = it.ordemServico.cliente,
                    servico = it.ordemServico.servico,
                    identificadorFluxo = it.identificadorFluxo,
                    status = it.statusAtual,
                    recebidoPor = it.recebidoPor?.nome,
                    dataOs = it.ordemServico.criadoEm,
                    chegouEm = it.entrouNoSetorEm
                )
            }
    }

    /** Adesivador da Frota: so ele move a OS pelo projeto. */
    private fun daFrota(usuario: Usuario?) = usuario != null && usuario.ativo &&
        usuario.perfil.nome == PerfilNome.OPERACIONAL && usuario.setores.any { it.nome == SetorNome.FROTA }

    /**
     * Conclui o fluxo. So o Financeiro faz isso, e so depois de receber a OS: e o unico
     * jeito de uma OS terminar dentro do sistema (fora o cancelamento).
     */
    @Transactional
    fun concluir(fluxoId: Int, req: ReceberRequest, autor: UsuarioAutenticado): FluxoDetalheResponse {
        val fluxo = carregarFluxo(fluxoId)
        garantirFluxoAtivo(fluxo)
        if (fluxo.setorAtual.nome != SetorNome.FINANCEIRO || !matriz.trabalhaNoSetor(autor, fluxo.setorAtual)) {
            throw PermissaoNegadaException("Somente o Financeiro conclui o fluxo de uma OS.")
        }
        if (fluxo.statusAtual != StatusFluxo.EM_PROCESSAMENTO) {
            throw RegraDeNegocioException("Receba a OS no Financeiro antes de concluir.")
        }
        val usuario = carregarUsuario(autor.id)
        fluxo.statusAtual = StatusFluxo.ENCERRADA
        fluxo.encerrado = true
        fluxo.encerradoEm = Instant.now()
        fluxoRepository.save(fluxo)
        registrarEvento(fluxo, fluxo.setorAtual, fluxo.setorAtual, TipoEvento.CONCLUSAO, usuario, req.observacao)
        return detalhe(fluxo.id!!, autor)
    }

    // ------------------------------------------------------------ cancelamento

    @Transactional
    fun cancelarFluxo(fluxoId: Int, req: MotivoRequest, autor: UsuarioAutenticado): FluxoDetalheResponse {
        garantirPodeCancelar(autor)
        val fluxo = carregarFluxo(fluxoId)
        garantirFluxoAtivo(fluxo)

        val usuario = carregarUsuario(autor.id)
        aplicarCancelamento(fluxo, req.motivo.trim(), usuario)
        return detalhe(fluxo.id!!, autor)
    }

    @Transactional
    fun cancelarOrdem(osId: Int, req: MotivoRequest, autor: UsuarioAutenticado): OrdemResponse {
        garantirPodeCancelar(autor)
        val os = ordemRepository.findById(osId)
            .orElseThrow { NaoEncontradoException("Ordem de servico $osId nao encontrada.") }

        val usuario = carregarUsuario(autor.id)
        val motivo = req.motivo.trim()
        val fluxos = fluxoRepository.findByOrdemServicoIdOrderByIdAsc(osId)
        val ativos = fluxos.filter { it.statusAtual != StatusFluxo.CANCELADA && !it.encerrado }
        if (ativos.isEmpty() && os.cancelada) {
            throw RegraDeNegocioException("A OS ${os.numeroOsErp} ja esta cancelada.")
        }
        ativos.forEach { aplicarCancelamento(it, motivo, usuario) }

        os.cancelada = true
        os.motivoCancelamento = motivo
        ordemRepository.save(os)

        return os.paraResponse(fluxoRepository.findByOrdemServicoIdOrderByIdAsc(osId))
    }

    /** RN02: o cancelamento muda o status e preserva integralmente o historico de eventos. */
    private fun aplicarCancelamento(fluxo: FluxoOs, motivo: String, usuario: Usuario) {
        fluxo.statusAtual = StatusFluxo.CANCELADA
        fluxo.encerrado = true
        fluxo.encerradoEm = Instant.now()
        fluxo.motivoCancelamento = motivo
        fluxoRepository.save(fluxo)
        registrarEvento(fluxo, fluxo.setorAtual, fluxo.setorAtual, TipoEvento.CANCELAMENTO, usuario, motivo)
    }

    /** Abrir, acrescentar fluxo e cancelar OS: comercial, diretoria e administrador. */
    private fun ehComercialOuAcima(autor: UsuarioAutenticado) =
        autor.tem(SecaoDoSistema.CRIAR_OS)

    private fun garantirPodeAbrirOs(autor: UsuarioAutenticado) {
        if (!ehComercialOuAcima(autor)) {
            throw PermissaoNegadaException("Apenas Comercial, Diretoria e Administrador abrem OS.")
        }
    }

    private fun garantirPodeCancelar(autor: UsuarioAutenticado) {
        if (!ehComercialOuAcima(autor)) {
            throw PermissaoNegadaException("Apenas Comercial, Diretoria e Administrador cancelam OS.")
        }
    }

    // --------------------------------------------------------------- consultas

    /**
     * Tudo que a tela do setor precisa, e nada mais: o que chegou para receber e o que ja
     * esta no setor, com os destinos possiveis de cada OS e para onde ela pode voltar.
     */
    @Transactional(readOnly = true)
    fun movimentacao(autor: UsuarioAutenticado): MovimentacaoResponse {
        val setor = matriz.setorDeTrabalho(autor)
            ?: throw RegraDeNegocioException("Seu usuario nao esta vinculado a um setor.")
        val setorId = setor.id!!
        val noSetor = fluxoRepository.findBySetorAtualIdAndEncerradoFalseOrderByEntrouNoSetorEmAsc(setorId)
            .filter { it.statusAtual != StatusFluxo.CANCELADA && !it.ordemServico.cancelada }

        fun item(fluxo: FluxoOs, comAcoes: Boolean): ItemMovimentacaoResponse {
            val destinos = if (comAcoes) matriz.destinosDe(fluxo) else emptyList()
            return ItemMovimentacaoResponse(
                fluxoId = fluxo.id!!,
                numeroOsErp = fluxo.ordemServico.numeroOsErp,
                cliente = fluxo.ordemServico.cliente,
                identificador = fluxo.identificadorFluxo,
                veioDe = fluxo.setorAnterior?.nome,
                desde = fluxo.recebidoEm ?: fluxo.entrouNoSetorEm,
                segundosDesde = HorarioComercial.entre(fluxo.recebidoEm ?: fluxo.entrouNoSetorEm, Instant.now()).seconds,
                recebidoPor = fluxo.recebidoPor?.nome,
                destinos = destinos.filter { !it.retorno },
                devolverPara = destinos.firstOrNull { it.retorno }?.setor,
                podeConcluir = comAcoes && fluxo.setorAtual.nome == SetorNome.FINANCEIRO
            )
        }

        return MovimentacaoResponse(
            setor = setor.nome,
            paraReceber = noSetor.filter { it.statusAtual == StatusFluxo.AGUARDANDO_RECEBIMENTO }
                .map { item(it, comAcoes = false) },
            noSetor = noSetor.filter { it.statusAtual == StatusFluxo.EM_PROCESSAMENTO }
                .map { item(it, comAcoes = true) }
        )
    }

    @Transactional(readOnly = true)
    fun listarFluxos(
        autor: UsuarioAutenticado,
        termo: String?,
        status: StatusFluxo?,
        setor: SetorNome? = null
    ): List<FluxoResponse> {
        val base = when {
            autor.podeVerTudo && !termo.isNullOrBlank() -> fluxoRepository.buscarPorTermo(termo.trim())
            autor.podeVerTudo -> fluxoRepository.listarRecentes(
                status, setor, org.springframework.data.domain.PageRequest.of(0, LIMITE_DA_LISTA)
            )
            autor.setorId != null -> fluxoRepository.findVisiveisParaSetor(autor.setorId)
            else -> emptyList()
        }
        val t = termo?.trim()?.lowercase()
        return base
            .filter { f ->
                t.isNullOrEmpty() ||
                    f.ordemServico.numeroOsErp.lowercase().contains(t) ||
                    (f.ordemServico.cliente ?: "").lowercase().contains(t) ||
                    f.identificadorFluxo.lowercase().contains(t)
            }
            .filter { status == null || it.statusAtual == status }
            .filter { setor == null || it.setorAtual.nome == setor }
            .map { it.paraResponse() }
    }

    /**
     * OS que ainda estao em andamento - as que podem ser vinculadas a um carro da agenda.
     * Uma OS pode atender varios carros; cada carro aponta para uma unica OS.
     */
    @Transactional(readOnly = true)
    fun ordensAbertas(): List<OrdemAbertaResponse> =
        // Numa consulta so, e so o que esta em andamento: nao cresce com o historico.
        fluxoRepository.ativosDeOsAbertas()
            .groupBy { it.ordemServico }
            .map { (os, ativos) ->
                OrdemAbertaResponse(
                    id = os.id!!,
                    numeroOsErp = os.numeroOsErp,
                    cliente = os.cliente,
                    fluxosAtivos = ativos.size,
                    setores = ativos.sortedBy { it.id }.map { it.setorAtual.nome }.distinct()
                )
            }
            .sortedByDescending { it.id }

    @Transactional(readOnly = true)
    fun detalhe(fluxoId: Int, autor: UsuarioAutenticado): FluxoDetalheResponse {
        val fluxo = carregarFluxo(fluxoId)
        garantirVisibilidade(fluxo, autor)
        val eventos = eventoRepository.findByFluxoIdOrderByDataHoraAscIdAsc(fluxoId).map { it.paraResponse() }
        return FluxoDetalheResponse(fluxo.paraResponse(), eventos, acoesDisponiveis(fluxo, autor))
    }

    /** A OS aberta (nao cancelada) com este numero, se ja foi enviada ao sistema. */
    @Transactional(readOnly = true)
    fun ordemPorNumero(numero: String): OsJaEnviadaResponse? {
        if (numero.isBlank()) return null
        val os = ordemRepository.findByNumeroAtivo(normalizarNumeroOs(numero)) ?: return null
        return OsJaEnviadaResponse(
            osId = os.id!!,
            numeroOsErp = os.numeroOsErp,
            cliente = os.cliente,
            enviadaEm = os.criadoEm,
            enviadaPor = os.criadoPor.nome,
            fluxos = fluxoRepository.findByOrdemServicoIdOrderByIdAsc(os.id!!).map { it.identificadorFluxo }
        )
    }

    @Transactional(readOnly = true)
    fun detalheOrdem(osId: Int, autor: UsuarioAutenticado): OrdemResponse {
        val os = ordemRepository.findById(osId)
            .orElseThrow { NaoEncontradoException("Ordem de servico $osId nao encontrada.") }
        val fluxos = fluxoRepository.findByOrdemServicoIdOrderByIdAsc(osId)
            .filter { autor.podeVerTudo || visivelParaSetor(it, matriz.setorDeTrabalho(autor)?.id) }
        if (fluxos.isEmpty() && !autor.podeVerTudo) {
            throw PermissaoNegadaException("Esta OS nao passou pelo seu setor.")
        }
        return os.paraResponse(fluxos)
    }

    fun acoesDisponiveis(fluxo: FluxoOs, autor: UsuarioAutenticado): AcoesDisponiveis {
        val ativo = !fluxo.encerrado && fluxo.statusAtual != StatusFluxo.CANCELADA
        val podeReceber = ativo &&
            fluxo.statusAtual == StatusFluxo.AGUARDANDO_RECEBIMENTO &&
            !fluxo.setorAtual.nome.localFisico &&
            matriz.trabalhaNoSetor(autor, fluxo.setorAtual)

        val podeDespachar = ativo && runCatching {
            matriz.validarExecutorDespacho(fluxo, autor)
            if (!fluxo.setorAtual.nome.localFisico && fluxo.statusAtual != StatusFluxo.EM_PROCESSAMENTO) {
                throw RegraDeNegocioException("aguardando recebimento")
            }
        }.isSuccess

        val podeCancelar = ativo && ehComercialOuAcima(autor)

        return AcoesDisponiveis(
            podeReceber = podeReceber,
            podeDespachar = podeDespachar,
            podeCancelar = podeCancelar,
            destinos = if (podeDespachar) matriz.destinosDe(fluxo) else emptyList()
        )
    }

    @Transactional(readOnly = true)
    fun painel(autor: UsuarioAutenticado, inicio: java.time.LocalDate, fim: java.time.LocalDate): PainelResponse {
        val zona = java.time.ZoneId.systemDefault()
        val de = inicio.atStartOfDay(zona).toInstant()
        val ate = fim.plusDays(1).atStartOfDay(zona).toInstant()
        // Quem ve tudo: o agora so com os fluxos em andamento, e o periodo contado no banco.
        // Assim o painel nao le o historico inteiro, que cresce todo dia.
        if (autor.podeVerTudo || autor.setorId == null) return painelGeral(inicio, fim, de, ate)

        val fluxos = fluxoRepository.findVisiveisParaSetor(autor.setorId)
        fun noPeriodo(t: Instant?) = t != null && !t.isBefore(de) && t.isBefore(ate)

        // Saidas de cada setor no periodo, contadas pela trilha de eventos.
        val visiveis = fluxos.mapNotNull { it.id }.toSet()
        val saidas = eventoRepository.findByDataHoraGreaterThanEqualAndDataHoraLessThan(de, ate)
            .filter { it.fluxo.id in visiveis }
            .mapNotNull { e ->
                when (e.tipoEvento) {
                    TipoEvento.DESPACHO, TipoEvento.RETORNO, TipoEvento.ENTREGA -> e.setorOrigem?.nome
                    TipoEvento.CONCLUSAO -> e.setorDestino.nome
                    else -> null
                }
            }
            .groupingBy { it }.eachCount()

        val ativos = fluxos.filter { !it.encerrado }.groupBy { it.setorAtual.nome }
        val porSetor = (ativos.keys + saidas.keys)
            .sortedBy { it.ordinal }
            .map { setor ->
                val lista = ativos[setor].orEmpty()
                ContagemSetor(
                    setor = setor,
                    aguardando = lista.count { it.statusAtual == StatusFluxo.AGUARDANDO_RECEBIMENTO },
                    emProcessamento = lista.count { it.statusAtual == StatusFluxo.EM_PROCESSAMENTO },
                    saidasNoPeriodo = saidas[setor] ?: 0
                )
            }

        return PainelResponse(
            fluxosAtivos = fluxos.count { !it.encerrado },
            aguardandoRecebimento = fluxos.count { !it.encerrado && it.statusAtual == StatusFluxo.AGUARDANDO_RECEBIMENTO },
            emProcessamento = fluxos.count { !it.encerrado && it.statusAtual == StatusFluxo.EM_PROCESSAMENTO },
            inicio = inicio,
            fim = fim,
            osAbertas = fluxos.map { it.ordemServico }.distinctBy { it.id }.count { noPeriodo(it.criadoEm) },
            concluidas = fluxos.count { it.statusAtual == StatusFluxo.ENCERRADA && noPeriodo(it.encerradoEm) },
            canceladas = fluxos.count { it.statusAtual == StatusFluxo.CANCELADA && noPeriodo(it.encerradoEm) },
            porSetor = porSetor
        )
    }

    /** O painel de quem ve todos os setores, sem carregar o historico. */
    private fun painelGeral(inicio: java.time.LocalDate, fim: java.time.LocalDate, de: Instant, ate: Instant): PainelResponse {
        val ativos = fluxoRepository.findByEncerradoFalse()
        val saidas = eventoRepository.findByDataHoraGreaterThanEqualAndDataHoraLessThan(de, ate)
            .mapNotNull { e ->
                when (e.tipoEvento) {
                    TipoEvento.DESPACHO, TipoEvento.RETORNO, TipoEvento.ENTREGA -> e.setorOrigem?.nome
                    TipoEvento.CONCLUSAO -> e.setorDestino.nome
                    else -> null
                }
            }
            .groupingBy { it }.eachCount()
        val porSetorAtivo = ativos.groupBy { it.setorAtual.nome }
        return PainelResponse(
            fluxosAtivos = ativos.size,
            aguardandoRecebimento = ativos.count { it.statusAtual == StatusFluxo.AGUARDANDO_RECEBIMENTO },
            emProcessamento = ativos.count { it.statusAtual == StatusFluxo.EM_PROCESSAMENTO },
            inicio = inicio,
            fim = fim,
            osAbertas = ordemRepository.countByCriadoEmGreaterThanEqualAndCriadoEmLessThan(de, ate).toInt(),
            concluidas = fluxoRepository.contarEncerradosEntre(StatusFluxo.ENCERRADA, de, ate).toInt(),
            canceladas = fluxoRepository.contarEncerradosEntre(StatusFluxo.CANCELADA, de, ate).toInt(),
            porSetor = (porSetorAtivo.keys + saidas.keys).sortedBy { it.ordinal }.map { setor ->
                val lista = porSetorAtivo[setor].orEmpty()
                ContagemSetor(
                    setor = setor,
                    aguardando = lista.count { it.statusAtual == StatusFluxo.AGUARDANDO_RECEBIMENTO },
                    emProcessamento = lista.count { it.statusAtual == StatusFluxo.EM_PROCESSAMENTO },
                    saidasNoPeriodo = saidas[setor] ?: 0
                )
            }
        )
    }

    // ----------------------------------------------------------------- helpers

    private companion object {
        /** A consulta sem busca mostra ate estas, das mais recentes; as antigas, pela busca. */
        const val LIMITE_DA_LISTA = 300

        /** Projeto que ainda nao terminou: enquanto houver um, a OS nao vai para o Patio. */
        val PROJETO_PENDENTE = setOf(StatusAgendamento.PROGRAMADO, StatusAgendamento.EM_PATIO, StatusAgendamento.EXECUTANDO)
    }

    private fun carregarFluxo(id: Int): FluxoOs = fluxoRepository.findById(id)
        .orElseThrow { NaoEncontradoException("Fluxo $id nao encontrado.") }

    private fun carregarUsuario(id: Int): Usuario = usuarioRepository.findById(id)
        .orElseThrow { NaoEncontradoException("Usuario $id nao encontrado.") }

    private fun garantirFluxoAtivo(fluxo: FluxoOs) {
        if (fluxo.statusAtual == StatusFluxo.CANCELADA) {
            throw RegraDeNegocioException("Este fluxo esta cancelado.")
        }
        if (fluxo.encerrado) {
            throw RegraDeNegocioException("Este fluxo ja foi concluido pelo Financeiro.")
        }
    }

    private fun visivelParaSetor(fluxo: FluxoOs, setorId: Int?): Boolean {
        if (setorId == null) return false
        if (fluxo.setorAtual.id == setorId) return true
        return eventoRepository.findByFluxoIdOrderByDataHoraAscIdAsc(fluxo.id!!)
            .any { it.setorDestino.id == setorId || it.setorOrigem?.id == setorId }
    }

    private fun garantirVisibilidade(fluxo: FluxoOs, autor: UsuarioAutenticado) {
        if (autor.podeVerTudo) return
        if (!visivelParaSetor(fluxo, matriz.setorDeTrabalho(autor)?.id)) {
            throw PermissaoNegadaException("Este fluxo nao passou pelo seu setor.")
        }
    }

    /** RNF01: eventos sao somente-insercao, nunca alterados ou removidos pela aplicacao. */
    private fun registrarEvento(
        fluxo: FluxoOs,
        origem: Setor?,
        destino: Setor,
        tipo: TipoEvento,
        usuario: Usuario,
        observacao: String?
    ) {
        eventoRepository.save(
            EventoMovimentacao(
                fluxo = fluxo,
                setorOrigem = origem,
                setorDestino = destino,
                tipoEvento = tipo,
                usuario = usuario,
                dataHora = Instant.now(),
                observacao = observacao?.trim()?.takeIf { it.isNotEmpty() }
            )
        )
    }
}
