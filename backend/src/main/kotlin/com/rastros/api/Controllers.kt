package com.rastros.api

import com.rastros.domain.StatusFluxo
import com.rastros.repository.SetorRepository
import com.rastros.repository.UsuarioRepository
import com.rastros.security.JwtService
import com.rastros.security.UsuarioAutenticado
import com.rastros.service.*
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/health")
class HealthController {
    @GetMapping
    fun health() = mapOf("status" to "ok", "servico" to "rastros-api")
}

/** Resposta da consulta por numero: [os] nulo quando a OS ainda nao esta no sistema. */
data class OsNoSistemaResponse(val os: OsJaEnviadaResponse?)

/**
 * A empresa que usa esta instalacao do RastrOS: nome e logo no topo e no login, e se o
 * "agir como" do administrador esta ligado aqui (desligado, o seletor nem aparece).
 */
data class EmpresaResponse(val sistema: String, val nome: String, val logo: String?, val agirComo: Boolean)

@RestController
@RequestMapping("/api/empresa")
class EmpresaController(
    @org.springframework.beans.factory.annotation.Value("\${rastros.empresa.nome:RastrOS}") private val nome: String,
    @org.springframework.beans.factory.annotation.Value("\${rastros.empresa.logo:/rastros-logo-branca.svg}") private val logo: String,
    @org.springframework.beans.factory.annotation.Value("\${rastros.personificacao.habilitada:false}") private val agirComo: Boolean
) {
    /** Aberto: o login mostra a empresa antes de a pessoa entrar. */
    @GetMapping
    fun empresa() = EmpresaResponse(sistema = "RastrOS", nome = nome, logo = logo.ifBlank { null }, agirComo = agirComo)
}

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val usuarioRepository: UsuarioRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
    private val minhaAgenda: com.rastros.service.MinhaAgendaService,
    private val protecaoDeLogin: com.rastros.security.ProtecaoDeLogin
) {

    /** O usuario da sessao, com a indicacao de que ele adesiva (aba "Minha agenda"). */
    private fun com.rastros.domain.Usuario.daSessao() = paraResponse().copy(temAgenda = minhaAgenda.colunaDe(this) != null)

    @PostMapping("/login")
    fun login(@Valid @RequestBody req: LoginRequest, request: jakarta.servlet.http.HttpServletRequest): LoginResponse {
        // Entra pelo nome de usuario; quem ainda digitar o e-mail antigo tambem entra.
        val digitado = req.usuario.trim()
        // Freio contra adivinhar senha: tentativas demais fecham o login por um tempo.
        val endereco = com.rastros.security.ProtecaoDeLogin.enderecoDe(request)
        protecaoDeLogin.conferir(digitado, endereco)
        val usuario = usuarioRepository.findByLoginIgnoreCase(digitado)
            ?: digitado.takeIf { '@' in it }?.let { usuarioRepository.findByEmailIgnoreCase(it) }
        if (usuario == null || !passwordEncoder.matches(req.senha, usuario.senhaHash)) {
            protecaoDeLogin.falhou(digitado, endereco)
            throw PermissaoNegadaException("Usuario ou senha invalidos.")
        }
        if (!usuario.ativo) throw PermissaoNegadaException("Usuario inativo. Procure o administrador.")
        protecaoDeLogin.entrou(digitado)
        return LoginResponse(
            token = jwtService.gerarToken(usuario),
            expiraEmSegundos = jwtService.expiracaoSegundos(usuario),
            usuario = usuario.daSessao()
        )
    }

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal autor: UsuarioAutenticado): UsuarioResponse =
        usuarioRepository.findById(autor.id)
            .orElseThrow { NaoEncontradoException("Usuario nao encontrado.") }
            .daSessao()
            .copy(agindoPor = autor.personificadoPor?.let { usuarioRepository.findById(it).orElse(null)?.nome })

    /** A pessoa troca a propria senha. E a unica coisa liberada para quem esta com senha provisoria. */
    @PostMapping("/trocar-senha")
    fun trocarSenha(
        @Valid @RequestBody req: TrocarSenhaRequest,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): LoginResponse {
        val usuario = usuarioRepository.findById(autor.id)
            .orElseThrow { NaoEncontradoException("Usuario nao encontrado.") }
        if (!passwordEncoder.matches(req.senhaAtual, usuario.senhaHash)) {
            throw RegraDeNegocioException("A senha atual nao confere.")
        }
        PoliticaDeSenha.validar(req.novaSenha, usuario.login, usuario.email)
        if (req.novaSenha == req.senhaAtual) {
            throw RegraDeNegocioException("A nova senha precisa ser diferente da atual.")
        }
        usuario.senhaHash = passwordEncoder.encode(req.novaSenha)
        usuario.trocarSenha = false
        // Quem soube da senha velha perde o acesso: todas as outras sessoes caem, e esta
        // continua com o token novo devolvido aqui.
        usuario.encerrarSessoes()
        val salvo = usuarioRepository.save(usuario)
        val admin = autor.personificadoPor?.let { usuarioRepository.findById(it).orElse(null) }
        return LoginResponse(
            token = jwtService.gerarToken(salvo, personificadoPor = admin),
            expiraEmSegundos = jwtService.expiracaoSegundos(salvo),
            usuario = salvo.daSessao().copy(agindoPor = admin?.nome)
        )
    }
}

