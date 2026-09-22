package com.ostracker.api

import com.ostracker.domain.*
import com.ostracker.repository.*
import com.ostracker.service.BackupResponse
import com.ostracker.service.BackupService
import com.ostracker.service.ExclusaoUsuarioService
import com.ostracker.security.UsuarioAutenticado
import com.ostracker.service.NaoEncontradoException
import com.ostracker.service.PermissaoNegadaException
import com.ostracker.service.RegraDeNegocioException
import com.ostracker.service.paraResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.*

/**
 * RF07 - gestao de usuarios, setores e da matriz de transicao. Usuarios: Admin e Financeiro;
 * o resto so o Admin (as regras de rota estao no SecurityConfig).
 */
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
    private val feriadoService: com.ostracker.service.FeriadoService
) {

    // --------------------------------------------------------------- feriados

    /** Dias em que a empresa nao abre: nao contam no tempo das OS. */
    @GetMapping("/feriados")
    fun listarFeriados(): List<com.ostracker.service.FeriadoResponse> = feriadoService.listar()

    @PostMapping("/feriados")
    @ResponseStatus(HttpStatus.CREATED)
    fun adicionarFeriado(@Valid @RequestBody req: FeriadoRequest): com.ostracker.service.FeriadoResponse =
        feriadoService.adicionar(req.data!!, req.descricao)

    @DeleteMapping("/feriados/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removerFeriado(@PathVariable id: Int) = feriadoService.remover(id)

    // ------------------------------------------------------------- usuarios

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

        garantirLoginEEmailLivres(req, id)

        usuario.nome = req.nome.trim()
        usuario.login = req.login.trim().lowercase()
        usuario.email = emailDe(req)
        usuario.perfil = perfil(req.perfil!!)
        usuario.setor = setorDoPerfil(req)
        usuario.ativo = req.ativo
        req.senha?.takeIf { it.isNotBlank() }?.let {
            usuario.senhaHash = passwordEncoder.encode(it)
            usuario.trocarSenha = true // senha redefinida pelo administrador tambem e provisoria
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
     * O Financeiro cadastra usuarios e redefine senhas, mas nao cria nem mexe em
     * administrador: senao bastaria criar para si uma conta com acesso total.
     */
    private fun garantirPodeDarPerfil(autor: UsuarioAutenticado, perfil: PerfilNome) {
        if (autor.perfil != PerfilNome.ADMIN && perfil == PerfilNome.ADMIN) {
            throw PermissaoNegadaException("Somente um administrador cria ou altera contas de administrador.")
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

    private fun setorDoPerfil(req: UsuarioRequest): Setor? {
        if (req.perfil != PerfilNome.OPERACIONAL) return null
        val setorId = req.setorId
            ?: throw RegraDeNegocioException("Usuarios do perfil Operacional precisam de um setor.")
        return setorRepository.findById(setorId)
            .orElseThrow { NaoEncontradoException("Setor $setorId nao encontrado.") }
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
