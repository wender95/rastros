package com.rastros.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.rastros.TesteIntegracao
import com.rastros.domain.PerfilNome
import com.rastros.domain.SetorNome
import com.rastros.domain.Usuario
import com.rastros.repository.PerfilRepository
import com.rastros.repository.SetorRepository
import com.rastros.security.JwtService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put

/**
 * Um funcionario de mais de um setor (Recorte e Frota): em Meu setor ele escolhe em qual
 * esta trabalhando, e o servidor so aceita um setor que seja dele.
 */
@AutoConfigureMockMvc
class VariosSetoresTest : TesteIntegracao() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var perfis: PerfilRepository
    @Autowired lateinit var setores: SetorRepository

    private fun setor(nome: SetorNome) = setores.findByNome(nome)!!

    private fun recorteEFrota() = usuarioRepository.save(
        Usuario(
            nome = "Jose Recorte", login = "jose.recorte", senhaHash = "x", trocarSenha = false,
            perfil = perfis.findByNome(PerfilNome.OPERACIONAL)!!, setor = setor(SetorNome.RECORTE),
            outrosSetores = mutableSetOf(setor(SetorNome.FROTA))
        )
    )

    /** O setor que Meu setor mostra, escolhendo (ou nao) um setor. */
    private fun meuSetor(usuario: Usuario, escolhido: Int? = null): String {
        val pedido = get("/api/movimentacao").header("Authorization", "Bearer ${jwt.gerarToken(usuario)}")
        escolhido?.let { pedido.header("X-Setor", it) }
        val corpo = mvc.perform(pedido).andReturn().response.contentAsString
        return json.readTree(corpo)["setor"].asText()
    }

    @Test
    fun `sem escolher fica o setor principal, e escolhendo um dos dele vale o escolhido`() {
        val jose = recorteEFrota()

        assertThat(meuSetor(jose)).isEqualTo("RECORTE")
        assertThat(meuSetor(jose, setor(SetorNome.FROTA).id)).isEqualTo("FROTA")
        // Um setor que nao e dele nao vale: fica o principal.
        assertThat(meuSetor(jose, setor(SetorNome.IMPRESSAO).id)).isEqualTo("RECORTE")
    }

    @Test
    fun `o cadastro guarda os outros setores, e recusa Patio como setor de alguem`() {
        val jose = recorteEFrota()
        val admin = jwt.gerarToken(usuarioRepository.save(usuarioRepository.findByLoginIgnoreCase("admin")!!.also { it.trocarSenha = false }))
        fun salvar(outros: Set<Int>) = mvc.perform(
            put("/api/admin/usuarios/${jose.id}").header("Authorization", "Bearer $admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        mapOf(
                            "nome" to jose.nome, "login" to jose.login, "perfil" to "OPERACIONAL",
                            "setorId" to setor(SetorNome.RECORTE).id, "outrosSetoresIds" to outros
                        )
                    )
                )
        ).andReturn().response

        val salvo = salvar(setOf(setor(SetorNome.FROTA).id!!, setor(SetorNome.PREPARACAO).id!!))
        assertThat(salvo.status).isEqualTo(200)
        val setoresDele = json.readTree(salvo.contentAsString)["setores"].map { it["nome"].asText() }
        assertThat(setoresDele).containsExactly("RECORTE", "PREPARACAO", "FROTA")

        assertThat(salvar(setOf(setor(SetorNome.PATIO).id!!)).status).isEqualTo(422)
    }
}
