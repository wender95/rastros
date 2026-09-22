package com.ostracker.service

import com.ostracker.api.*
import com.ostracker.domain.*
import com.ostracker.repository.*
import com.ostracker.security.UsuarioAutenticado
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class FluxoService(
    private val ordemRepository: OrdemServicoRepository,
    private val fluxoRepository: FluxoOsRepository,
    private val eventoRepository: EventoMovimentacaoRepository,
    private val setorRepository: SetorRepository,
    private val usuarioRepository: UsuarioRepository,
    private val matriz: MatrizTransicaoService,
    private val agendaDaFrota: AgendaDaFrotaService
) {

    // ---------------------------------------------------------------- criacao

    @Transactional
    fun criarOrdem(req: NovaOrdemRequest, autor: UsuarioAutenticado): OrdemResponse {
        garantirPodeAbrirOs(autor)
        val usuario = carregarUsuario(autor.id)
        val iniciaisValidos = matriz.setoresIniciais().mapNotNull { it.id }.toSet()

        val os = OrdemServico(
            numeroOsErp = req.numeroOsErp.trim(),
            cliente = req.cliente?.trim()?.takeIf { it.isNotEmpty() },
            criadoPor = usuario
        )
        ordemRepository.save(os)

        req.fluxos.forEach { novo -> criarFluxo(os, novo, usuario, iniciaisValidos) }

        return os.paraResponse(fluxoRepository.findByOrdemServicoIdOrderByIdAsc(os.id!!))
    }

    @Transactional
    fun adicionarFluxo(osId: Int, novo: NovoFluxoRequest, autor: UsuarioAutenticado): OrdemResponse {
        garantirPodeAbrirOs(autor)
        val os = ordemRepository.findById(osId)
            .orElseThrow { NaoEncontradoException("Ordem de servico $osId nao encontrada.") }
        if (os.cancelada) throw RegraDeNegocioException("A OS ${os.numeroOsErp} esta cancelada.")

        criarFluxo(os, novo, carregarUsuario(autor.id), matriz.setoresIniciais().mapNotNull { it.id }.toSet())
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

        val usuario = carregarUsuario(autor.id)
        fluxo.statusAtual = StatusFluxo.EM_PROCESSAMENTO
        fluxo.recebidoPor = usuario
        fluxo.recebidoEm = Instant.now()
        fluxoRepository.save(fluxo)

        registrarEvento(
            fluxo, fluxo.setorAnterior, fluxo.setorAtual, TipoEvento.RECEBIMENTO, usuario, req.observacao
        )
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
    }

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
        autor.perfil == PerfilNome.VENDEDOR || autor.perfil == PerfilNome.DIRETORIA || autor.perfil == PerfilNome.ADMIN

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
            autor.podeVerTudo -> fluxoRepository.findAllByOrderByIdDesc()
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
        ordemRepository.findAll()
            .filter { !it.cancelada }
            .mapNotNull { os ->
                val ativos = fluxoRepository.findByOrdemServicoIdOrderByIdAsc(os.id!!)
                    .filter { !it.encerrado && it.statusAtual != StatusFluxo.CANCELADA }
                if (ativos.isEmpty()) null
                else OrdemAbertaResponse(
                    id = os.id!!,
                    numeroOsErp = os.numeroOsErp,
                    cliente = os.cliente,
                    fluxosAtivos = ativos.size,
                    setores = ativos.map { it.setorAtual.nome }.distinct()
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
        val fluxos = if (autor.podeVerTudo || autor.setorId == null) {
            fluxoRepository.findAll()
        } else {
            fluxoRepository.findVisiveisParaSetor(autor.setorId)
        }
        val zona = java.time.ZoneId.systemDefault()
        val de = inicio.atStartOfDay(zona).toInstant()
        val ate = fim.plusDays(1).atStartOfDay(zona).toInstant()
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

    // ----------------------------------------------------------------- helpers

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