/** Regras minimas para uma senha que nao seja adivinhada na primeira tentativa. */
object PoliticaDeSenha {
    private val obvias = setOf("123456", "1234567", "12345678", "123456789", "senha123", "password", "rastros", "rastros123", "ostracker")

    fun validar(senha: String, login: String, email: String? = null) {
        if (senha.length < 8) throw RegraDeNegocioException("A senha precisa ter pelo menos 8 caracteres.")
        if (senha.lowercase() in obvias || senha.all { it == senha.first() }) {
            throw RegraDeNegocioException("Essa senha e facil demais de adivinhar. Escolha outra.")
        }
        val proibidas = listOfNotNull(login, email, email?.substringBefore('@'))
        if (proibidas.any { senha.equals(it, ignoreCase = true) }) {
            throw RegraDeNegocioException("A senha nao pode ser o proprio usuario ou e-mail.")
        }
    }
}

@RestController
@RequestMapping("/api/setores")
class SetorController(
    private val setorRepository: SetorRepository,
    private val matriz: MatrizTransicaoService
) {

    @GetMapping
    fun listar(): List<SetorResponse> = setorRepository.findAllByOrderByNomeAsc().map { it.paraResponse() }

    /** Setores validos para abertura de um fluxo novo (origem = Vendas). */
    @GetMapping("/iniciais")
    fun iniciais(): List<SetorResponse> = matriz.setoresIniciais().map { it.paraResponse() }
}

@RestController
@RequestMapping("/api/ordens")
class OrdemController(private val fluxoService: FluxoService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun criar(
        @Valid @RequestBody req: NovaOrdemRequest,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): OrdemResponse = fluxoService.criarOrdem(req, autor)

    /**
     * A OS com este numero ja esta no sistema? A extensao do ERP pergunta antes de enviar:
     * OS repetida nao ganha fluxo novo, so o aviso de que ja foi enviada.
     */
    @GetMapping("/por-numero")
    fun porNumero(@RequestParam numero: String): OsNoSistemaResponse =
        OsNoSistemaResponse(fluxoService.ordemPorNumero(numero))

    /** Lista para o seletor de OS da agenda: só as que ainda estão em andamento. */
    @GetMapping("/abertas")
    fun abertas(): List<OrdemAbertaResponse> = fluxoService.ordensAbertas()

    @GetMapping("/{id}")
    fun detalhe(
        @PathVariable id: Int,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): OrdemResponse = fluxoService.detalheOrdem(id, autor)

    @PostMapping("/{id}/fluxos")
    @ResponseStatus(HttpStatus.CREATED)
    fun adicionarFluxo(
        @PathVariable id: Int,
        @Valid @RequestBody req: NovoFluxoRequest,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): OrdemResponse = fluxoService.adicionarFluxo(id, req, autor)

    @PostMapping("/{id}/cancelar")
    fun cancelar(
        @PathVariable id: Int,
        @Valid @RequestBody req: MotivoRequest,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): OrdemResponse = fluxoService.cancelarOrdem(id, req, autor)
}

