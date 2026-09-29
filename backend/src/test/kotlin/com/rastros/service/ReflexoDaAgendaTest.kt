package com.rastros.service

import com.rastros.TesteIntegracao
import com.rastros.api.NovaParteRequest
import com.rastros.domain.Adesivador
import com.rastros.domain.Agendamento
import com.rastros.domain.StatusAgendamento
import com.rastros.repository.AdesivadorRepository
import com.rastros.repository.AgendamentoRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

/**
 * O painel e a Minha agenda sao o reflexo da agenda: so aparece o que a agenda poe no dia.
 * O que o adesivador registrou (inicio, conclusao) vale para o servico inteiro, em todas as
 * copias - inclusive a criada depois, quando o escritorio estende o card para o dia seguinte.
 */
class ReflexoDaAgendaTest : TesteIntegracao() {

    @Autowired lateinit var minhaAgenda: MinhaAgendaService
    @Autowired lateinit var agenda: AgendaService
    @Autowired lateinit var produtividade: ProdutividadeService
    @Autowired lateinit var adesivadores: AdesivadorRepository
    @Autowired lateinit var agendamentos: AgendamentoRepository

    /** 04/03/2030 e segunda. */
    private val segunda = LocalDate.of(2030, 3, 4)
    private val terca = segunda.plusDays(1)
    private val heitor get() = autor("frota@rastros.cloud") // Heitor Frota tem a coluna dele
    private lateinit var coluna: Adesivador

    @BeforeEach
    fun preparar() {
        coluna = adesivadores.save(Adesivador(nome = "HEITOR FROTA", ordem = 1))
    }

    private fun projeto(dia: LocalDate, descricao: String) = agendamentos.save(
        Agendamento(data = dia, adesivador = coluna, slotInicio = 1, horasEstimadas = BigDecimal.ONE, descricao = descricao)
    ).id!!

    /** O escritorio estende o card para a terca (a alca do canto). */
    private fun estenderParaTerca(id: Long) = agenda.replicar(
        id, listOf(NovaParteRequest(data = terca, adesivadorId = coluna.id, slotInicio = 1, horasEstimadas = BigDecimal.ONE)), autor()
    )

    private fun noPainel(dia: LocalDate) = minhaAgenda.agendasDoDia(dia).single { it.adesivadorId == coluna.id }.carros

    private fun linhaDoRelatorio(id: Long) = produtividade.relatorio(segunda, terca).adesivadores
        .single { it.adesivadorId == coluna.id }.servicos.single { it.agendamentoId == id }

    @Test
    fun `iniciado na segunda e estendido para a terca, continua iniciado e no Agora na terca`() {
        val id = projeto(segunda, "KICKS CRENVI")
        minhaAgenda.iniciar(id, heitor)
        estenderParaTerca(id)

        val naTerca = noPainel(terca).single()
        assertThat(naTerca.status).isEqualTo(StatusAgendamento.EXECUTANDO)
        assertThat(naTerca.iniciadoEm).isNotNull()
        assertThat(naTerca.iniciadoPor).isNotNull()
        assertThat(naTerca.atual).isTrue()
        assertThat(minhaAgenda.doDia(terca, heitor).carros.single().atual).isTrue()
    }

    @Test
    fun `copia sem o inicio gravado (feita antes da correcao) tambem vale o inicio do servico`() {
        val id = projeto(segunda, "TRANSIT CRENVI")
        minhaAgenda.iniciar(id, heitor)
        agendamentos.save(agendamentos.findById(id).get().apply { grupoId = id })
        agendamentos.save(
            Agendamento(
                data = terca, adesivador = coluna, slotInicio = 1, horasEstimadas = BigDecimal.ONE,
                descricao = "TRANSIT CRENVI", status = StatusAgendamento.EXECUTANDO, grupoId = id
            )
        )

        val naTerca = noPainel(terca).single()
        assertThat(naTerca.iniciadoEm).isNotNull()
        assertThat(naTerca.atual).isTrue()
    }

    @Test
    fun `concluido direto na agenda sai do painel, e o relatorio deixa a conclusao em aberto`() {
        val id = projeto(segunda, "ARGO PERTUTTI")
        minhaAgenda.iniciar(id, heitor)
        estenderParaTerca(id)

        agenda.alterarStatus(id, StatusAgendamento.CONCLUIDO, autor())

        assertThat(noPainel(terca)).isEmpty()
        assertThat(noPainel(segunda)).isEmpty()
        assertThat(minhaAgenda.doDia(terca, heitor).carros).isEmpty()
        val linha = linhaDoRelatorio(id)
        assertThat(linha.status).isEqualTo(StatusAgendamento.CONCLUIDO)
        assertThat(linha.iniciadoEm).isNotNull()
        assertThat(linha.concluidoEm).isNull() // a tela mostra "?": nao foi o adesivador
    }

    @Test
    fun `concluido pelo adesivador continua no dia, como concluido, com o horario`() {
        val id = projeto(segunda, "FIORINO SELABOND")
        minhaAgenda.iniciar(id, heitor)
        estenderParaTerca(id)
        minhaAgenda.concluir(id, heitor)

        val naTerca = noPainel(terca).single()
        assertThat(naTerca.status).isEqualTo(StatusAgendamento.CONCLUIDO)
        assertThat(naTerca.concluidoEm).isNotNull()
        assertThat(naTerca.atual).isFalse()
        assertThat(linhaDoRelatorio(id).concluidoEm).isNotNull()
    }

    @Test
    fun `em andamento, mas fora da agenda do dia, nao aparece no painel desse dia`() {
        val id = projeto(segunda, "SO NA SEGUNDA")
        minhaAgenda.iniciar(id, heitor)

        assertThat(noPainel(segunda).single().atual).isTrue()
        assertThat(noPainel(terca)).isEmpty()
        assertThat(minhaAgenda.doDia(terca, heitor).carros).isEmpty()
    }
}
