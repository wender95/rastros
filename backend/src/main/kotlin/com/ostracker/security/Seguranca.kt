package com.ostracker.security

import com.ostracker.domain.PerfilNome
import com.ostracker.domain.SetorNome
import com.ostracker.domain.Usuario
import com.ostracker.repository.UsuarioRepository
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

/** Identidade do usuario autenticado, montada a partir do JWT. */
data class UsuarioAutenticado(
    val id: Int,
    val nome: String,
    val login: String,
    val email: String?,
    val perfil: PerfilNome,
    val setor: SetorNome?,
    val setorId: Int?
) {
    val podeVerTudo: Boolean
        get() = perfil == PerfilNome.VENDEDOR || perfil == PerfilNome.DIRETORIA || perfil == PerfilNome.ADMIN
}

fun Usuario.paraAutenticado() = UsuarioAutenticado(
    id = this.id!!,
    nome = this.nome,
    login = this.login,
    email = this.email,
    perfil = this.perfil.nome,
    setor = this.setor?.nome,
    setorId = this.setor?.id
)

@Service
class JwtService(
    @Value("\${ostracker.jwt.secret:}") segredoConfigurado: String,
    @Value("\${ostracker.jwt.arquivo-segredo}") arquivoSegredo: String,
    @Value("\${ostracker.jwt.expiracao-horas}") private val expiracaoHoras: Long
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

    fun gerarToken(usuario: Usuario): String {
        val agora = System.currentTimeMillis()
        return Jwts.builder()
            .subject(usuario.id.toString())
            .claim("login", usuario.login)
            .claim("nome", usuario.nome)
            .claim("perfil", usuario.perfil.nome.name)
            .claim("setor", usuario.setor?.nome?.name)
            .issuedAt(Date(agora))
            .expiration(Date(agora + expiracaoHoras * 3_600_000))
            .signWith(chave)
            .compact()
    }

    fun extrairUsuarioId(token: String): Int? = runCatching {
        Jwts.parser().verifyWith(chave).build()
            .parseSignedClaims(token).payload.subject.toInt()
    }.getOrNull()

    fun expiracaoSegundos(): Long = expiracaoHoras * 3600
}

@Component
class JwtAuthFilter(
    private val jwtService: JwtService,
    private val usuarioRepository: UsuarioRepository
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val header = request.getHeader("Authorization")
        if (header != null && header.startsWith("Bearer ") && SecurityContextHolder.getContext().authentication == null) {
            val usuarioId = jwtService.extrairUsuarioId(header.removePrefix("Bearer ").trim())
            if (usuarioId != null) {
                val usuario = usuarioRepository.findById(usuarioId).orElse(null)
                if (usuario != null && usuario.ativo && usuario.trocarSenha && request.requestURI !in LIBERADO_COM_SENHA_PROVISORIA) {
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
                    val principal = usuario.paraAutenticado()
                    val auth = UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        listOf(SimpleGrantedAuthority("ROLE_${principal.perfil.name}"))
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
    }
}
