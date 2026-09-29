package com.rastros.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.rastros.TesteIntegracao
import com.rastros.domain.PerfilNome
import com.rastros.domain.SecaoDoSistema
import com.rastros.domain.Usuario
import com.rastros.repository.PerfilRepository
import com.rastros.security.JwtService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request

/**
 * Secoes por pessoa: o perfil traz as dele prontas e o cadastro acrescenta ou tira. Conferido
 * pela porta de entrada (HTTP), que e quem barra de verdade.
 */
@AutoConfigureMockMvc
class SecoesTest : TesteIntegracao() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var perfis: PerfilRepository
    @Autowired lateinit var setores: com.rastros.repository.SetorRepository

    private fun token(usuario: Usuario) = jwt.gerarToken(usuarioRepository.save(usuario.also { it.trocarSenha = false }))

    private fun status(token: String, metodo: HttpMethod, rota: String, corpo: Any? = null): Int =
        mvc.perform(
            request(metodo, rota).header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo?.let { json.writeValueAsString(it) } ?: "")
        ).andReturn().response.status

    private fun usuario(login: String, perfil: PerfilNome) = usuarioRepository.save(
        Usuario(nome = login.uppercase(), login = login, senhaHash = "x", perfil = perfis.findByNome(perfil)!!)
    )

    @Test
    fun `a TV do perfil Personalizado so ve a agenda do dia dos adesivadores`() {
        val tv = usuario("tv-frota", PerfilNome.PERSONALIZADO)
        tv.definirSecoes(setOf(SecaoDoSistema.PAINEL_ADESIVADORES))
        val t = token(tv)

        assertThat(status(t, HttpMethod.GET, "/api/painel/agendas")).isEqualTo(200)
        assertThat(status(t, HttpMethod.GET, "/api/painel/clima")).isIn(200, 204)
        assertThat(status(t, HttpMethod.GET, "/api/painel/acabamento")).isEqualTo(403)
        assertThat(status(t, HttpMethod.GET, "/api/painel")).isEqualTo(403)
        assertThat(status(t, HttpMethod.GET, "/api/agenda/colunas")).isEqualTo(403)
        assertThat(status(t, HttpMethod.GET, "/api/fluxos")).isEqualTo(403)
        // A TV fica ligada o dia todo: a sessao dura 30 dias.
        assertThat(jwt.expiracaoSegundos(tv)).isEqualTo(30L * 24 * 3600)
    }

    @Test
    fun `tirar uma secao do Comercial barra so aquilo, e o resto do perfil continua`() {
        val vendedor = usuario("vend-sem-os", PerfilNome.VENDEDOR)
        vendedor.definirSecoes(SecaoDoSistema.padraoDo(PerfilNome.VENDEDOR) - SecaoDoSistema.CRIAR_OS)
        val t = token(vendedor)

        assertThat(status(t, HttpMethod.POST, "/api/ordens", mapOf("numeroOsErp" to "777", "cliente" to "X"))).isEqualTo(403)
        assertThat(status(t, HttpMethod.GET, "/api/agenda/colunas")).isEqualTo(200)
        assertThat(status(t, HttpMethod.GET, "/api/painel")).isEqualTo(200)
        assertThat(vendedor.ajustesDeSecao).containsExactlyEntriesOf(mapOf(SecaoDoSistema.CRIAR_OS to false))
    }

    @Test
    fun `dar a agenda para alguem da producao libera ver a agenda, mas nao mexer nela`() {
        val criacao = usuario("criacao-agenda", PerfilNome.OPERACIONAL)
        criacao.definirSecoes(setOf(SecaoDoSistema.AGENDA_VER))
        val t = token(criacao)

        assertThat(status(t, HttpMethod.GET, "/api/agenda/colunas")).isEqualTo(200)
        assertThat(status(t, HttpMethod.POST, "/api/agenda/desfazer")).isEqualTo(403)
        assertThat(status(t, HttpMethod.GET, "/api/produtividade")).isEqualTo(403)
    }

    @Test
    fun `o cadastro guarda as secoes e a sessao da pessoa ja traz as novas`() {
        val admin = usuarioRepository.findByLoginIgnoreCase("admin")!!.also { it.trocarSenha = false }
        val t = token(admin)
        val corpo = mapOf(
            "nome" to "TV Acabamento", "login" to "tv-acabamento", "senha" to "Tv-Acab-2026",
            "perfil" to "PERSONALIZADO", "secoes" to listOf("PAINEL_ACABAMENTO")
        )
        val criado = mvc.perform(
            request(HttpMethod.POST, "/api/admin/usuarios").header("Authorization", "Bearer $t")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(corpo))
        ).andReturn().response
        assertThat(criado.status).isEqualTo(201)
        assertThat(json.readTree(criado.contentAsString)["secoes"].map { it.asText() }).containsExactly("PAINEL_ACABAMENTO")

        val semSecao = corpo + ("login" to "tv-vazia") + ("secoes" to emptyList<String>())
        assertThat(status(t, HttpMethod.POST, "/api/admin/usuarios", semSecao)).isEqualTo(422)
    }

    @Test
    fun `o Administrador muda o padrao do perfil e vale na hora para todo mundo dele`() {
        val admin = token(usuarioRepository.findByLoginIgnoreCase("admin")!!)
        val vendedor = token(usuarioRepository.findByEmailIgnoreCase("vendedor@rastros.cloud")!!)
        assertThat(status(vendedor, HttpMethod.GET, "/api/produtividade")).isEqualTo(403)

        val novas = SecaoDoSistema.padraoDo(PerfilNome.VENDEDOR) + SecaoDoSistema.PRODUTIVIDADE
        assertThat(status(admin, HttpMethod.PUT, "/api/admin/perfis/VENDEDOR/secoes", novas)).isEqualTo(200)
        assertThat(status(vendedor, HttpMethod.GET, "/api/produtividade")).isEqualTo(200)
        // O Administrador nao se tranca para fora.
        assertThat(status(admin, HttpMethod.PUT, "/api/admin/perfis/ADMIN/secoes", emptyList<String>())).isEqualTo(422)
    }

    @Test
    fun `o Financeiro cadastra, mas nao mexe em permissoes`() {
        val financeiro = token(usuarioRepository.findByEmailIgnoreCase("financeiro@rastros.cloud")!!)
        assertThat(status(financeiro, HttpMethod.PUT, "/api/admin/perfis/VENDEDOR/secoes", listOf("PAINEL"))).isEqualTo(403)

        val comSecoes = mapOf(
            "nome" to "Novo Comercial", "login" to "novo-comercial", "senha" to "Novo-Com-2026",
            "perfil" to "VENDEDOR", "secoes" to listOf("PRODUTIVIDADE")
        )
        assertThat(status(financeiro, HttpMethod.POST, "/api/admin/usuarios", comSecoes)).isEqualTo(201)
        val criado = usuarioRepository.findByLoginIgnoreCase("novo-comercial")!!
        assertThat(criado.ajustesDeSecao).isEmpty() // as secoes pedidas pelo Financeiro nao valem
        assertThat(criado.secoes).isEqualTo(SecaoDoSistema.padraoDo(PerfilNome.VENDEDOR))

        val tv = comSecoes + ("login" to "tv-do-financeiro") + ("perfil" to "PERSONALIZADO")
        assertThat(status(financeiro, HttpMethod.POST, "/api/admin/usuarios", tv)).isEqualTo(403)
    }

    @Test
    fun `o Financeiro redefine senha so de Operacional e Comercial, e nao promove ninguem`() {
        val contaDoFinanceiro = usuarioRepository.findByEmailIgnoreCase("financeiro@rastros.cloud")!!
        val financeiro = token(contaDoFinanceiro)
        val diretoria = usuario("dir-alvo", PerfilNome.DIRETORIA)
        val operador = usuario("op-alvo", PerfilNome.OPERACIONAL).also {
            it.setor = setores.findByNome(com.rastros.domain.SetorNome.RECORTE)
            usuarioRepository.save(it)
        }
        fun edicao(u: Usuario, perfil: String, senha: String? = "Nova-Senha-2026") = mapOf(
            "nome" to u.nome, "login" to u.login, "perfil" to perfil, "senha" to senha,
            "setorId" to u.setor?.id
        )

        // Redefinir a senha da Diretoria daria ao Financeiro o acesso dela.
        assertThat(status(financeiro, HttpMethod.PUT, "/api/admin/usuarios/${diretoria.id}", edicao(diretoria, "DIRETORIA"))).isEqualTo(403)
        // Promover alguem (ou a si mesmo) a Diretoria tambem nao.
        assertThat(status(financeiro, HttpMethod.PUT, "/api/admin/usuarios/${operador.id}", edicao(operador, "DIRETORIA"))).isEqualTo(403)
        assertThat(
            status(financeiro, HttpMethod.PUT, "/api/admin/usuarios/${contaDoFinanceiro.id}", edicao(contaDoFinanceiro, "FINANCEIRO", null))
        ).isEqualTo(403)
        assertThat(status(financeiro, HttpMethod.DELETE, "/api/admin/usuarios/${diretoria.id}")).isEqualTo(403)

        // O dia a dia continua: redefinir a senha de um operador.
        assertThat(status(financeiro, HttpMethod.PUT, "/api/admin/usuarios/${operador.id}", edicao(operador, "OPERACIONAL"))).isEqualTo(200)
        assertThat(usuarioRepository.findById(operador.id!!).get().trocarSenha).isTrue()
    }
}
