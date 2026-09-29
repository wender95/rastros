package com.rastros.service

import com.rastros.domain.*
import com.rastros.repository.AdesivadorRepository
import com.rastros.repository.AgendamentoRepository
import com.rastros.repository.FluxoOsRepository
import com.rastros.repository.UsuarioRepository
import com.rastros.security.UsuarioAutenticado
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** A OS de um projeto da agenda, do ponto de vista de quem vai adesivar. */
data class OsDoCarroResponse(
    val osId: Int,
    val numeroOsErp: String,
    val cliente: String?,
    /** O que a OS manda fazer, como veio do ERP. */
    val servico: String?,
    /** O fluxo que interessa a Frota (o que esta nela, ou o que ainda esta a caminho). */
    val fluxoId: Int?,
    val setorAtual: SetorNome?,
    val statusFluxo: StatusFluxo?,
    val recebidoPor: String?,
    /** Recebida na Frota: pode voltar para o setor de onde veio (ex.: material com defeito). */
    val devolverPara: SetorNome? = null
)

data class CarroDoDiaResponse(
    val agendamentoId: Long,
    val descricao: String,
    val tipo: TipoAgendamento,
    val status: StatusAgendamento,
    /** Status criado na agenda que o card mostra (nome e cor da legenda). */
    val etiquetaId: Long? = null,
    /** Horario deste projeto NESTE dia (um servico longo aparece em cada dia que ocupa). */
    val horarioInicio: String,
    val horarioFim: String,
    val comecaEm: LocalDate,
    val terminaEm: LocalDate,
    val horasEstimadas: BigDecimal,
    val vendedorCodigo: String?,
    val observacao: String?,
    val os: OsDoCarroResponse?,
    /** Quando e por quem o projeto foi iniciado na propria agenda. */
    val iniciadoEm: Instant? = null,
    val iniciadoPor: String? = null,
    /** Coluna Noturno: os adesivadores atribuidos ao servico. */
    val atribuidos: List<String> = emptyList(),
    val concluidoEm: Instant? = null,
    /** O projeto em que o adesivador esta trabalhando agora: o ultimo que ele iniciou. */
    val atual: Boolean = false,
    /** Botoes da propria agenda (so o dono da coluna os ve). */
    val podeIniciar: Boolean = false,
    val podeConcluir: Boolean = false,
    /** Pausado agora: desde quando. */
    val pausadoDesde: Instant? = null,
    /** Todas as pausas do servico, da primeira para a ultima. */
    val pausas: List<PausaResponse> = emptyList(),
    val podePausar: Boolean = false,
    val podeRetomar: Boolean = false
)

/**
 * O que o adesivador registrou num servico, olhando **todas as copias** dele. Uma copia
 * criada depois do inicio (o card estendido para o dia seguinte) nao tem o inicio gravado;
 * o servico, sim.
 */
data class Execucao(
    val iniciadoEm: Instant?,
    val iniciadoPor: Usuario?,
    val inicioRegistradoPor: Usuario?,
    /** So com o servico concluido: foi o adesivador (ou quem agiu por ele) que concluiu. */
    val concluidoEm: Instant?,
    val conclusaoRegistradaPor: Usuario?
) {
    companion object {
        fun de(servico: Agendamento, copias: List<Agendamento>?): Execucao {
            val partes = copias?.takeIf { it.isNotEmpty() } ?: listOf(servico)
            val inicio = partes.filter { it.iniciadoEm != null }.minByOrNull { it.iniciadoEm!! }
            val conclusao = partes.filter { it.concluidoEm != null }.maxByOrNull { it.concluidoEm!! }
                ?.takeIf { servico.status == StatusAgendamento.CONCLUIDO }
            return Execucao(
                iniciadoEm = inicio?.iniciadoEm,
                iniciadoPor = inicio?.iniciadoPor ?: partes.firstNotNullOfOrNull { it.iniciadoPor },
                inicioRegistradoPor = inicio?.inicioRegistradoPor,
                concluidoEm = conclusao?.concluidoEm,
                conclusaoRegistradaPor = conclusao?.conclusaoRegistradaPor
            )
        }
    }
}

