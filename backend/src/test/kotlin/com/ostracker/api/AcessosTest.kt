package com.ostracker.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.ostracker.TesteIntegracao
import com.ostracker.domain.PerfilNome
import com.ostracker.domain.SetorNome
import com.ostracker.repository.SetorRepository
import com.ostracker.security.JwtService
import org.assertj.core.api.SoftAssertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request

/**
 * Quem ve o que, conferido pela porta de entrada (HTTP), e nao so pelo menu da tela:
 *
 * | Perfil      | Pode                                                    |
 * | Admin       | tudo                                                    |
 * | Diretoria   | tudo do nivel de usuario (sem a Administracao)          |
 * | Comercial   | painel, agenda, consultar OS, criar OS                  |
 * | Financeiro  | painel, usuarios (sem criar administrador) e o setor Financeiro: recebe e conclui |
 * | Operacional | so a tela do setor: receber, devolver, despachar        |
 */
@AutoConfigureMockMvc
class AcessosTest : TesteIntegracao() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var setores: SetorRepository

    private val tokens = mutableMapOf<String, String>()

    @BeforeEach
    fun preparar() {
        // Sem a senha provisoria, que bloquearia tudo antes de chegar nas regras de perfil.
        mapOf(
            "ADMIN" to "admin@ostracker.com",
            "DIRETORIA" to "diretoria@ostracker.com",
            "COMERCIAL" to "vendedor@ostracker.com",
            "FINANCEIRO" to "financeiro@ostracker.com",
            "OPERACIONAL" to "impressao@ostracker.com"
        ).forEach { (quem, email) ->
            val usuario = usuarioRepository.findByEmailIgnoreCase(email)!!
            usuario.trocarSenha = false
            tokens[quem] = jwt.gerarToken(usuarioRepository.save(usuario))
        }
    }

    private fun status(quem: String, metodo: HttpMethod, rota: String, corpo: Any? = null): Int =
        mvc.perform(
            request(metodo, rota).header("Authorization", "Bearer ${tokens[quem]}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo?.let { json.writeValueAsString(it) } ?: "")
        ).andReturn().response.status

    /** Liberado = qualquer resposta que nao seja 401/403 (a rota pode ate recusar os dados). */
    private fun liberado(quem: String, metodo: HttpMethod, rota: String, corpo: Any? = null) =
        status(quem, metodo, rota, corpo) !in setOf(401, 403)

    @Test
    fun `cada perfil ve so as areas dele`() {
        val todos = listOf("ADMIN", "DIRETORIA", "COMERCIAL", "FINANCEIRO", "OPERACIONAL")
        val areas = mapOf(
            "painel" to (HttpMethod.GET to "/api/painel") to setOf("ADMIN", "DIRETORIA", "COMERCIAL", "FINANCEIRO"),
            "agenda" to (HttpMethod.GET to "/api/agenda/colunas") to setOf("ADMIN", "DIRETORIA", "COMERCIAL"),
            "editar adesivadores" to (HttpMethod.DELETE to "/api/agenda/colunas/999999") to setOf("ADMIN", "DIRETORIA"),
            "reordenar adesivadores" to (HttpMethod.PUT to "/api/agenda/colunas/ordem") to setOf("ADMIN", "DIRETORIA"),
            "patio e prateleira" to (HttpMethod.GET to "/api/fluxos/em-espera") to setOf("ADMIN", "DIRETORIA", "COMERCIAL"),
            "consultar OS" to (HttpMethod.GET to "/api/fluxos") to setOf("ADMIN", "DIRETORIA", "COMERCIAL"),
            "produtividade" to (HttpMethod.GET to "/api/produtividade") to setOf("ADMIN", "DIRETORIA"),
            "relatorio" to (HttpMethod.GET to "/api/produtividade/semanal") to setOf("ADMIN", "DIRETORIA"),
            "usuarios" to (HttpMethod.GET to "/api/admin/usuarios") to setOf("ADMIN", "FINANCEIRO"),
            "excluir usuario" to (HttpMethod.DELETE to "/api/admin/usuarios/999999") to setOf("ADMIN", "FINANCEIRO"),
            "feriados" to (HttpMethod.GET to "/api/admin/feriados") to setOf("ADMIN"),
            "matriz" to (HttpMethod.GET to "/api/admin/transicoes") to setOf("ADMIN"),
            "backups" to (HttpMethod.GET to "/api/admin/backups") to setOf("ADMIN"),
            "tela do setor" to (HttpMethod.GET to "/api/movimentacao") to setOf("OPERACIONAL", "FINANCEIRO"),
            "receber" to (HttpMethod.POST to "/api/fluxos/999999/receber") to setOf("OPERACIONAL", "FINANCEIRO"),
            "devolver" to (HttpMethod.POST to "/api/fluxos/999999/devolver") to setOf("OPERACIONAL", "FINANCEIRO"),
            "concluir" to (HttpMethod.POST to "/api/fluxos/999999/concluir") to setOf("FINANCEIRO")
        )

        val soft = SoftAssertions()
        areas.forEach { (area, quemPode) ->
            val (nome, rota) = area
            todos.forEach { quem ->
                soft.assertThat(liberado(quem, rota.first, rota.second))
                    .describedAs("$quem em $nome")
                    .isEqualTo(quem in quemPode)
            }
        }
        soft.assertAll()
    }

    @Test
    fun `abrir OS e do comercial, da diretoria e do administrador`() {
        val criacao = setores.findByNome(SetorNome.CRIACAO)!!.id
        fun nova(numero: String) = mapOf("numeroOsErp" to numero, "fluxos" to listOf(mapOf("setorInicialId" to criacao)))

        val soft = SoftAssertions()
        soft.assertThat(status("COMERCIAL", HttpMethod.POST, "/api/ordens", nova("AC-1"))).isEqualTo(201)
        soft.assertThat(status("DIRETORIA", HttpMethod.POST, "/api/ordens", nova("AC-2"))).isEqualTo(201)
        soft.assertThat(status("ADMIN", HttpMethod.POST, "/api/ordens", nova("AC-3"))).isEqualTo(201)
        soft.assertThat(status("FINANCEIRO", HttpMethod.POST, "/api/ordens", nova("AC-4"))).isEqualTo(403)
        soft.assertThat(status("OPERACIONAL", HttpMethod.POST, "/api/ordens", nova("AC-5"))).isEqualTo(403)
        soft.assertAll()
    }

    @Test
    fun `o financeiro cadastra usuarios mas nao administradores`() {
        fun usuario(login: String, perfil: String) =
            mapOf("nome" to "Teste", "login" to login, "senha" to "provisoria1", "perfil" to perfil)

        val soft = SoftAssertions()
        soft.assertThat(status("FINANCEIRO", HttpMethod.POST, "/api/admin/usuarios", usuario("comercial.novo", "VENDEDOR")))
            .isEqualTo(201)
        soft.assertThat(status("FINANCEIRO", HttpMethod.POST, "/api/admin/usuarios", usuario("admin.novo", "ADMIN")))
            .isEqualTo(403)
        val admin = usuarioRepository.findByEmailIgnoreCase("admin@ostracker.com")!!
        soft.assertThat(
            status("FINANCEIRO", HttpMethod.PUT, "/api/admin/usuarios/${admin.id}", usuario(admin.login, "ADMIN"))
        ).isEqualTo(403)
        soft.assertThat(status("ADMIN", HttpMethod.POST, "/api/admin/usuarios", usuario("admin.outro", "ADMIN")))
            .isEqualTo(201)
        soft.assertAll()
    }
}
