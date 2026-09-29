package com.rastros.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.rastros.TesteIntegracao
import com.rastros.domain.PerfilNome
import com.rastros.security.JwtService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import java.nio.file.Path

@AutoConfigureMockMvc
class AutenticacaoTest : TesteIntegracao() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var admin: AdminController

    private fun login(usuario: String, senha: String): String {
        val resposta = mvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("usuario" to usuario, "senha" to senha)))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return json.readTree(resposta)["token"].asText()
    }

    private fun trocar(token: String, atual: String, nova: String) = mvc.perform(
        post("/api/auth/trocar-senha").header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(mapOf("senhaAtual" to atual, "novaSenha" to nova)))
    )

    @Test
    fun `quem esta com a senha padrao so consegue trocar a senha`() {
        val token = login("diretoria", "123456")

        mvc.perform(get("/api/painel").header("Authorization", "Bearer $token"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.codigo").value("TROCAR_SENHA"))
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.trocarSenha").value(true))
    }

    @Test
    fun `trocar a senha libera o sistema`() {
        val token = login("diretoria", "123456")

        val resposta = trocar(token, "123456", "Adesivo-2026").andExpect(status().isOk)
            .andExpect(jsonPath("$.usuario.trocarSenha").value(false))
            .andReturn().response.contentAsString
        val novo = json.readTree(resposta)["token"].asText()

        mvc.perform(get("/api/painel").header("Authorization", "Bearer $novo")).andExpect(status().isOk)
        login("diretoria", "Adesivo-2026")
    }

    @Test
    fun `senha fraca, igual ao email ou com a atual errada e recusada`() {
        val token = login("vendedor", "123456")

        trocar(token, "123456", "12345678").andExpect(status().isUnprocessableEntity)
        trocar(token, "123456", "curta").andExpect(status().isUnprocessableEntity)
        trocar(token, "123456", "vendedor").andExpect(status().isUnprocessableEntity)
        trocar(token, "errada", "Adesivo-2026").andExpect(status().isUnprocessableEntity)
    }

    @Test
    fun `senha definida pelo administrador e provisoria`() {
        val criado = admin.criarUsuario(
            UsuarioRequest(nome = "Novo", login = "novo", senha = "inicial1", perfil = PerfilNome.VENDEDOR),
            autor("admin@rastros.cloud")
        )

        assertThat(criado.trocarSenha).isTrue()
    }

    @Test
    fun `entra pelo nome de usuario, sem diferenciar maiusculas, e o e-mail antigo ainda funciona`() {
        login("Vendedor", "123456")
        login("vendedor@rastros.cloud", "123456")
        mvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("usuario" to "ninguem", "senha" to "123456")))
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `usuario com espaco ou acento e recusado, e nao pode repetir`() {
        val admin = autor("admin@rastros.cloud")
        mvc.perform(
            post("/api/admin/usuarios").header("Authorization", "Bearer ${tokenSemSenhaProvisoria("admin")}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("nome" to "X", "login" to "joão silva", "senha" to "provisoria1", "perfil" to "VENDEDOR")))
        ).andExpect(status().isBadRequest)
        org.assertj.core.api.Assertions.assertThatThrownBy {
            this.admin.criarUsuario(UsuarioRequest(nome = "Outro", login = "vendedor", senha = "x1234567", perfil = PerfilNome.VENDEDOR), admin)
        }.isInstanceOf(com.rastros.service.RegraDeNegocioException::class.java)
    }

    @Autowired lateinit var jwt: JwtService

    private fun tokenSemSenhaProvisoria(login: String): String {
        val usuario = usuarioRepository.findByLoginIgnoreCase(login)!!
        usuario.trocarSenha = false
        return jwt.gerarToken(usuarioRepository.save(usuario))
    }

    @Test
    fun `a api continua protegida e rota da tela nao pede login`() {
        mvc.perform(get("/api/agenda/colunas")).andExpect(status().isUnauthorized)
        val status = mvc.perform(get("/agenda")).andReturn().response.status
        assertThat(status).isNotEqualTo(401)
    }

    @Test
    fun `sem JWT_SECRET a chave e gerada uma vez e reaproveitada depois`(@TempDir pasta: Path) {
        val arquivo = pasta.resolve("jwt.secret").toString()
        val primeira = JwtService("", arquivo, 12)
        val token = primeira.gerarToken(usuarioRepository.findByEmailIgnoreCase("admin@rastros.cloud")!!)

        val depoisDoReinicio = JwtService("", arquivo, 12)

        assertThat(Files.exists(Path.of(arquivo))).isTrue()
        assertThat(depoisDoReinicio.ler(token)).isNotNull()
        assertThat(JwtService("", pasta.resolve("outra.secret").toString(), 12).ler(token)).isNull()
    }

    @Test
    fun `trocar a senha derruba as outras sessoes da pessoa`() {
        val celular = login("diretoria", "123456")
        val computador = login("diretoria", "123456")

        val resposta = trocar(computador, "123456", "Adesivo-2026").andExpect(status().isOk)
            .andReturn().response.contentAsString
        val novo = json.readTree(resposta)["token"].asText()

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer $celular")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer $computador")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/painel").header("Authorization", "Bearer $novo")).andExpect(status().isOk)
    }

    @Test
    fun `senha redefinida pelo administrador derruba quem estava logado`() {
        val sessao = tokenSemSenhaProvisoria("vendedor")
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer $sessao")).andExpect(status().isOk)

        val vendedor = usuarioRepository.findByLoginIgnoreCase("vendedor")!!
        admin.atualizarUsuario(
            vendedor.id!!,
            UsuarioRequest(nome = vendedor.nome, login = vendedor.login, senha = "Redefinida-1", perfil = PerfilNome.VENDEDOR),
            autor("admin@rastros.cloud")
        )

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer $sessao")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `editar a pessoa sem mexer na senha mantem a sessao`() {
        val sessao = tokenSemSenhaProvisoria("vendedor")
        val vendedor = usuarioRepository.findByLoginIgnoreCase("vendedor")!!
        admin.atualizarUsuario(
            vendedor.id!!,
            UsuarioRequest(nome = "Outro nome", login = vendedor.login, perfil = PerfilNome.VENDEDOR),
            autor("admin@rastros.cloud")
        )

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer $sessao")).andExpect(status().isOk)
    }
}