data class MinhaAgendaResponse(
    val adesivadorId: Int,
    val adesivador: String,
    val data: LocalDate,
    val carros: List<CarroDoDiaResponse>,
    /** A coluna Noturno (o painel so a mostra quando tem servico no dia). */
    val noturno: Boolean = false
)

/** O que aconteceu ao iniciar ou concluir um projeto - a tela monta a frase. */
data class ProjetoResponse(
    val agendamentoId: Long,
    val descricao: String,
    val status: StatusAgendamento,
    val numeroOsErp: String?,
    /** OS recebidas na Frota agora, em nome de quem iniciou. */
    val recebidas: Int,
    /** OS que foram da Frota para o Patio agora. */
    val paraOPatio: Int,
    /** Outros projetos da mesma OS que ainda seguram a ida ao Patio. */
    val faltam: List<String>,
    /** A OS ainda nao chegou na Frota: sera recebida (ou vai para o Patio) quando chegar. */
    val osACaminho: Boolean
)

/**
 * "Minha agenda": o adesivador ve so a coluna dele, dia a dia, e trabalha por **projetos**
 * - inicia e conclui. Quem move a OS dentro da Frota e o projeto (veja
 * [FluxoService.acompanharProjetos]); ele nao recebe nem despacha.
 *
 * A coluna e dele pelo vinculo explicito com a conta ou, sem ele, pelo nome (a coluna
 * ANDRE e do usuario Andre). O painel operacional mostra a mesma agenda de cada um.
 */
