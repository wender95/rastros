package com.ostracker.api

import com.ostracker.domain.StatusFluxo
import com.ostracker.repository.SetorRepository
import com.ostracker.repository.UsuarioRepository
import com.ostracker.security.JwtService
import com.ostracker.security.UsuarioAutenticado
import com.ostracker.service.*
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/health")
class HealthController {
    @GetMapping
    fun health() = mapOf("status" to "ok", "servico" to "os-tracker-api")
}

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val usuarioRepository: UsuarioRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
    private val minhaAgenda: com.ostracker.service.MinhaAgendaService
) {

    /** O usuario da sessao, com a indicacao de que ele adesiva (aba "Minha agenda"). */
    private fun com.ostracker.domain.Usuario.daSessao() = paraResponse().copy(temAgenda = minhaAgenda.colunaDe(this) != null)

    @PostMapping("/login")
    fun login(@Valid @RequestBody req: LoginRequest): LoginResponse {
        // Entra pelo nome de usuario; quem ainda digitar o e-mail antigo tambem entra.
        val digitado = req.usuario.trim()
        val usuario = usuarioRepository.findByLoginIgnoreCase(digitado)
            ?: digitado.takeIf { '@' in it }?.let { usuarioRepository.findByEmailIgnoreCase(it) }
            ?: throw PermissaoNegadaException("Usuario ou senha invalidos.")
        if (!usuario.ativo) throw PermissaoNegadaException("Usuario inativo. Procure o administrador.")
        if (!passwordEncoder.matches(req.senha, usuario.senhaHash)) {
            throw PermissaoNegadaException("Usuario ou senha invalidos.")
        }
        return LoginResponse(
            token = jwtService.gerarToken(usuario),
            expiraEmSegundos = jwtService.expiracaoSegundos(),
            usuario = usuario.daSessao()
        )
    }

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal autor: UsuarioAutenticado): UsuarioResponse =
        usuarioRepository.findById(autor.id)
            .orElseThrow { NaoEncontradoException("Usuario nao encontrado.") }
            .daSessao()

    /** A pessoa troca a propria senha. E a unica coisa liberada para quem esta com senha provisoria. */
    @PostMapping("/trocar-senha")
    fun trocarSenha(
        @Valid @RequestBody req: TrocarSenhaRequest,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): UsuarioResponse {
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
        return usuarioRepository.save(usuario).paraResponse()
    }
}

/** Regras minimas para uma senha que nao seja adivinhada na primeira tentativa. */
object PoliticaDeSenha {
    private val obvias = setOf("123456", "1234567", "12345678", "123456789", "senha123", "password", "ostracker")

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
    private val esperaService: com.ostracker.service.EsperaService
) {

    /** Prateleira e Patio: o que espera o comercial liberar para o Financeiro. */
    @GetMapping("/em-espera")
    fun emEspera(): List<com.ostracker.service.OsEmEsperaResponse> = esperaService.emEspera()

    @GetMapping
    fun listar(
        @RequestParam(required = false) termo: String?,
        @RequestParam(required = false) status: StatusFluxo?,
        @RequestParam(required = false) setor: com.ostracker.domain.SetorNome?,
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
class PainelController(private val fluxoService: FluxoService) {

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

/** "Minha agenda": a coluna do adesivador que esta logado, dia a dia. */
@RestController
@RequestMapping("/api/minha-agenda")
class MinhaAgendaController(private val minhaAgenda: com.ostracker.service.MinhaAgendaService) {

    @GetMapping
    fun doDia(
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
        data: java.time.LocalDate?,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): com.ostracker.service.MinhaAgendaResponse = minhaAgenda.doDia(data ?: java.time.LocalDate.now(), autor)
}

data class VendedorResponse(val codigo: String, val nome: String)

/**
 * Os vendedores da agenda, montados dos usuarios cadastrados: o codigo e a primeira letra do
 * nome (como no cronograma: "(K)" e a Karina). Na disputa pela mesma letra, o Comercial vem
 * antes da Diretoria. Assim nenhum nome fica no codigo, e vendedor novo aparece sozinho.
 */
@RestController
@RequestMapping("/api/vendedores")
class VendedoresController(private val usuarioRepository: UsuarioRepository) {

    @GetMapping
    fun listar(): List<VendedorResponse> {
        val prioridade = listOf(com.ostracker.domain.PerfilNome.VENDEDOR, com.ostracker.domain.PerfilNome.DIRETORIA)
        val porCodigo = linkedMapOf<String, String>()
        usuarioRepository.findAllByOrderByNomeAsc()
            .filter { it.ativo && it.perfil.nome in prioridade }
            .sortedBy { prioridade.indexOf(it.perfil.nome) }
            .forEach { u ->
                val codigo = java.text.Normalizer.normalize(u.nome.trim(), java.text.Normalizer.Form.NFD)
                    .firstOrNull { it.isLetter() }?.uppercase() ?: return@forEach
                porCodigo.putIfAbsent(codigo, u.nome.trim())
            }
        return porCodigo.map { (codigo, nome) -> VendedorResponse(codigo, nome) }.sortedBy { it.nome }
    }
}
