package com.rastros.config

import com.rastros.domain.*
import com.rastros.repository.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * Carga inicial: perfis, setores, matriz de transicao, usuarios de demonstracao
 * e massa de dados ficticios (Fase 1 do roadmap).
 *
 * Roda apenas quando o banco esta vazio. Com rastros.carga-demo desligado (producao),
 * cria so a estrutura e um administrador com senha provisoria.
 */
@Component
class CargaInicial(
    private val perfilRepository: PerfilRepository,
    private val setorRepository: SetorRepository,
    private val usuarioRepository: UsuarioRepository,
    private val transicaoRepository: TransicaoPermitidaRepository,
    private val ordemRepository: OrdemServicoRepository,
    private val fluxoRepository: FluxoOsRepository,
    private val eventoRepository: EventoMovimentacaoRepository,
    private val passwordEncoder: PasswordEncoder,
    private val configuracaoAgenda: com.rastros.service.ConfiguracaoAgendaService,
    private val adesivadorRepository: AdesivadorRepository,
    private val geradorFicticio: GeradorFicticio,
    @Value("\${rastros.carga-demo}") private val cargaDemo: Boolean,
    /** Demonstracao completa: equipe da Frota, agenda e OS de umas seis semanas (GeradorFicticio). */
    @Value("\${rastros.demo-completa:false}") private val demoCompleta: Boolean
) : CommandLineRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Matriz de transicao de docs/06. Origem nula = entrada comercial (Vendas). */
    private val matriz: List<Pair<SetorNome?, SetorNome>> = listOf(
        null to SetorNome.CRIACAO,
        null to SetorNome.RECORTE,
        null to SetorNome.IMPRESSAO,
        null to SetorNome.PREPARACAO,
        null to SetorNome.FROTA,
        null to SetorNome.ACABAMENTO,

        SetorNome.CRIACAO to SetorNome.RECORTE,
        SetorNome.CRIACAO to SetorNome.IMPRESSAO,
        SetorNome.CRIACAO to SetorNome.PREPARACAO,
        SetorNome.CRIACAO to SetorNome.FROTA,
        SetorNome.CRIACAO to SetorNome.ACABAMENTO,

        SetorNome.IMPRESSAO to SetorNome.RECORTE,
        SetorNome.IMPRESSAO to SetorNome.PREPARACAO,
        SetorNome.IMPRESSAO to SetorNome.FROTA,
        SetorNome.IMPRESSAO to SetorNome.ACABAMENTO,

        SetorNome.RECORTE to SetorNome.PREPARACAO,
        SetorNome.RECORTE to SetorNome.FROTA,
        SetorNome.RECORTE to SetorNome.ACABAMENTO,

        SetorNome.PREPARACAO to SetorNome.FROTA,
        SetorNome.ACABAMENTO to SetorNome.PRATELEIRA,
        SetorNome.FROTA to SetorNome.PATIO,
        SetorNome.PRATELEIRA to SetorNome.FINANCEIRO,
        SetorNome.PATIO to SetorNome.FINANCEIRO
    )

    @Transactional
    override fun run(vararg args: String) {
        if (usuarioRepository.count() == 0L) {
            carregar()
            // Na demonstracao, os vendedores da agenda saem dos usuarios ficticios do comercial.
            if (cargaDemo) configuracaoAgenda.semearVendedoresDosUsuarios()
            if (cargaDemo && demoCompleta) {
                montarEquipeDaFrota()
                geradorFicticio.gerarTudo()
            }
        }
        marcarSenhaPadraoComoProvisoria()
    }

    /**
     * Quem ainda entra com a senha de demonstracao (123456) passa a ter de troca-la no
     * proximo acesso. Roda a cada partida e so acha quem ainda nao trocou.
     */
    private fun marcarSenhaPadraoComoProvisoria() {
        val comSenhaPadrao = usuarioRepository.findAll()
            .filter { !it.trocarSenha && passwordEncoder.matches(SENHA_DEMONSTRACAO, it.senhaHash) }
        comSenhaPadrao.forEach { it.trocarSenha = true }
        usuarioRepository.saveAll(comSenhaPadrao)
        if (comSenhaPadrao.isNotEmpty()) {
            log.info("{} usuario(s) com a senha padrao terao de troca-la no proximo acesso.", comSenhaPadrao.size)
        }
    }

    private fun carregar() {
        log.info("Banco vazio: aplicando carga inicial do RastrOS...")

        // Um perfil pode ja ter vindo de uma migracao (o Financeiro veio na V6).
        val perfis = PerfilNome.entries.associateWith { perfilRepository.findByNome(it)
                ?: perfilRepository.save(Perfil(nome = it, secoes = SecaoDoSistema.padraoDo(it).toMutableSet())) }
        val setores = SetorNome.entries.associateWith { setorRepository.save(Setor(nome = it)) }
        matriz.forEach { (origem, destino) ->
            transicaoRepository.save(TransicaoPermitida(setorOrigem = origem, setorDestino = destino))
        }

        if (!cargaDemo) {
            // Instalacao de producao: nada ficticio, so um administrador para cadastrar o resto.
            val senhaInicial = senhaAleatoria()
            usuarioRepository.save(
                Usuario(
                    nome = "Administrador",
                    login = "admin",
                    email = "admin@rastros.cloud",
                    senhaHash = passwordEncoder.encode(senhaInicial),
                    perfil = perfis[PerfilNome.ADMIN]!!,
                    trocarSenha = true
                )
            )
            log.warn(
                "Primeiro acesso: entre com o usuario admin e a senha provisoria {} - " +
                    "o sistema pede a troca logo no login.",
                senhaInicial
            )
            return
        }

        val senha = passwordEncoder.encode(SENHA_DEMONSTRACAO)
        fun usuario(nome: String, email: String, perfil: PerfilNome, setor: SetorNome? = null) =
            usuarioRepository.save(
                Usuario(
                    nome = nome,
                    login = email.substringBefore('@'),
                    email = email,
                    senhaHash = senha,
                    perfil = perfis[perfil]!!,
                    setor = setor?.let { setores[it] },
                    trocarSenha = true
                )
            )

        val admin = usuario("Ana Admin", "admin@rastros.cloud", PerfilNome.ADMIN)
        val vendedor = usuario("Marina Vendas", "vendedor@rastros.cloud", PerfilNome.VENDEDOR)
        val vendedor2 = usuario("Caio Comercial", "vendedor2@rastros.cloud", PerfilNome.VENDEDOR)
        val diretoria = usuario("Ricardo Diretoria", "diretoria@rastros.cloud", PerfilNome.DIRETORIA)

        val opCriacao = usuario("Bruno Criacao", "criacao@rastros.cloud", PerfilNome.OPERACIONAL, SetorNome.CRIACAO)
        val opImpressao = usuario("Diego Impressao", "impressao@rastros.cloud", PerfilNome.OPERACIONAL, SetorNome.IMPRESSAO)
        val opRecorte = usuario("Elisa Recorte", "recorte@rastros.cloud", PerfilNome.OPERACIONAL, SetorNome.RECORTE)
        val opPreparacao = usuario("Fabio Preparacao", "preparacao@rastros.cloud", PerfilNome.OPERACIONAL, SetorNome.PREPARACAO)
        val opAcabamento = usuario("Gisele Acabamento", "acabamento@rastros.cloud", PerfilNome.OPERACIONAL, SetorNome.ACABAMENTO)
        val opFrota = usuario("Heitor Frota", "frota@rastros.cloud", PerfilNome.OPERACIONAL, SetorNome.FROTA)
        val financeiro = usuario("Fernanda Financeiro", "financeiro@rastros.cloud", PerfilNome.FINANCEIRO)
        log.info("Usuarios de demonstracao criados (senha padrao: 123456). Admin: {}", admin.login)
        // Na demonstracao completa as OS vem do gerador, coerentes com a agenda.
        if (demoCompleta) return

        val agora = Instant.now()
        fun ha(horas: Long): Instant = agora.minus(Duration.ofHours(horas))

        val operadores = mapOf(
            SetorNome.CRIACAO to opCriacao,
            SetorNome.IMPRESSAO to opImpressao,
            SetorNome.RECORTE to opRecorte,
            SetorNome.PREPARACAO to opPreparacao,
            SetorNome.ACABAMENTO to opAcabamento,
            SetorNome.FROTA to opFrota,
            SetorNome.FINANCEIRO to financeiro
        )

        /**
         * Cria um fluxo e reproduz sua trajetoria, gravando os eventos com carimbos de tempo
         * coerentes (criacao -> recebimento -> despacho em cada setor).
         */
        fun semear(
            os: OrdemServico,
            identificador: String,
            etapas: List<SetorNome>,
            horasInicio: Long,
            passoHoras: Long = 3,
            pararEm: Int = etapas.size,
            recebidoNoUltimo: Boolean = false,
            saidaPor: Usuario? = null
        ): FluxoOs {
            var relogio = ha(horasInicio)
            val fluxo = fluxoRepository.save(
                FluxoOs(
                    ordemServico = os,
                    identificadorFluxo = identificador,
                    setorAtual = setores[etapas.first()]!!,
                    entrouNoSetorEm = relogio
                )
            )
            eventoRepository.save(
                EventoMovimentacao(
                    fluxo = fluxo,
                    setorOrigem = null,
                    setorDestino = setores[etapas.first()]!!,
                    tipoEvento = TipoEvento.CRIACAO,
                    usuario = os.criadoPor,
                    dataHora = relogio,
                    observacao = "Fluxo aberto pelo comercial."
                )
            )

            for (indice in 0 until pararEm) {
                val setorAtual = etapas[indice]
                val ehUltimoVisitado = indice == pararEm - 1
                val executor = operadores[setorAtual] ?: saidaPor ?: vendedor

                // Recebimento (locais fisicos nao possuem essa etapa).
                if (!setorAtual.localFisico) {
                    relogio = relogio.plus(Duration.ofMinutes(35))
                    if (!ehUltimoVisitado || recebidoNoUltimo) {
                        eventoRepository.save(
                            EventoMovimentacao(
                                fluxo = fluxo,
                                setorOrigem = fluxo.setorAnterior,
                                setorDestino = setores[setorAtual]!!,
                                tipoEvento = TipoEvento.RECEBIMENTO,
                                usuario = executor,
                                dataHora = relogio
                            )
                        )
                        fluxo.statusAtual = StatusFluxo.EM_PROCESSAMENTO
                        fluxo.recebidoPor = executor
                        fluxo.recebidoEm = relogio
                    }
                }

                // O Financeiro recebe e conclui: so assim o fluxo termina.
                if (ehUltimoVisitado && setorAtual == SetorNome.FINANCEIRO && recebidoNoUltimo) {
                    relogio = relogio.plus(Duration.ofHours(passoHoras))
                    eventoRepository.save(
                        EventoMovimentacao(
                            fluxo = fluxo,
                            setorOrigem = setores[setorAtual]!!,
                            setorDestino = setores[setorAtual]!!,
                            tipoEvento = TipoEvento.CONCLUSAO,
                            usuario = executor,
                            dataHora = relogio
                        )
                    )
                    fluxo.statusAtual = StatusFluxo.ENCERRADA
                    fluxo.encerrado = true
                    fluxo.encerradoEm = relogio
                }

                if (ehUltimoVisitado) break

                // Despacho para a proxima etapa.
                val proximo = etapas[indice + 1]
                relogio = relogio.plus(Duration.ofHours(passoHoras))
                eventoRepository.save(
                    EventoMovimentacao(
                        fluxo = fluxo,
                        setorOrigem = setores[setorAtual]!!,
                        setorDestino = setores[proximo]!!,
                        tipoEvento = if (proximo == SetorNome.FINANCEIRO) TipoEvento.ENTREGA else TipoEvento.DESPACHO,
                        usuario = if (setorAtual.localFisico) (saidaPor ?: vendedor) else executor,
                        dataHora = relogio
                    )
                )
                fluxo.setorAnterior = setores[setorAtual]
                fluxo.setorAtual = setores[proximo]!!
                fluxo.entrouNoSetorEm = relogio
                fluxo.recebidoPor = null
                fluxo.recebidoEm = null
                fluxo.statusAtual = StatusFluxo.AGUARDANDO_RECEBIMENTO
            }
            return fluxoRepository.save(fluxo)
        }

        fun ordem(numero: String, cliente: String, autor: Usuario, horas: Long): OrdemServico =
            ordemRepository.save(
                OrdemServico(
                    numeroOsErp = numero,
                    cliente = cliente,
                    criadoPor = autor,
                    criadoEm = ha(horas)
                )
            )

        // OS 5050 - exemplo dos docs: dois fluxos paralelos e independentes.
        val os5050 = ordem("5050", "Supermercado Estrela", vendedor, 40)
        semear(
            os5050, "Lona impressa",
            listOf(SetorNome.IMPRESSAO, SetorNome.ACABAMENTO, SetorNome.PRATELEIRA),
            horasInicio = 40, pararEm = 3
        )
        semear(
            os5050, "Adesivacao frota",
            listOf(SetorNome.FROTA, SetorNome.PATIO),
            horasInicio = 40, passoHoras = 6, pararEm = 1, recebidoNoUltimo = true
        )

        // Fluxo completo: recebido e concluido pelo Financeiro.
        val os5041 = ordem("5041", "Padaria Pao Dourado", vendedor, 72)
        semear(
            os5041, "Fachada ACM",
            listOf(
                SetorNome.CRIACAO, SetorNome.IMPRESSAO, SetorNome.ACABAMENTO,
                SetorNome.PRATELEIRA, SetorNome.FINANCEIRO
            ),
            horasInicio = 72, passoHoras = 5, saidaPor = vendedor, recebidoNoUltimo = true
        )

        // Aguardando recebimento na fila da Criacao.
        val os5062 = ordem("5062", "Auto Center Veloz", vendedor2, 5)
        semear(os5062, "Principal", listOf(SetorNome.CRIACAO), horasInicio = 5, pararEm = 1)

        // Em processamento no Recorte.
        val os5063 = ordem("5063", "Clinica Vida", vendedor, 9)
        semear(
            os5063, "Adesivo de vitrine",
            listOf(SetorNome.CRIACAO, SetorNome.RECORTE),
            horasInicio = 9, passoHoras = 2, pararEm = 2, recebidoNoUltimo = true
        )

        // Aguardando na Preparacao.
        val os5064 = ordem("5064", "Construtora Horizonte", vendedor2, 14)
        semear(
            os5064, "Placas de obra",
            listOf(SetorNome.IMPRESSAO, SetorNome.RECORTE, SetorNome.PREPARACAO),
            horasInicio = 14, passoHoras = 3, pararEm = 3
        )

        // Parado no Patio aguardando liberacao do comercial.
        val os5058 = ordem("5058", "Transportes Sul", vendedor, 26)
        semear(
            os5058, "Envelopamento cavalo mecanico",
            listOf(SetorNome.FROTA, SetorNome.PATIO),
            horasInicio = 26, passoHoras = 9, pararEm = 2
        )

        // Fluxo cancelado pela Diretoria, com historico preservado (RN02).
        val os5055 = ordem("5055", "Bar do Ze", vendedor, 30)
        val cancelado = semear(
            os5055, "Luminoso",
            listOf(SetorNome.CRIACAO, SetorNome.IMPRESSAO),
            horasInicio = 30, passoHoras = 4, pararEm = 2, recebidoNoUltimo = true
        )
        val momentoCancelamento = ha(20)
        eventoRepository.save(
            EventoMovimentacao(
                fluxo = cancelado,
                setorOrigem = cancelado.setorAtual,
                setorDestino = cancelado.setorAtual,
                tipoEvento = TipoEvento.CANCELAMENTO,
                usuario = diretoria,
                dataHora = momentoCancelamento,
                observacao = "Cliente desistiu do pedido."
            )
        )
        cancelado.statusAtual = StatusFluxo.CANCELADA
        cancelado.encerrado = true
        cancelado.encerradoEm = momentoCancelamento
        cancelado.motivoCancelamento = "Cliente desistiu do pedido."
        fluxoRepository.save(cancelado)
        os5055.cancelada = true
        os5055.motivoCancelamento = "Cliente desistiu do pedido."
        ordemRepository.save(os5055)

        log.info(
            "Carga inicial concluida: {} ordens, {} fluxos, {} eventos.",
            ordemRepository.count(), fluxoRepository.count(), eventoRepository.count()
        )
    }

    /**
     * A equipe da Frota da demonstracao: cada adesivador com a sua coluna na agenda (ligada
     * a conta dele, para a Minha agenda), mais a coluna do Noturno.
     */
    private fun montarEquipeDaFrota() {
        val senha = passwordEncoder.encode(SENHA_DEMONSTRACAO)
        val operacional = perfilRepository.findByNome(PerfilNome.OPERACIONAL)!!
        val frota = setorRepository.findByNome(SetorNome.FROTA)!!
        val heitor = usuarioRepository.findByLoginIgnoreCase("frota")
        val equipe = listOfNotNull(heitor?.let { "HEITOR" to it }) + listOf(
            "LUCAS" to "Lucas Andrade", "RAFAEL" to "Rafael Moreira", "TIAGO" to "Tiago Nunes"
        ).map { (coluna, nome) ->
            coluna to usuarioRepository.save(
                Usuario(
                    nome = nome, login = coluna.lowercase(), email = "${coluna.lowercase()}@rastros.cloud",
                    senhaHash = senha, perfil = operacional, setor = frota, trocarSenha = true
                )
            )
        }
        equipe.forEachIndexed { i, (coluna, usuario) ->
            adesivadorRepository.save(Adesivador(nome = coluna, ordem = i + 1, usuario = usuario))
        }
        adesivadorRepository.save(Adesivador(nome = "NOTURNO", tipo = TipoColunaAgenda.NOTURNO, ordem = equipe.size + 1))
        log.info("Equipe da Frota da demonstracao: {} adesivadores e a coluna do Noturno.", equipe.size)
    }

    /** 10 caracteres sem os que se confundem na hora de digitar (0/O, 1/l/I). */
    private fun senhaAleatoria(): String {
        val alfabeto = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val sorteio = java.security.SecureRandom()
        return (1..10).map { alfabeto[sorteio.nextInt(alfabeto.length)] }.joinToString("")
    }

    private companion object {
        const val SENHA_DEMONSTRACAO = "123456"
    }
}
