package com.rastros.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.rastros.TesteIntegracao
import com.rastros.api.NovoAgendamentoRequest
import com.rastros.domain.Adesivador
import com.rastros.repository.AdesivadorRepository
import com.rastros.repository.AgendamentoRepository
import com.rastros.repository.RetratoAgendaRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class HistoricoAgendaTest : TesteIntegracao() {

    @Autowired lateinit var agenda: AgendaService
    @Autowired lateinit var adesivadores: AdesivadorRepository
    @Autowired lateinit var agendamentos: AgendamentoRepository
    @Autowired lateinit var retratos: RetratoAgendaRepository
    @Autowired lateinit var json: ObjectMapper

    private val segunda = LocalDate.of(2030, 3, 4)
    private lateinit var coluna: Adesivador

    @BeforeEach
    fun preparar() {
        coluna = adesivadores.save(Adesivador(nome = "TESTE", ordem = 900))
    }

    private fun criar(faixa: Int, horas: String, descricao: String) = agenda.criar(
        NovoAgendamentoRequest(
            data = segunda, adesivadorId = coluna.id, slotInicio = faixa,
            horasEstimadas = BigDecimal(horas), descricao = descricao
        ),
        autor()
    ).id

    @Test
    fun `o desfazer sobrevive a um reinicio da aplicacao`() {
        val a = criar(1, "2.5", "A")
        val b = criar(3, "1", "B")
        agenda.alterarHoras(a, BigDecimal("4.5"), autor())

        // Uma instancia nova, sem nada em memoria, enxerga a mesma pilha.
        val depoisDoReinicio = HistoricoAgendaService(retratos, json)
        assertThat(depoisDoReinicio.ultima(autor().id)).isEqualTo("mudar as horas de \"A\"")

        agenda.desfazer(autor())
        assertThat(agendamentos.findById(b).get().slotInicio).isEqualTo(3)
    }

    @Test
    fun `retrato com mais de um dia nao e desfeito`() {
        criar(1, "1.5", "ANTIGO")
        retratos.findAll().forEach { it.criadoEm = Instant.now().minus(Duration.ofHours(25)) }

        assertThatThrownBy { agenda.desfazer(autor()) }.isInstanceOf(RegraDeNegocioException::class.java)
    }

    @Test
    fun `cada pessoa guarda so os ultimos 25 passos`() {
        repeat(30) { criar(1, "1", "C$it") }

        assertThat(retratos.idsDoUsuario(autor().id)).hasSize(HistoricoAgendaService.LIMITE)
    }
}