@RestController
@RequestMapping("/api/fluxos")
class FluxoController(
    private val fluxoService: FluxoService,
    private val esperaService: com.rastros.service.EsperaService
) {

    /** Prateleira e Patio: o que espera o comercial liberar para o Financeiro. */
    @GetMapping("/em-espera")
    fun emEspera(): List<com.rastros.service.OsEmEsperaResponse> = esperaService.emEspera()

    @GetMapping
    fun listar(
        @RequestParam(required = false) termo: String?,
        @RequestParam(required = false) status: StatusFluxo?,
        @RequestParam(required = false) setor: com.rastros.domain.SetorNome?,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): List<FluxoResponse> = fluxoService.listarFluxos(autor, termo, status, setor)

    @GetMapping("/{id}")
    fun detalhe(
        @PathVariable id: Int,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): FluxoDetalheResponse = fluxoService.detalhe(id, autor)

    @PostMapping("/{id}/receber")
    fun receber(
        @PathVariable id: Int,
        @RequestBody(required = false) req: ReceberRequest?,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): FluxoDetalheResponse = fluxoService.receber(id, req ?: ReceberRequest(), autor)

    /** Devolve ao setor de onde a OS veio (botao Devolver da tela do setor). */
    @PostMapping("/{id}/devolver")
    fun devolver(
        @PathVariable id: Int,
        @RequestBody(required = false) req: ReceberRequest?,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): FluxoDetalheResponse = fluxoService.devolver(id, req ?: ReceberRequest(), autor)

    /** Conclui o fluxo - so o Financeiro, com a OS recebida. */
    @PostMapping("/{id}/concluir")
    fun concluir(
        @PathVariable id: Int,
        @RequestBody(required = false) req: ReceberRequest?,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): FluxoDetalheResponse = fluxoService.concluir(id, req ?: ReceberRequest(), autor)

    @PostMapping("/{id}/despachar")
    fun despachar(
        @PathVariable id: Int,
        @Valid @RequestBody req: DespacharRequest,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): FluxoDetalheResponse = fluxoService.despachar(id, req, autor)

    @PostMapping("/{id}/cancelar")
    fun cancelar(
        @PathVariable id: Int,
        @Valid @RequestBody req: MotivoRequest,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): FluxoDetalheResponse = fluxoService.cancelarFluxo(id, req, autor)
}

@RestController
@RequestMapping("/api/painel")
class PainelController(
    private val fluxoService: FluxoService,
    private val minhaAgenda: MinhaAgendaService,
    private val clima: ClimaService
) {

    /** A agenda do dia de cada adesivador - o que cada um ve na propria "Minha agenda". */
    @GetMapping("/agendas")
    fun agendas(
        @RequestParam(required = false)
        @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
        data: java.time.LocalDate?
    ): List<MinhaAgendaResponse> = minhaAgenda.agendasDoDia(data ?: java.time.LocalDate.now())

    /** Previsao do tempo do dia para a linha do topo do painel; 204 sem previsao (sem internet). */
    @GetMapping("/clima")
    fun clima(): org.springframework.http.ResponseEntity<ClimaResponse> =
        clima.agora()?.let { org.springframework.http.ResponseEntity.ok(it) }
            ?: org.springframework.http.ResponseEntity.noContent().build()

    /**
     * Botao direito no card do painel: iniciar, pausar, retomar ou concluir o projeto de um
     * adesivador (so com a permissao "Agir pelo painel").
     */
    @PostMapping("/projetos/{id}/{acao}")
    fun agir(
        @PathVariable id: Long,
        @PathVariable acao: String,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): ProjetoResponse = minhaAgenda.agirPeloPainel(id, acao, autor)

    /** As OS que estao no Acabamento agora, da que chegou primeiro para a mais nova. */
    @GetMapping("/acabamento")
    fun acabamento(): List<OsNoSetorResponse> = fluxoService.noSetor(com.rastros.domain.SetorNome.ACABAMENTO)

    @GetMapping
    fun painel(
        @RequestParam(required = false)
        @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
        inicio: java.time.LocalDate?,
        @RequestParam(required = false)
        @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
        fim: java.time.LocalDate?,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): PainelResponse {
        // Sem periodo: o dia de hoje.
        val hoje = java.time.LocalDate.now()
        return fluxoService.painel(autor, inicio ?: hoje, fim ?: inicio ?: hoje)
    }
}

/** Score de um servico, lancado na lista do relatorio semanal. */
data class ScoreServicoRequest(val score: java.math.BigDecimal? = null)

