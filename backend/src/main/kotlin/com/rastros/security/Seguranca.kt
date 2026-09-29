package com.rastros.security

import com.rastros.domain.PerfilNome
import com.rastros.domain.SetorNome
import com.rastros.domain.Usuario
import com.rastros.repository.UsuarioRepository
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.charset.StandardCharsets
import java.util.Date
import com.rastros.domain.SecaoDoSistema

/** Identidade do usuario autenticado, montada a partir do JWT. */
data class UsuarioAutenticado(
    val id: Int,
    val nome: String,
    val login: String,
    val email: String?,
    val perfil: PerfilNome,
    /** O setor em que a pessoa esta trabalhando agora (com mais de um, o escolhido em Meu setor). */
    val setor: SetorNome?,
    val setorId: Int?,
    /** Todos os setores da pessoa (o principal e os outros). */
    val setores: List<SetorNome> = listOfNotNull(setor),
    /** O administrador que esta agindo como esta pessoa (so em teste; veja personificacao). */
    val personificadoPor: Int? = null,
    /** As secoes que a pessoa acessa: as do perfil, com os ajustes do cadastro. */
    val secoes: Set<SecaoDoSistema> = SecaoDoSistema.completar(SecaoDoSistema.padraoDo(perfil))
) {
    fun tem(secao: SecaoDoSistema) = secao in secoes

    /** Ve as OS de todos os setores (e nao so as que passaram pelo seu). */
    val podeVerTudo: Boolean
        get() = tem(SecaoDoSistema.CONSULTA) || tem(SecaoDoSistema.CRIAR_OS) ||
            tem(SecaoDoSistema.PATIO_PRATELEIRA) || tem(SecaoDoSistema.AGENDA_EDITAR)

    /** O que a seguranca da API confere: o perfil (ROLE_) e cada secao (SECAO_). */
    val autoridades: List<SimpleGrantedAuthority>
        get() = listOf(SimpleGrantedAuthority("ROLE_${perfil.name}")) +
            secoes.map { SimpleGrantedAuthority("SECAO_${it.name}") }
}

/**
 * [setorEscolhido]: o setor que a pessoa escolheu em Meu setor (cabecalho X-Setor). So vale
 * se for um dos setores dela; senao, fica o principal.
 */
fun Usuario.paraAutenticado(setorEscolhido: Int? = null): UsuarioAutenticado {
    val ativo = setores.firstOrNull { it.id == setorEscolhido } ?: this.setor
    return UsuarioAutenticado(
        id = this.id!!,
        nome = this.nome,
        login = this.login,
        email = this.email,
        perfil = this.perfil.nome,
        setor = ativo?.nome,
        setorId = ativo?.id,
        setores = setores.map { it.nome },
        secoes = this.secoes
    )
}

/** O que um token valido diz: de quem e a sessao e em que versao ela foi aberta. */
data class TokenLido(
    val usuarioId: Int,
    val personificadoPor: Int?,
    val sessao: Int,
    val sessaoAdmin: Int
)

