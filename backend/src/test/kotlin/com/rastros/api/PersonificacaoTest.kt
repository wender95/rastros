package com.rastros.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.rastros.TesteIntegracao
import com.rastros.domain.SetorNome
import com.rastros.repository.FluxoOsRepository
import com.rastros.repository.SetorRepository
import com.rastros.security.JwtService
import com.rastros.service.FluxoService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.HttpMethod
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request

/**
 * Fase de teste: o administrador "age como" outra pessoa, sem a senha dela, para
 * movimentar as OS e os projetos livremente.
 */
@AutoConfigureMockMvc
class PersonificacaoTest : TesteIntegracao() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var fluxos: FluxoService
    @Autowired lateinit var fluxoRepository: FluxoOsRepository
    @Autowired lateinit var setores: SetorRepository

    private fun tokenDe(email: String): String {
        val usuario = usuarioRepository.findByEmailIgnoreCase(email)!!
        usuario.trocarSenha = false
        return jwt.gerarToken(usuarioRepository.save(usuario))
    }

    private fun chamar(token: String, metodo: HttpMethod, rota: String): Pair<Int, JsonNode?> {
        val resposta = mvc.perform(request(metodo, rota).header("Authorization", "Bearer $token")).andReturn().response
        return resposta.status to resposta.contentAsString.takeIf { it.isNotBlank() }?.let { json.readTree(it) }
    }

    private fun idDe(email: String) = usuarioRepository.findByEmailIgnoreCase(email)!!.id!!

    /** O admin passa a agir como o Heitor (Frota). O Heitor ainda esta com senha provisoria. */
    private fun agirComoHeitor(): String {
        val (status, corpo) = chamar(tokenDe("admin@rastros.cloud"), HttpMethod.POST, "/api/admin/personificar/${idDe("frota@rastros.cloud")}")
        assertThat(status).isEqualTo(200)
        return corpo!!["token"].asText()
    }

    @Test
    fun `o admin age como o adesivador e a sessao diz quem esta por tras`() {
        val token = agirComoHeitor()

        val (status, eu) = chamar(token, HttpMethod.GET, "/api/auth/me")

        assertThat(status).isEqualTo(200)
        assertThat(eu!!["nome"].asText()).isEqualTo("Heitor Frota")
        assertThat(eu["setor"].asText()).isEqualTo("FROTA")
        assertThat(eu["agindoPor"].asText()).isEqualTo("Ana Admin")
    }

    @Test
    fun `agindo como a Frota, o admin recebe a OS em nome dela, mesmo com a senha provisoria dela`() {
        val fluxo = fluxos.criarOrdem(
            NovaOrdemRequest(
                numeroOsErp = "PERS-1", cliente = "Cliente",
                fluxos = listOf(NovoFluxoRequest(setorInicialId = setores.findByNome(SetorNome.FROTA)!!.id!!))
            ),
            autor("vendedor@rastros.cloud")
        ).fluxos.single().id

        val (status, _) = chamar(agirComoHeitor(), HttpMethod.POST, "/api/fluxos/$fluxo/receber")

        assertThat(status).isEqualTo(200)
        assertThat(fluxoRepository.findById(fluxo).get().recebidoPor!!.nome).isEqualTo("Heitor Frota")
    }

    @Test
    fun `a sessao emprestada nao entra na administracao, e so o admin escolhe por quem agir`() {
        assertThat(chamar(agirComoHeitor(), HttpMethod.GET, "/api/admin/usuarios").first).isEqualTo(403)

        val (status, _) = chamar(
            tokenDe("diretoria@rastros.cloud"), HttpMethod.POST, "/api/admin/personificar/${idDe("frota@rastros.cloud")}"
        )
        assertThat(status).isEqualTo(403)
    }

    @Test
    fun `nao da para agir como outro administrador`() {
        val (status, corpo) = chamar(
            tokenDe("admin@rastros.cloud"), HttpMethod.POST, "/api/admin/personificar/${idDe("admin@rastros.cloud")}"
        )
        assertThat(status).isEqualTo(422)
        assertThat(corpo!!["erro"].asText()).contains("administrador")
    }
}
