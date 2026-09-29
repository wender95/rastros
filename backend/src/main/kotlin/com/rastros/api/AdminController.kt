package com.rastros.api

import com.rastros.domain.*
import com.rastros.repository.*
import com.rastros.service.BackupResponse
import com.rastros.service.BackupService
import com.rastros.service.ExclusaoUsuarioService
import com.rastros.security.UsuarioAutenticado
import com.rastros.service.NaoEncontradoException
import com.rastros.service.PermissaoNegadaException
import com.rastros.service.RegraDeNegocioException
import com.rastros.service.paraResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.*

/**
 * RF07 - gestao de usuarios, setores e da matriz de transicao. Usuarios: Admin e Financeiro;
 * o resto so o Admin (as regras de rota estao no SecurityConfig).
 */

/**
 * O que o Financeiro pode fazer no cadastro: so contas de Operacional e Comercial. Se ele
 * redefinisse a senha da Diretoria (ou desse o perfil Diretoria a alguem, ate a si mesmo),
 * ganharia um acesso que nao tem. O resto e com o Administrador.
 */
val GERENCIAVEIS_PELO_FINANCEIRO = setOf(PerfilNome.OPERACIONAL, PerfilNome.VENDEDOR)

@RestController
@RequestMapping("/api/admin")
class AdminController(
    private val usuarioRepository: UsuarioRepository,
    private val perfilRepository: PerfilRepository,
    private val setorRepository: SetorRepository,
    private val transicaoRepository: TransicaoPermitidaRepository,
    private val passwordEncoder: PasswordEncoder,
    private val backupService: BackupService,
    private val exclusaoUsuarioService: ExclusaoUsuarioService,
    private val feriadoService: com.rastros.service.FeriadoService,
    private val jwtService: com.rastros.security.JwtService,
    private val minhaAgenda: com.rastros.service.MinhaAgendaService,
    @org.springframework.beans.factory.annotation.Value("\${rastros.personificacao.habilitada:false}")
    private val personificacaoHabilitada: Boolean
) {

    // --------------------------------------------------------------- feriados

    /** Dias em que a empresa nao abre: nao contam no tempo das OS. */
    @GetMapping("/feriados")
    fun listarFeriados(): List<com.rastros.service.FeriadoResponse> = feriadoService.listar()

    @PostMapping("/feriados")
    @ResponseStatus(HttpStatus.CREATED)
    fun adicionarFeriado(@Valid @RequestBody req: FeriadoRequest): com.rastros.service.FeriadoResponse =
        feriadoService.adicionar(req.data!!, req.descricao)

    @DeleteMapping("/feriados/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removerFeriado(@PathVariable id: Int) = feriadoService.remover(id)

    // ------------------------------------------------------------- usuarios

    /**
     * Fase de teste: o administrador passa a **agir como** outra pessoa - ve as telas dela
     * (o setor, a Minha agenda) e recebe, despacha, inicia e conclui como ela, sem precisar
     * da senha. Tudo fica gravado em nome da pessoa; o log do servidor registra quem estava
     * por tras. Desliga com PERSONIFICACAO=false.
     */
    @PostMapping("/personificar/{usuarioId}")
    fun personificar(
        @PathVariable usuarioId: Int,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): LoginResponse {
        if (!personificacaoHabilitada) throw RegraDeNegocioException("Agir como outro usuario esta desligado neste servidor.")
        if (autor.personificadoPor != null) throw RegraDeNegocioException("Volte para o administrador antes de escolher outra pessoa.")
        val admin = usuarioRepository.findById(autor.id).orElseThrow { NaoEncontradoException("Usuario nao encontrado.") }
        val alvo = usuarioRepository.findById(usuarioId).orElseThrow { NaoEncontradoException("Usuario nao encontrado.") }
        if (!alvo.ativo) throw RegraDeNegocioException("${alvo.nome} esta desativado.")
        if (alvo.perfil.nome == PerfilNome.ADMIN) throw RegraDeNegocioException("${alvo.nome} ja e administrador.")
        org.slf4j.LoggerFactory.getLogger(javaClass)
            .info("Administrador {} passou a agir como {} ({}).", admin.login, alvo.login, alvo.perfil.nome)
        return LoginResponse(
            token = jwtService.gerarToken(alvo, personificadoPor = admin),
            expiraEmSegundos = jwtService.expiracaoSegundos(alvo),
            usuario = alvo.paraResponse().copy(temAgenda = minhaAgenda.colunaDe(alvo) != null, agindoPor = admin.nome)
        )
    }

    @GetMapping("/usuarios")
    fun listarUsuarios(): List<UsuarioResponse> =
        usuarioRepository.findAllByOrderByNomeAsc().map { it.paraResponse() }

    @PostMapping("/usuarios")
    @ResponseStatus(HttpStatus.CREATED)
    fun criarUsuario(
        @Valid @RequestBody req: UsuarioRequest,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): UsuarioResponse {
        garantirPodeDarPerfil(autor, req.perfil!!)
        garantirLoginEEmailLivres(req, id = null)
        val senha = req.senha?.takeIf { it.isNotBlank() }
            ?: throw RegraDeNegocioException("Informe uma senha inicial.")

        val usuario = Usuario(
            nome = req.nome.trim(),
            login = req.login.trim().lowercase(),
            email = emailDe(req),
            senhaHash = passwordEncoder.encode(senha),
            perfil = perfil(req.perfil!!),
            setor = setorDoPerfil(req),
            ativo = req.ativo,
            // A senha que o administrador define e provisoria: a pessoa escolhe a dela no 1o acesso.
            trocarSenha = true
        )
        usuario.outrosSetores = outrosSetoresDo(req, usuario.setor, atuais = emptySet())
        if (autor.perfil == PerfilNome.ADMIN) req.secoes?.let { usuario.definirSecoes(validarSecoes(req, it)) }
        return usuarioRepository.save(usuario).paraResponse()
    }

    @PutMapping("/usuarios/{id}")
    fun atualizarUsuario(
        @PathVariable id: Int,
        @Valid @RequestBody req: UsuarioRequest,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): UsuarioResponse {
        val usuario = usuarioRepository.findById(id)
            .orElseThrow { NaoEncontradoException("Usuario $id nao encontrado.") }
        garantirPodeDarPerfil(autor, usuario.perfil.nome)
        garantirPodeDarPerfil(autor, req.perfil!!)
        // Pelo cadastro, o Financeiro nao mexe na propria conta (nem no proprio perfil):
        // a senha dele se troca em "Trocar senha", com a senha atual.
        if (autor.perfil != PerfilNome.ADMIN && usuario.id == autor.id) {
            throw PermissaoNegadaException("Para mudar a sua propria conta, use Trocar senha ou peca ao Administrador.")
        }

        garantirLoginEEmailLivres(req, id)

        usuario.nome = req.nome.trim()
        usuario.login = req.login.trim().lowercase()
        usuario.email = emailDe(req)
        usuario.perfil = perfil(req.perfil!!)
        usuario.setor = setorDoPerfil(req)
        usuario.outrosSetores = outrosSetoresDo(req, usuario.setor, atuais = usuario.outrosSetores)
        usuario.ativo = req.ativo
        // Permissoes sao so do Administrador: o Financeiro cadastra e troca senha, mas as
        // secoes da pessoa ficam como estavam (em cima do perfil novo, se ele mudou).
        if (autor.perfil == PerfilNome.ADMIN) req.secoes?.let { usuario.definirSecoes(validarSecoes(req, it)) }
        req.senha?.takeIf { it.isNotBlank() }?.let {
            usuario.senhaHash = passwordEncoder.encode(it)
            usuario.trocarSenha = true // senha redefinida pelo administrador tambem e provisoria
            usuario.encerrarSessoes() // e derruba quem estava logado com a senha antiga
        }

        return usuarioRepository.save(usuario).paraResponse()
    }

    /** Exclui de vez quem nao tem historico (contas antigas, cadastro errado). */
    @DeleteMapping("/usuarios/{id}")
    fun excluirUsuario(
        @PathVariable id: Int,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): Map<String, String> = mapOf("mensagem" to exclusaoUsuarioService.excluir(id, autor))

    /**
     * O Financeiro cadastra e redefine senhas so de Operacional e Comercial (veja
     * [GERENCIAVEIS_PELO_FINANCEIRO]): nem dar, nem mexer em perfil acima disso.
     */
    private fun garantirPodeDarPerfil(autor: UsuarioAutenticado, perfil: PerfilNome) {
        if (autor.perfil != PerfilNome.ADMIN && perfil !in GERENCIAVEIS_PELO_FINANCEIRO) {
            throw PermissaoNegadaException(
                "O Financeiro cadastra e redefine senhas so de Operacional e Comercial. " +
                    "Contas de ${perfil.name.lowercase()} ficam com o Administrador."
            )
        }
    }

    private fun emailDe(req: UsuarioRequest) = req.email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    private fun garantirLoginEEmailLivres(req: UsuarioRequest, id: Int?) {
        usuarioRepository.findByLoginIgnoreCase(req.login.trim())?.let {
            if (it.id != id) throw RegraDeNegocioException("Ja existe alguem com o usuario ${req.login.trim().lowercase()}.")
        }
        emailDe(req)?.let { email ->
            usuarioRepository.findByEmailIgnoreCase(email)?.let {
                if (it.id != id) throw RegraDeNegocioException("Ja existe um usuario com o e-mail $email.")
            }
        }
    }

    private fun perfil(nome: PerfilNome) = perfilRepository.findByNome(nome)
        ?: throw NaoEncontradoException("Perfil $nome nao encontrado.")

    /** O Personalizado so tem o que for marcado: sem nenhuma secao, a pessoa nao veria nada. */
    private fun validarSecoes(req: UsuarioRequest, secoes: Set<com.rastros.domain.SecaoDoSistema>): Set<com.rastros.domain.SecaoDoSistema> {
        if (req.perfil == PerfilNome.PERSONALIZADO && secoes.isEmpty()) {
            throw RegraDeNegocioException("Marque ao menos uma secao que o usuario Personalizado acessa.")
        }
        return secoes
    }

    /** As secoes de cada perfil, para a tela marcar o padrao ao escolher o perfil. */
    @GetMapping("/usuarios/secoes-padrao")
    fun secoesPadrao(): Map<PerfilNome, List<com.rastros.domain.SecaoDoSistema>> =
        perfilRepository.findAll().associate { it.nome to it.secoesPadrao.sortedBy { s -> s.ordinal } }

    /**
     * Administracao -> Permissoes: as secoes que um perfil traz prontas. Muda na hora para
     * todo mundo do perfil (cada pessoa mantem os proprios ajustes por cima). So o
     * Administrador (a rota /api/admin/perfis e dele); o perfil Administrador nao muda.
     */
    @PutMapping("/perfis/{perfil}/secoes")
    fun definirSecoesDoPerfil(
        @PathVariable perfil: PerfilNome,
        @RequestBody secoes: Set<com.rastros.domain.SecaoDoSistema>
    ): Map<PerfilNome, List<com.rastros.domain.SecaoDoSistema>> {
        if (perfil == PerfilNome.ADMIN) throw RegraDeNegocioException("O Administrador tem todas as secoes, sempre.")
        val alvo = perfil(perfil)
        alvo.secoes.clear()
        alvo.secoes.addAll(com.rastros.domain.SecaoDoSistema.completar(secoes))
        perfilRepository.save(alvo)
        return secoesPadrao()
    }

    private fun setorDoPerfil(req: UsuarioRequest): Setor? {
        if (req.perfil != PerfilNome.OPERACIONAL) return null
        val setorId = req.setorId
            ?: throw RegraDeNegocioException("Usuarios do perfil Operacional precisam de um setor.")
        return setorRepository.findById(setorId)
            .orElseThrow { NaoEncontradoException("Setor $setorId nao encontrado.") }
    }

    /**
     * Setores a mais (so Operacional): quem e do Recorte e tambem da Frota, por exemplo. So
     * setores de trabalho - Patio, Prateleira e Financeiro nao sao setor de ninguem.
     */
    private fun outrosSetoresDo(req: UsuarioRequest, principal: Setor?, atuais: Set<Setor>): MutableSet<Setor> {
        if (req.perfil != PerfilNome.OPERACIONAL) return mutableSetOf()
        val ids = req.outrosSetoresIds ?: return atuais.filter { it.id != principal?.id }.toMutableSet()
        return ids.filter { it != principal?.id }.map { id ->
            val setor = setorRepository.findById(id).orElseThrow { NaoEncontradoException("Setor $id nao encontrado.") }
            if (setor.nome.localFisico || setor.nome == SetorNome.FINANCEIRO) {
                throw RegraDeNegocioException("${setor.nome} nao e um setor de trabalho: nao da para atribuir a um funcionario.")
            }
            setor
        }.toMutableSet()
    }

    // --------------------------------------------------------------- setores

    @GetMapping("/setores")
    fun listarSetores(): List<SetorResponse> =
        setorRepository.findAllByOrderByNomeAsc().map { it.paraResponse() }

    @PatchMapping("/setores/{id}")
    fun alternarSetor(@PathVariable id: Int, @RequestBody req: SetorRequest): SetorResponse {
        val setor = setorRepository.findById(id)
            .orElseThrow { NaoEncontradoException("Setor $id nao encontrado.") }
        setor.ativo = req.ativo
        return setorRepository.save(setor).paraResponse()
    }

    // ------------------------------------------------------ matriz de transicao

    @GetMapping("/transicoes")
    fun listarTransicoes(): List<TransicaoResponse> =
        transicaoRepository.findAll()
            .sortedWith(compareBy({ it.setorOrigem?.name ?: "" }, { it.setorDestino.name }))
            .map { TransicaoResponse(it.id!!, it.setorOrigem, it.setorDestino) }

    @PostMapping("/transicoes")
    @ResponseStatus(HttpStatus.CREATED)
    fun criarTransicao(@Valid @RequestBody req: TransicaoRequest): TransicaoResponse {
        if (req.setorOrigem == req.setorDestino) {
            throw RegraDeNegocioException("Origem e destino nao podem ser o mesmo setor.")
        }
        if (transicaoRepository.existsBySetorOrigemAndSetorDestino(req.setorOrigem, req.setorDestino!!)) {
            throw RegraDeNegocioException("Esta transicao ja existe.")
        }
        val salva = transicaoRepository.save(
            TransicaoPermitida(setorOrigem = req.setorOrigem, setorDestino = req.setorDestino)
        )
        return TransicaoResponse(salva.id!!, salva.setorOrigem, salva.setorDestino)
    }

    @DeleteMapping("/transicoes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removerTransicao(@PathVariable id: Int) {
        if (!transicaoRepository.existsById(id)) {
            throw NaoEncontradoException("Transicao $id nao encontrada.")
        }
        transicaoRepository.deleteById(id)
    }

    // --------------------------------------------------------------- backups

    @GetMapping("/backups")
    fun listarBackups(): List<BackupResponse> = backupService.listar()

    /** Backup na hora, alem do diario automatico. */
    @PostMapping("/backups")
    @ResponseStatus(HttpStatus.CREATED)
    fun fazerBackup(): BackupResponse = backupService.fazer("manual")
}