@Service
class JwtService(
    @Value("\${rastros.jwt.secret:}") segredoConfigurado: String,
    @Value("\${rastros.jwt.arquivo-segredo}") arquivoSegredo: String,
    @Value("\${rastros.jwt.expiracao-horas}") private val expiracaoHoras: Long
) {
    private val chave = Keys.hmacShaKeyFor(resolverSegredo(segredoConfigurado, arquivoSegredo))

    /**
     * A chave que assina os logins. Sem JWT_SECRET configurado, cada instalacao gera a sua
     * uma vez e guarda ao lado do banco - nunca uma chave fixa no codigo, que qualquer um
     * com acesso ao repositorio usaria para forjar um login de administrador.
     */
    private fun resolverSegredo(configurado: String, arquivo: String): ByteArray {
        if (configurado.isNotBlank()) {
            val bytes = configurado.toByteArray(StandardCharsets.UTF_8)
            require(bytes.size >= 32) { "JWT_SECRET precisa ter pelo menos 32 caracteres." }
            return bytes
        }
        val caminho = java.nio.file.Paths.get(arquivo).toAbsolutePath().normalize()
        if (java.nio.file.Files.exists(caminho)) {
            return java.util.Base64.getDecoder().decode(java.nio.file.Files.readString(caminho).trim())
        }
        val novo = ByteArray(64).also { java.security.SecureRandom().nextBytes(it) }
        java.nio.file.Files.createDirectories(caminho.parent)
        java.nio.file.Files.writeString(caminho, java.util.Base64.getEncoder().encodeToString(novo))
        org.slf4j.LoggerFactory.getLogger(javaClass).info("Chave de assinatura dos logins gerada em {}", caminho)
        return novo
    }

    /** Sessao da pessoa. Com `personificadoPor`, e o administrador agindo como ela. */
    fun gerarToken(usuario: Usuario, personificadoPor: Usuario? = null): String {
        val agora = System.currentTimeMillis()
        return Jwts.builder()
            .subject(usuario.id.toString())
            .claim("personificadoPor", personificadoPor?.id)
            .claim("sessao", usuario.versaoSessao)
            .claim("sessaoAdmin", personificadoPor?.versaoSessao)
            .claim("login", usuario.login)
            .claim("nome", usuario.nome)
            .claim("perfil", usuario.perfil.nome.name)
            .claim("setor", usuario.setor?.nome?.name)
            .issuedAt(Date(agora))
            .expiration(Date(agora + horasDeSessao(usuario) * 3_600_000))
            .signWith(chave)
            .compact()
    }

    /**
     * Quem e a sessao e, se for o caso, o administrador que esta agindo por ela. Tokens de
     * antes da V15 nao tem versao de sessao: valem como 0, que e o valor inicial de todos.
     */
    fun ler(token: String): TokenLido? = runCatching {
        val dados = Jwts.parser().verifyWith(chave).build().parseSignedClaims(token).payload
        TokenLido(
            usuarioId = dados.subject.toInt(),
            personificadoPor = (dados["personificadoPor"] as? Number)?.toInt(),
            sessao = (dados["sessao"] as? Number)?.toInt() ?: 0,
            sessaoAdmin = (dados["sessaoAdmin"] as? Number)?.toInt() ?: 0
        )
    }.getOrNull()

    /**
     * Uma TV (Personalizado so com secoes de tela) fica ligada o dia todo, todo dia: a sessao
     * dela dura 30 dias, para ninguem ter de subir numa escada para entrar de novo. As outras
     * seguem a configuracao (12 horas).
     */
    fun horasDeSessao(usuario: Usuario): Long =
        if (usuario.perfil.nome == PerfilNome.PERSONALIZADO && usuario.secoes.isNotEmpty() && usuario.secoes.all { it.deTela }) {
            HORAS_DE_TELA
        } else expiracaoHoras

    fun expiracaoSegundos(usuario: Usuario): Long = horasDeSessao(usuario) * 3600

    private companion object {
        const val HORAS_DE_TELA = 30L * 24
    }
}

@Component
class JwtAuthFilter(
    private val jwtService: JwtService,
    private val usuarioRepository: UsuarioRepository,
    @Value("\${rastros.personificacao.habilitada:true}") private val personificacaoHabilitada: Boolean
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val header = request.getHeader("Authorization")
        if (header != null && header.startsWith("Bearer ") && SecurityContextHolder.getContext().authentication == null) {
            val lido = jwtService.ler(header.removePrefix("Bearer ").trim())
            // Administrador agindo como outra pessoa: so vale com a funcao ligada, com o
            // administrador ainda ativo e sem ter trocado a senha desde entao. Senao a sessao
            // simplesmente nao autentica.
            val admin = lido?.personificadoPor?.let { id ->
                usuarioRepository.findById(id).orElse(null)?.takeIf {
                    personificacaoHabilitada && it.ativo && it.perfil.nome == PerfilNome.ADMIN &&
                        it.versaoSessao == lido.sessaoAdmin
                }
            }
            val valido = lido != null && (lido.personificadoPor == null || admin != null)
            val usuarioId = lido?.usuarioId?.takeIf { valido }
            if (usuarioId != null) {
                // Senha trocada ou redefinida depois deste login: o token velho nao vale mais.
                val usuario = usuarioRepository.findById(usuarioId).orElse(null)
                    ?.takeIf { it.versaoSessao == lido.sessao }
                // A senha provisoria e da pessoa; o administrador agindo por ela nao e barrado.
                if (usuario != null && usuario.ativo && usuario.trocarSenha && admin == null &&
                    request.requestURI !in LIBERADO_COM_SENHA_PROVISORIA
                ) {
                    // Senha provisoria (123456 ou definida pelo administrador): antes de tudo,
                    // a pessoa escolhe a propria. O bloqueio e aqui, e nao so na tela.
                    response.status = HttpServletResponse.SC_FORBIDDEN
                    response.contentType = "application/json;charset=UTF-8"
                    response.writer.write(
                        """{"erro":"Troque a senha provisoria antes de continuar.","codigo":"TROCAR_SENHA"}"""
                    )
                    return
                }
                if (usuario != null && usuario.ativo) {
                    val setorEscolhido = request.getHeader(CABECALHO_SETOR)?.trim()?.toIntOrNull()
                    val principal = usuario.paraAutenticado(setorEscolhido).copy(personificadoPor = admin?.id)
                    val auth = UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.autoridades
                    )
                    auth.details = usuario.id
                    SecurityContextHolder.getContext().authentication = auth
                }
            }
        }
        filterChain.doFilter(request, response)
    }

    private companion object {
        val LIBERADO_COM_SENHA_PROVISORIA = setOf("/api/auth/me", "/api/auth/trocar-senha")
        /** O setor escolhido em Meu setor, por quem trabalha em mais de um. */
        const val CABECALHO_SETOR = "X-Setor"
    }
}
