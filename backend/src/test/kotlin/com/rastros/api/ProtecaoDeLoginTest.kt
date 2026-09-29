package com.rastros.api

import com.rastros.TesteIntegracao
import com.rastros.domain.PerfilNome
import com.rastros.domain.Usuario
import com.rastros.repository.PerfilRepository
import com.rastros.security.ProtecaoDeLogin
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/** Na internet, o login tem freio: senha errada demais fecha a porta por 15 minutos. */
@AutoConfigureMockMvc
class ProtecaoDeLoginTest : TesteIntegracao() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var protecao: ProtecaoDeLogin
    @Autowired lateinit var perfis: PerfilRepository
    @Autowired lateinit var senhas: PasswordEncoder

    @AfterEach
    fun limpar() = protecao.esquecerTudo()

    private fun entrar(usuario: String, senha: String, endereco: String = "203.0.113.7") =
        mvc.perform(
            post("/api/auth/login")
                .header("CF-Connecting-IP", endereco)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"usuario":"$usuario","senha":"$senha"}""")
        ).andReturn().response

    private fun criarUsuario() = usuarioRepository.save(
        Usuario(
            nome = "Freio", login = "freio.teste", senhaHash = senhas.encode("Certa#2026x"),
            perfil = perfis.findByNome(PerfilNome.VENDEDOR)!!
        )
    )

    @Test
    fun `cinco senhas erradas fecham o login daquele usuario, ate com a senha certa`() {
        criarUsuario()
        repeat(ProtecaoDeLogin.LIMITE_POR_USUARIO) { assertThat(entrar("freio.teste", "errada").status).isEqualTo(403) }

        val bloqueado = entrar("freio.teste", "Certa#2026x")

        assertThat(bloqueado.status).isEqualTo(429)
        assertThat(bloqueado.contentAsString).contains("Muitas tentativas")
    }

    @Test
    fun `entrar certo zera o contador do usuario`() {
        criarUsuario()
        repeat(ProtecaoDeLogin.LIMITE_POR_USUARIO - 1) { entrar("freio.teste", "errada") }

        assertThat(entrar("freio.teste", "Certa#2026x").status).isEqualTo(200)
        assertThat(entrar("freio.teste", "errada").status).isEqualTo(403) // conta do zero de novo
    }

    @Test
    fun `um endereco tentando muitos usuarios tambem e barrado`() {
        repeat(ProtecaoDeLogin.LIMITE_POR_ENDERECO) { i -> entrar("ninguem$i", "x", endereco = "198.51.100.9") }

        assertThat(entrar("outro", "x", endereco = "198.51.100.9").status).isEqualTo(429)
        // Outro endereco continua podendo tentar.
        assertThat(entrar("outro", "x", endereco = "198.51.100.10").status).isEqualTo(403)
    }
}