/** A tela do setor: o que chegou para receber e o que esta no setor para despachar. */
@RestController
@RequestMapping("/api/movimentacao")
class MovimentacaoController(private val fluxoService: FluxoService) {

    @GetMapping
    fun doMeuSetor(@AuthenticationPrincipal autor: UsuarioAutenticado): MovimentacaoResponse =
        fluxoService.movimentacao(autor)
}

/** Indicadores derivados da trilha de eventos e da agenda (docs/07). */
@RestController
@RequestMapping("/api/produtividade")
class ProdutividadeController(
    private val produtividadeService: ProdutividadeService,
    private val agendaService: AgendaService
) {

    @GetMapping
    fun calcular(
        @RequestParam(required = false)
        @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
        inicio: java.time.LocalDate?,
        @RequestParam(required = false)
        @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
        fim: java.time.LocalDate?
    ): ProdutividadeResponse {
        val ate = fim ?: java.time.LocalDate.now()
        val de = inicio ?: ate.minusDays(29)
        return produtividadeService.calcular(de, ate)
    }

    /** Relatório da semana por adesivador: serviço por serviço, com a OS de cada um. */
    @GetMapping("/semanal")
    fun semanal(
        @RequestParam(required = false)
        @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
        data: java.time.LocalDate?,
        @RequestParam(required = false)
        @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
        inicio: java.time.LocalDate?,
        @RequestParam(required = false)
        @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
        fim: java.time.LocalDate?
    ): RelatorioSemanalResponse =
        if (inicio != null && fim != null) produtividadeService.relatorio(inicio, fim)
        else produtividadeService.relatorioSemanal(data ?: java.time.LocalDate.now())

    /** Busca em todos os meses, pelo numero da OS ou pelo carro/servico. */
    @GetMapping("/semanal/busca")
    fun buscar(@RequestParam termo: String): BuscaRelatorioResponse = produtividadeService.buscarServicos(termo)

    /**
     * Atribui o score de um servico da lista. O score se lanca aqui, olhando o que foi
     * entregue - na agenda ele seria um palpite antes do servico existir.
     *
     * Devolve a semana inteira recalculada, para o total da pessoa acompanhar a digitacao.
     */
    @PatchMapping("/semanal/servicos/{agendamentoId}/score")
    fun atribuirScore(
        @PathVariable agendamentoId: Long,
        @RequestBody req: ScoreServicoRequest,
        @RequestParam(required = false)
        @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
        inicio: java.time.LocalDate?,
        @RequestParam(required = false)
        @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
        fim: java.time.LocalDate?,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): RelatorioSemanalResponse {
        val data = agendaService.alterarScore(agendamentoId, req.score, autor)
        // Devolve o periodo que a tela esta mostrando (a semana ou o mes inteiro).
        return if (inicio != null && fim != null) produtividadeService.relatorio(inicio, fim)
        else produtividadeService.relatorioSemanal(data)
    }
}

/**
 * Avisos de mudanca em tempo real: a tela abre esta conexao e, a cada gravacao na agenda
 * ou nas OS, recebe um "mudou" e busca de novo. Qualquer usuario logado; o aviso nao
 * carrega dado nenhum.
 */
@RestController
@RequestMapping("/api/mudancas")
class MudancasController(private val canal: CanalDeMudancas) {

    @GetMapping(produces = [org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE])
    fun ouvir(response: jakarta.servlet.http.HttpServletResponse): org.springframework.web.servlet.mvc.method.annotation.SseEmitter {
        // Atras do Cloudflare (ou de qualquer proxy): nada de guardar, comprimir ou juntar os
        // avisos - eles precisam chegar na hora.
        response.setHeader("Cache-Control", "no-cache, no-transform")
        response.setHeader("X-Accel-Buffering", "no")
        return canal.abrir()
    }
}

/** "Minha agenda": a coluna do adesivador que esta logado, dia a dia. */
@RestController
@RequestMapping("/api/minha-agenda")
class MinhaAgendaController(private val minhaAgenda: com.rastros.service.MinhaAgendaService) {

    @GetMapping
    fun doDia(
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
        data: java.time.LocalDate?,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): com.rastros.service.MinhaAgendaResponse = minhaAgenda.doDia(data ?: java.time.LocalDate.now(), autor)

