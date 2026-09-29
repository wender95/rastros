package com.rastros.service

import com.rastros.TesteIntegracao
import com.rastros.api.NovaParteRequest
import com.rastros.api.NovoAgendamentoRequest
import com.rastros.domain.Adesivador
import com.rastros.domain.StatusAgendamento
import com.rastros.repository.AdesivadorRepository
import com.rastros.repository.AgendamentoRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

/**
 * A legenda de status editada na agenda: os do sistema mudam de nome e cor; os criados na
 * agenda sao etiquetas de servico que ainda nao comecou, e podem sair.
 */
class StatusDaAgendaTest : TesteIntegracao() {

    @Autowired lateinit var configuracao: ConfiguracaoAgendaService
    @Autowired lateinit var agenda: AgendaService
    @Autowired lateinit var adesivadores: AdesivadorRepository
    @Autowired lateinit var agendamentos: AgendamentoRepository

    private val segunda = LocalDate.of(2030, 3, 4)
    private lateinit var coluna: Adesivador

    @BeforeEach
    fun preparar() {
        coluna = adesivadores.save(Adesivador(nome = "STATUS-TESTE", ordem = 960))
    }

    private fun criar(faixa: Int = 2) = agenda.criar(
        NovoAgendamentoRequest(
            data = segunda, adesivadorId = coluna.id, slotInicio = faixa,
            horasEstimadas = BigDecimal.ONE, descricao = "TAXI $faixa"
        ),
        autor()
    ).id

    private fun daColuna() = agendamentos.findAll().filter { it.adesivador.id == coluna.id }

    @Test
    fun `os seis do sistema vem prontos, mudam de nome e cor e nao saem`() {
        val lista = configuracao.status()
        assertThat(lista.mapNotNull { it.chave }).containsExactly(*StatusAgendamento.entries.toTypedArray())

        val executando = lista.first { it.chave == StatusAgendamento.EXECUTANDO }
        val mudado = configuracao.alterarStatus(executando.id, "Em execução", "#FF8800", autor())
        assertThat(mudado.nome).isEqualTo("Em execução")
        assertThat(mudado.cor).isEqualTo("#ff8800")

        assertThatThrownBy { configuracao.removerStatus(executando.id, autor()) }
            .isInstanceOf(RegraDeNegocioException::class.java)
        assertThatThrownBy { configuracao.alterarStatus(executando.id, "X", "laranja", autor()) }
            .isInstanceOf(RegraDeNegocioException::class.java)
    }

    @Test
    fun `status criado vale para todas as copias, sai ao iniciar e, removido, devolve o card para programado`() {
        val aguardando = configuracao.adicionarStatus("Aguardando material", "#0ea5e9", autor())
        val original = criar(2)
        agenda.replicar(original, listOf(NovaParteRequest(segunda, coluna.id, 3, BigDecimal.ONE)), autor())
        agenda.alterarStatus(original, StatusAgendamento.EXECUTANDO, autor())

        val resposta = agenda.alterarStatus(original, StatusAgendamento.CONCLUIDO, autor(), aguardando.id)
        assertThat(resposta.status).isEqualTo(StatusAgendamento.PROGRAMADO)
        assertThat(resposta.etiquetaId).isEqualTo(aguardando.id)
        assertThat(daColuna().map { it.etiquetaId }).containsOnly(aguardando.id)

        // Um status do sistema tira a etiqueta, de todas as partes.
        agenda.alterarStatus(original, StatusAgendamento.EXECUTANDO, autor())
        assertThat(daColuna().map { it.etiquetaId }).containsOnlyNulls()

        agenda.alterarStatusDeVarios(listOf(original), StatusAgendamento.PROGRAMADO, autor(), aguardando.id)
        assertThat(daColuna().map { it.etiquetaId }).containsOnly(aguardando.id)

        val remocao = configuracao.removerStatus(aguardando.id, autor())
        assertThat(remocao.mensagem).contains("2 card(s)")
        agendamentos.flush()
        daColuna().forEach { agendamentos.findById(it.id!!).get().also { a -> assertThat(a.etiquetaId).isNull() } }
        assertThat(configuracao.status().map { it.nome }).doesNotContain("Aguardando material")
    }

    @Test
    fun `desfazer volta a etiqueta, e so a Diretoria e o Administrador editam a legenda`() {
        val reagendar = configuracao.adicionarStatus("Reagendar", "#ef4444", autor())
        val id = criar(4)
        agenda.alterarStatus(id, StatusAgendamento.PROGRAMADO, autor(), reagendar.id)
        agenda.alterarStatus(id, StatusAgendamento.EM_PATIO, autor())

        agenda.desfazer(autor())
        assertThat(agendamentos.findById(id).get().etiquetaId).isEqualTo(reagendar.id)

        assertThatThrownBy { configuracao.adicionarStatus("Outro", "#000000", autor("vendedor@rastros.cloud")) }
            .isInstanceOf(PermissaoNegadaException::class.java)
    }
}
