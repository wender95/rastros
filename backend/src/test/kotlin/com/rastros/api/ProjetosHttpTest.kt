package com.rastros.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.rastros.TesteIntegracao
import com.rastros.domain.Adesivador
import com.rastros.domain.Agendamento
import com.rastros.domain.SetorNome
import com.rastros.repository.AdesivadorRepository
import com.rastros.repository.AgendamentoRepository
import com.rastros.repository.OrdemServicoRepository
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
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Iniciar, concluir e o painel pela porta de entrada: os nomes dos campos que a tela le
 * (o Jackson escreveria "aCaminho" como "acaminho", e a tela nunca veria o aviso) e quem
 * pode chamar o que.
 */
@AutoConfigureMockMvc
class ProjetosHttpTest : TesteIntegracao() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var fluxos: FluxoService
    @Autowired lateinit var setores: SetorRepository
    @Autowired lateinit var adesivadores: AdesivadorRepository
    @Autowired lateinit var agendamentos: AgendamentoRepository
    @Autowired lateinit var ordens: OrdemServicoRepository

    private fun token(email: String): String {
        val usuario = usuarioRepository.findByEmailIgnoreCase(email)!!
        usuario.trocarSenha = false
        return jwt.gerarToken(usuarioRepository.save(usuario))
    }

    private fun chamar(email: String, metodo: HttpMethod, rota: String): Pair<Int, JsonNode?> {
        val resposta = mvc.perform(request(metodo, rota).header("Authorization", "Bearer ${token(email)}"))
            .andReturn().response
        return resposta.status to resposta.contentAsString.takeIf { it.isNotBlank() }?.let { json.readTree(it) }
    }

    /** Projeto do Heitor Frota ligado a uma OS que ainda esta na Impressao. */
    private fun projetoComOsNaImpressao(): Long {
        val coluna = adesivadores.save(Adesivador(nome = "HEITOR FROTA", ordem = 1))
        fluxos.criarOrdem(
            NovaOrdemRequest(
                numeroOsErp = "HTTP-1", cliente = "Cliente",
                fluxos = listOf(NovoFluxoRequest(setorInicialId = setores.findByNome(SetorNome.IMPRESSAO)!!.id!!))
            ),
            autor("vendedor@rastros.cloud")
        )
        return agendamentos.save(
            Agendamento(
                data = LocalDate.of(2030, 3, 4), adesivador = coluna, slotInicio = 1,
                horasEstimadas = BigDecimal("2"), descricao = "TAXI HTTP",
                ordemServico = ordens.findByNumeroOsErpIgnoreCase("HTTP-1").single()
            )
        ).id!!
    }

    @Test
    fun `iniciar e concluir respondem com os campos que a tela le`() {
        val id = projetoComOsNaImpressao()

        val (status, iniciado) = chamar("frota@rastros.cloud", HttpMethod.POST, "/api/minha-agenda/$id/iniciar")

        assertThat(status).isEqualTo(200)
        assertThat(iniciado!!.has("osACaminho")).isTrue()
        assertThat(iniciado["osACaminho"].asBoolean()).isTrue()
        assertThat(iniciado["status"].asText()).isEqualTo("EXECUTANDO")

        val (_, concluido) = chamar("frota@rastros.cloud", HttpMethod.POST, "/api/minha-agenda/$id/concluir")
        assertThat(concluido!!["status"].asText()).isEqualTo("CONCLUIDO")
        assertThat(concluido.has("paraOPatio")).isTrue()
    }

    @Test
    fun `projeto de outro adesivador nao se inicia pela API`() {
        val id = projetoComOsNaImpressao()
        adesivadores.save(Adesivador(nome = "Elisa Recorte", ordem = 2))

        val (status, _) = chamar("recorte@rastros.cloud", HttpMethod.POST, "/api/minha-agenda/$id/iniciar")

        assertThat(status).isEqualTo(403)
    }

    @Test
    fun `o painel ao vivo e da gestao e do comercial, nao do chao de fabrica`() {
        assertThat(chamar("diretoria@rastros.cloud", HttpMethod.GET, "/api/painel/agendas").first).isEqualTo(200)
        assertThat(chamar("vendedor@rastros.cloud", HttpMethod.GET, "/api/painel/acabamento").first).isEqualTo(200)
        assertThat(chamar("impressao@rastros.cloud", HttpMethod.GET, "/api/painel/agendas").first).isEqualTo(403)
        assertThat(chamar("frota@rastros.cloud", HttpMethod.GET, "/api/painel/acabamento").first).isEqualTo(403)
    }

    @Test
    fun `a lista do Acabamento traz as datas com os nomes que a tela usa`() {
        fluxos.criarOrdem(
            NovaOrdemRequest(
                numeroOsErp = "HTTP-AC", cliente = "Cliente", servico = "Placas",
                fluxos = listOf(NovoFluxoRequest(setorInicialId = setores.findByNome(SetorNome.ACABAMENTO)!!.id!!))
            ),
            autor("vendedor@rastros.cloud")
        )

        val (_, lista) = chamar("diretoria@rastros.cloud", HttpMethod.GET, "/api/painel/acabamento")

        val os = lista!!.first { it["numeroOsErp"].asText() == "HTTP-AC" }
        listOf("servico", "identificadorFluxo", "cliente", "dataOs", "chegouEm", "status").forEach {
            assertThat(os.has(it)).`as`(it).isTrue()
        }
    }
}