    /** Comecar o projeto: a OS e recebida na Frota em nome de quem iniciou (agora ou quando chegar). */
    @PostMapping("/{id}/iniciar")
    fun iniciar(@PathVariable id: Long, @AuthenticationPrincipal autor: UsuarioAutenticado): ProjetoResponse =
        minhaAgenda.iniciar(id, autor)

    /** Terminar o projeto: com os projetos da OS terminados, ela vai da Frota para o Patio. */
    @PostMapping("/{id}/concluir")
    fun concluir(@PathVariable id: Long, @AuthenticationPrincipal autor: UsuarioAutenticado): ProjetoResponse =
        minhaAgenda.concluir(id, autor)

    /** Parar o projeto por um tempo; o horario fica registrado e aparece no relatorio. */
    @PostMapping("/{id}/pausar")
    fun pausar(@PathVariable id: Long, @AuthenticationPrincipal autor: UsuarioAutenticado): ProjetoResponse =
        minhaAgenda.pausar(id, autor)

    @PostMapping("/{id}/retomar")
    fun retomar(@PathVariable id: Long, @AuthenticationPrincipal autor: UsuarioAutenticado): ProjetoResponse =
        minhaAgenda.retomar(id, autor)
}

data class NomeCorRequest(val nome: String = "", val cor: String = "")
data class VendedorRequest(val nome: String = "", val codigo: String? = null)

/**
 * Os vendedores da agenda: a lista e cadastrada na propria agenda (Diretoria e
 * Administrador). O codigo (a letra do cronograma) e o que fica gravado no card.
 */
@RestController
@RequestMapping("/api/vendedores")
class VendedoresController(private val configuracao: ConfiguracaoAgendaService) {

    /** Todos, inclusive os removidos (ativo = false), para os cards antigos mostrarem o nome. */
    @GetMapping
    fun listar(): List<VendedorAgendaResponse> = configuracao.vendedores()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun adicionar(@RequestBody req: VendedorRequest, @AuthenticationPrincipal autor: UsuarioAutenticado) =
        configuracao.adicionarVendedor(req.nome, req.codigo, autor)

    @PutMapping("/{id}")
    fun renomear(@PathVariable id: Int, @RequestBody req: VendedorRequest, @AuthenticationPrincipal autor: UsuarioAutenticado) =
        configuracao.renomearVendedor(id, req.nome, autor)

    @DeleteMapping("/{id}")
    fun remover(@PathVariable id: Int, @AuthenticationPrincipal autor: UsuarioAutenticado) =
        configuracao.removerVendedor(id, autor)

    @PostMapping("/{id}/restaurar")
    fun restaurar(@PathVariable id: Int, @AuthenticationPrincipal autor: UsuarioAutenticado) =
        configuracao.restaurarVendedor(id, autor)
}

/**
 * A legenda de status da agenda: os do sistema mudam de nome e cor; os criados na agenda
 * tambem podem ser removidos. Todos leem (a agenda, o painel e a Minha agenda pintam com
 * ela); so Diretoria e Administrador editam.
 */
@RestController
@RequestMapping("/api/status-agenda")
class StatusAgendaController(private val configuracao: ConfiguracaoAgendaService) {

    @GetMapping
    fun listar(): List<StatusAgendaResponse> = configuracao.status()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun adicionar(@RequestBody req: NomeCorRequest, @AuthenticationPrincipal autor: UsuarioAutenticado) =
        configuracao.adicionarStatus(req.nome, req.cor, autor)

    @PutMapping("/{id}")
    fun alterar(@PathVariable id: Long, @RequestBody req: NomeCorRequest, @AuthenticationPrincipal autor: UsuarioAutenticado) =
        configuracao.alterarStatus(id, req.nome, req.cor, autor)

    /** Ids de todos os status na nova ordem da legenda. */
    @PutMapping("/ordem")
    fun reordenar(@RequestBody ids: List<Long>, @AuthenticationPrincipal autor: UsuarioAutenticado) =
        configuracao.reordenarStatus(ids, autor)

    @DeleteMapping("/{id}")
    fun remover(@PathVariable id: Long, @AuthenticationPrincipal autor: UsuarioAutenticado) =
        configuracao.removerStatus(id, autor)
}