@Service
class MinhaAgendaService(
    private val adesivadorRepository: AdesivadorRepository,
    private val agendamentoRepository: AgendamentoRepository,
    private val fluxoRepository: FluxoOsRepository,
    private val usuarioRepository: UsuarioRepository,
    private val fluxoService: FluxoService,
    private val pausas: PausasDosProjetos
) {

    /** A coluna da agenda desta pessoa, ou nulo se ela nao adesiva. */
    fun colunaDe(usuario: Usuario): Adesivador? {
        val colunas = adesivadorRepository.findByAtivoTrueOrderByOrdemAsc().filter { it.tipo == TipoColunaAgenda.ADESIVADOR }
        return colunas.firstOrNull { it.usuario?.id == usuario.id }
            ?: colunas.firstOrNull { it.usuario == null && it.nome.trim().equals(usuario.nome.trim(), ignoreCase = true) }
    }

    @Transactional(readOnly = true)
    fun doDia(dia: LocalDate, autor: UsuarioAutenticado): MinhaAgendaResponse {
        val coluna = colunaDe(carregarUsuario(autor))
            ?: throw NaoEncontradoException("Voce nao tem uma coluna na agenda dos adesivadores.")
        // Frota entre os setores dela (mesmo que o principal seja outro, como o Recorte).
        val daFrota = autor.perfil == PerfilNome.OPERACIONAL && SetorNome.FROTA in autor.setores
        return agendaDaColuna(coluna, dia, dono = true, daFrota = daFrota)
    }

    /** A agenda do dia de cada adesivador, como cada um a ve - o espelho do painel. */
    @Transactional(readOnly = true)
    fun agendasDoDia(dia: LocalDate): List<MinhaAgendaResponse> =
        adesivadorRepository.findByAtivoTrueOrderByOrdemAsc()
            .filter { it.tipo == TipoColunaAgenda.ADESIVADOR || it.tipo == TipoColunaAgenda.NOTURNO }
            .map { agendaDaColuna(it, dia, dono = false, daFrota = false) }

    /**
     * Os projetos da coluna no dia, na ordem do dia. O painel e a Minha agenda sao o
     * **reflexo da agenda**: so entra o que a agenda poe neste dia.
     *
     * - Iniciado ontem e ainda na agenda de hoje (o escritorio estendeu o card): continua
     *   em andamento e no destaque "Agora", ate o adesivador concluir ou a agenda marcar
     *   concluido. O inicio registrado vale para todas as copias do servico.
     * - Concluido pelo adesivador: continua no dia, marcado como concluido.
     * - Concluido direto na agenda (o adesivador nao concluiu): sai do painel e da Minha
     *   agenda; no relatorio a conclusao aparece como "?".
     */
    private fun agendaDaColuna(coluna: Adesivador, dia: LocalDate, dono: Boolean, daFrota: Boolean): MinhaAgendaResponse {
        // Quem comecou antes e ainda ocupa este dia tambem aparece.
        val doDia = agendamentoRepository
            .doPeriodoDoAdesivador(dia.minusWeeks(4), dia, coluna.id!!)
            .mapNotNull { a ->
                val hoje = a.posicoesOcupadas
                    .filter { FaixasDoDia.diaUtilAFrente(a.data, it / FaixasDoDia.QUANTIDADE) == dia }
                    .map { it % FaixasDoDia.QUANTIDADE + 1 }
                if (hoje.isEmpty()) null else a to hoje
            }
            .sortedBy { (_, faixas) -> faixas.first() }
        // Copias do mesmo servico (a alca do canto, o colar) aparecem como um card so no dia.
        val chave = { a: Agendamento -> a.grupoId ?: a.id }
        val doDiaUnico = doDia.groupBy { chave(it.first) }.values
            .map { partes -> partes.first().first to partes.flatMap { it.second }.distinct().sorted() }
            .sortedBy { (_, faixas) -> faixas.first() }

        // Todas as copias dos servicos do dia: o servico vai ate o ultimo dia da ultima copia,
        // e o que o adesivador registrou (inicio, conclusao) vale para o servico inteiro.
        val grupos = doDiaUnico.mapNotNull { it.first.grupoId }.toSet()
        val partesPorServico = if (grupos.isEmpty()) emptyMap()
        else agendamentoRepository.findAllById(grupos).plus(agendamentoRepository.findByGrupoIdIn(grupos))
            .distinctBy { it.id }.groupBy { chave(it) }
        val execucoes = doDiaUnico.associate { (a, _) -> chave(a) to Execucao.de(a, partesPorServico[chave(a)]) }
        fun execucao(a: Agendamento) = execucoes.getValue(chave(a))

        // Concluido direto na agenda, sem o adesivador concluir: sai do painel e da Minha agenda.
        val projetos = doDiaUnico.filterNot { (a, _) ->
            a.ehServico && a.status == StatusAgendamento.CONCLUIDO && execucao(a).concluidoEm == null
        }

        val pausasPorServico = pausas.dosServicos(projetos.map { pausas.chave(it.first) })
        fun pausadoAgora(a: Agendamento) = pausasPorServico[pausas.chave(a)].orEmpty().any { it.fim == null }

        // O destaque "Agora" do painel e de um servico so: entre os que estao em execucao
        // (pausado e concluido nao), o ultimo que entrou em execucao - iniciado ou retomado.
        // Iniciar outro manda o anterior de volta para a lista, sem mudar o status dele.
        fun emExecucao(a: Agendamento) =
            a.status == StatusAgendamento.EXECUTANDO && execucao(a).iniciadoPor != null && !pausadoAgora(a)
        fun entrouEmExecucao(a: Agendamento): Instant =
            listOfNotNull(execucao(a).iniciadoEm, pausasPorServico[pausas.chave(a)].orEmpty().mapNotNull { it.fim }.maxOrNull())
                .maxOrNull() ?: Instant.MIN
        val atual = projetos.map { it.first }.filter(::emExecucao).maxByOrNull(::entrouEmExecucao)

        val ultimoDiaDoServico = partesPorServico.mapValues { (_, partes) -> partes.maxOf { it.ultimoDia } }

        val osIds = projetos.mapNotNull { it.first.ordemServico?.id }.toSet()
        val fluxosPorOs = if (osIds.isEmpty()) emptyMap()
        else fluxoRepository.findByOrdemServicoIdInOrderByIdAsc(osIds).groupBy { it.ordemServico.id!! }

        return MinhaAgendaResponse(
            adesivadorId = coluna.id!!,
            adesivador = coluna.nome,
            data = dia,
            noturno = coluna.tipo == TipoColunaAgenda.NOTURNO,
            carros = projetos.map { (a, faixas) ->
                val servico = a.ehServico
                CarroDoDiaResponse(
                    agendamentoId = a.id!!,
                    descricao = a.descricao,
                    tipo = a.tipo ?: TipoAgendamento.SERVICO,
                    status = a.status,
                    etiquetaId = a.etiquetaId,
                    horarioInicio = FaixasDoDia.de(faixas.first()).inicio,
                    horarioFim = FaixasDoDia.de(faixas.last()).fim,
                    comecaEm = a.data,
                    terminaEm = ultimoDiaDoServico[chave(a)] ?: a.ultimoDia,
                    horasEstimadas = a.horas,
                    vendedorCodigo = a.vendedorCodigo,
                    observacao = a.observacao,
                    os = a.ordemServico?.let { os ->
                        osDoProjeto(os, fluxosPorOs[os.id].orEmpty(), daFrota && a.status == StatusAgendamento.EXECUTANDO)
                    },
                    iniciadoEm = execucao(a).iniciadoEm,
                    iniciadoPor = execucao(a).iniciadoPor?.nome,
                    atribuidos = a.atribuidos.sortedBy { it.ordem }.map { it.nome },
                    concluidoEm = execucao(a).concluidoEm,
                    atual = a.id == atual?.id,
                    podeIniciar = dono && servico && a.status in PODE_INICIAR,
                    podeConcluir = dono && servico && a.status == StatusAgendamento.EXECUTANDO,
                    pausas = pausasPorServico[pausas.chave(a)].orEmpty(),
                    pausadoDesde = pausasPorServico[pausas.chave(a)].orEmpty().lastOrNull { it.fim == null }?.inicio,
                    podePausar = dono && servico && a.status == StatusAgendamento.EXECUTANDO &&
                        pausasPorServico[pausas.chave(a)].orEmpty().none { it.fim == null },
                    podeRetomar = dono && servico && a.status == StatusAgendamento.EXECUTANDO &&
                        pausasPorServico[pausas.chave(a)].orEmpty().any { it.fim == null }
                )
            }
        )
    }

    private fun osDoProjeto(os: OrdemServico, fluxos: List<FluxoOs>, podeDevolver: Boolean): OsDoCarroResponse {
        val ativos = fluxos.filter { !it.encerrado }
        val fluxo = ativos.firstOrNull { it.setorAtual.nome == SetorNome.FROTA } ?: ativos.firstOrNull() ?: fluxos.lastOrNull()
        // Devolver (material com defeito) so com a OS recebida na Frota e o projeto em andamento.
        val naFrotaRecebida = fluxo != null && !fluxo.encerrado &&
            fluxo.setorAtual.nome == SetorNome.FROTA && fluxo.statusAtual == StatusFluxo.EM_PROCESSAMENTO
        return OsDoCarroResponse(
            osId = os.id!!,
            numeroOsErp = os.numeroOsErp,
            cliente = os.cliente,
            servico = os.servico,
            fluxoId = fluxo?.id,
            setorAtual = fluxo?.setorAtual?.nome,
            statusFluxo = fluxo?.statusAtual,
            recebidoPor = fluxo?.recebidoPor?.nome,
            devolverPara = if (podeDevolver && naFrotaRecebida) fluxo?.setorAnterior?.nome else null
        )
    }

    // ---------------------------------------------------------------- projetos

    /**
     * O adesivador comeca o projeto - com ou sem a OS ter chegado na Frota. Se ela ja esta
     * la, e recebida agora em nome dele; se nao, e recebida sozinha quando chegar.
     */
    @Transactional
    fun iniciar(id: Long, autor: UsuarioAutenticado): ProjetoResponse {
        val (usuario, projeto) = projetoDoAdesivador(id, autor)
        return iniciarProjeto(projeto, dono = usuario, quem = usuario)
    }

    /**
     * [dono] e o adesivador da coluna: e em nome dele que a OS e recebida na Frota. [quem] e
     * quem registrou (ele mesmo, ou alguem agindo pelo painel) e aparece no relatorio.
     */
    private fun iniciarProjeto(projeto: Agendamento, dono: Usuario?, quem: Usuario): ProjetoResponse {
        if (projeto.status !in PODE_INICIAR) {
            throw RegraDeNegocioException("\"${projeto.descricao}\" ja esta ${rotulo(projeto.status)}.")
        }
        val agora = Instant.now()
        partesDe(projeto).forEach {
            it.status = StatusAgendamento.EXECUTANDO
            it.etiquetaId = null
            if (it.iniciadoEm == null) {
                it.iniciadoEm = agora
                it.inicioRegistradoPor = quem
            }
            it.iniciadoPor = dono ?: quem
            it.concluidoEm = null
            it.conclusaoRegistradaPor = null
            it.atualizadoEm = agora
            agendamentoRepository.save(it)
        }
        return resposta(projeto)
    }

    /**
     * O adesivador termina o projeto. Terminados todos os projetos da OS, ela vai da Frota
     * para o Patio em nome dele; se o material ainda nao chegou, vai assim que chegar.
     */
    @Transactional
    fun concluir(id: Long, autor: UsuarioAutenticado): ProjetoResponse {
        val (usuario, projeto) = projetoDoAdesivador(id, autor)
        return concluirProjeto(projeto, dono = usuario, quem = usuario)
    }

    private fun concluirProjeto(projeto: Agendamento, dono: Usuario?, quem: Usuario): ProjetoResponse {
        if (projeto.status != StatusAgendamento.EXECUTANDO) {
            throw RegraDeNegocioException("Inicie \"${projeto.descricao}\" antes de concluir.")
        }
        val agora = Instant.now()
        // Concluir um projeto pausado fecha a pausa na hora da conclusao.
        pausas.fecharAbertas(projeto, agora)
        partesDe(projeto).forEach {
            it.status = StatusAgendamento.CONCLUIDO
            it.etiquetaId = null
            if (it.iniciadoEm == null) {
                it.iniciadoEm = agora
                it.inicioRegistradoPor = quem
            }
            if (it.iniciadoPor == null) it.iniciadoPor = dono ?: quem
            it.concluidoEm = agora
            it.conclusaoRegistradaPor = quem
            it.atualizadoEm = agora
            agendamentoRepository.save(it)
        }
        return resposta(projeto)
    }

    /**
     * O adesivador para o projeto por um tempo (falta material, outra urgencia...). Cada
     * pausa fica com o horario de inicio e de fim, e aparece no relatorio.
     */
    @Transactional
    fun pausar(id: Long, autor: UsuarioAutenticado): ProjetoResponse {
        val (usuario, projeto) = projetoDoAdesivador(id, autor)
        return pausarProjeto(projeto, quem = usuario)
    }

    private fun pausarProjeto(projeto: Agendamento, quem: Usuario): ProjetoResponse {
        if (projeto.status != StatusAgendamento.EXECUTANDO) {
            throw RegraDeNegocioException("So da para pausar um projeto em andamento.")
        }
        pausas.pausar(projeto, quem)
        return semEfeitoNaOs(projeto)
    }

    @Transactional
    fun retomar(id: Long, autor: UsuarioAutenticado): ProjetoResponse {
        val (usuario, projeto) = projetoDoAdesivador(id, autor)
        pausas.retomar(projeto, usuario)
        return semEfeitoNaOs(projeto)
    }

    /**
     * Pelo painel (botao direito no card), quem tem a permissao "Agir pelo painel" inicia,
     * pausa, retoma ou conclui o projeto de um adesivador - com as mesmas regras da Minha
     * agenda. A OS anda em nome do adesivador dono da coluna; o nome de quem clicou fica
     * registrado e aparece no relatorio.
     */
    @Transactional
    fun agirPeloPainel(id: Long, acao: String, autor: UsuarioAutenticado): ProjetoResponse {
        if (!autor.tem(SecaoDoSistema.PAINEL_ACOES)) {
            throw PermissaoNegadaException("Voce nao tem permissao para agir pelo painel.")
        }
        val projeto = agendamentoRepository.findById(id)
            .orElseThrow { NaoEncontradoException("Projeto $id nao encontrado.") }
        if (!projeto.ehServico) throw RegraDeNegocioException("Um bloqueio da agenda nao e projeto.")
        val quem = carregarUsuario(autor)
        val dono = donoDaColuna(projeto.adesivador)
        return when (acao) {
            "iniciar" -> iniciarProjeto(projeto, dono, quem)
            "pausar" -> pausarProjeto(projeto, quem)
            "retomar" -> pausas.retomar(projeto, quem).let { semEfeitoNaOs(projeto) }
            "concluir" -> concluirProjeto(projeto, dono, quem)
            else -> throw RegraDeNegocioException("Acao desconhecida: $acao.")
        }
    }

    /** O adesivador da coluna (o inverso de [colunaDe]): o usuario ligado a ela, ou o de mesmo nome. */
    private fun donoDaColuna(coluna: Adesivador): Usuario? =
        coluna.usuario ?: usuarioRepository.findAllByOrderByNomeAsc()
            .firstOrNull { it.ativo && it.nome.trim().equals(coluna.nome.trim(), ignoreCase = true) }

    /** Pausar e retomar nao mexem na OS: a resposta so diz em que projeto foi. */
    private fun semEfeitoNaOs(projeto: Agendamento) = ProjetoResponse(
        agendamentoId = projeto.id!!,
        descricao = projeto.descricao,
        status = projeto.status,
        numeroOsErp = projeto.ordemServico?.numeroOsErp,
        recebidas = 0,
        paraOPatio = 0,
        faltam = emptyList(),
        osACaminho = false
    )

    private fun resposta(projeto: Agendamento): ProjetoResponse {
        val os = projeto.ordemServico
        val efeito = os?.let { fluxoService.acompanharProjetos(it) } ?: ProjetosDaOs()
        return ProjetoResponse(
            agendamentoId = projeto.id!!,
            descricao = projeto.descricao,
            status = projeto.status,
            numeroOsErp = os?.numeroOsErp,
            recebidas = efeito.recebidas,
            paraOPatio = efeito.paraOPatio,
            // As partes do proprio projeto nao contam como "outro projeto".
            faltam = efeito.faltam.filter { it != projeto.descricao },
            osACaminho = efeito.osACaminho
        )
    }

    /** O projeto, se for da coluna de quem pediu; so servico (bloqueio nao se inicia). */
    private fun projetoDoAdesivador(id: Long, autor: UsuarioAutenticado): Pair<Usuario, Agendamento> {
        val usuario = carregarUsuario(autor)
        val coluna = colunaDe(usuario)
            ?: throw NaoEncontradoException("Voce nao tem uma coluna na agenda dos adesivadores.")
        val projeto = agendamentoRepository.findById(id)
            .orElseThrow { NaoEncontradoException("Projeto $id nao encontrado.") }
        if (projeto.adesivador.id != coluna.id) {
            throw PermissaoNegadaException("Este projeto e da agenda de ${projeto.adesivador.nome}.")
        }
        if (!projeto.ehServico) throw RegraDeNegocioException("Um bloqueio da agenda nao e projeto.")
        return usuario to projeto
    }

    /** As partes do mesmo servico andam juntas: o estado e de todas. */
    private fun partesDe(projeto: Agendamento): List<Agendamento> =
        projeto.grupoId?.let { agendamentoRepository.findByGrupoIdOrderByDataAscSlotInicioAsc(it) }
            ?.takeIf { lista -> lista.any { it.id == projeto.id } }
            ?: listOf(projeto)

    private fun carregarUsuario(autor: UsuarioAutenticado): Usuario =
        usuarioRepository.findById(autor.id).orElseThrow { NaoEncontradoException("Usuario nao encontrado.") }

    private fun rotulo(status: StatusAgendamento) = when (status) {
        StatusAgendamento.EXECUTANDO -> "em andamento"
        StatusAgendamento.CONCLUIDO -> "concluido"
        StatusAgendamento.EXTERNO -> "marcado como externo"
        else -> status.name.lowercase()
    }

    private companion object {
        /** Aguardando (ou marcado como "nao veio", e o carro apareceu): da para iniciar. */
        val PODE_INICIAR = setOf(StatusAgendamento.PROGRAMADO, StatusAgendamento.EM_PATIO, StatusAgendamento.NAO_VEIO)
    }
}
