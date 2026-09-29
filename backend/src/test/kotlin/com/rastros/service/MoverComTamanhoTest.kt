package com.rastros.service

import com.rastros.TesteIntegracao
import com.rastros.api.MoverAgendamentoRequest
import com.rastros.api.NovoAgendamentoRequest
import com.rastros.domain.Adesivador
import com.rastros.repository.AdesivadorRepository
import com.rastros.repository.AgendamentoRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Mover levando o tamanho que o servico deve ter **no destino**.
 *
 * A agenda de espacos de trabalho divide o dia em 5 pedacos que nao valem as mesmas
 * horas (o 1o vale 1h30, o 4o vale 1h). Sem mandar o tamanho junto, o servidor conserva
 * a quantidade de faixas e um servico de um espaco chegava do outro lado ocupando dois.
 */
class MoverComTamanhoTest : TesteIntegracao() {

    @Autowired lateinit var agenda: AgendaService
    @Autowired lateinit var adesivadores: AdesivadorRepository
    @Autowired lateinit var agendamentos: AgendamentoRepository

    /** 04/03/2030 e segunda-feira, longe da base de demonstracao. */
    private val segunda = LocalDate.of(2030, 3, 4)

    private lateinit var coluna: Adesivador

    @BeforeEach
    fun preparar() {
        coluna = adesivadores.save(Adesivador(nome = "TAMANHO", ordem = 920))
    }

    private fun criar(faixa: Int, horas: String, descricao: String = "CARRO"): Long =
        agenda.criar(
            NovoAgendamentoRequest(
                data = segunda,
                adesivadorId = coluna.id,
                slotInicio = faixa,
                horasEstimadas = BigDecimal(horas),
                descricao = descricao
            ),
            autor()
        ).id

    private fun lido(id: Long) = agendamentos.findById(id).get()
    private fun faixas(id: Long) = lido(id).posicoesOcupadas.size

    @Test
    fun `com o tamanho do destino, o servico ocupa exatamente as faixas de la`() {
        // 09:00 as 11:00: o 2o espaco do dia, duas faixas de 1h.
        val id = criar(2, "2")

        // 07:30 e o 1o espaco: uma faixa so, de 1h30.
        agenda.mover(id, MoverAgendamentoRequest(segunda, coluna.id, 1, BigDecimal("1.5")), autor())

        assertThat(lido(id).slotInicio).isEqualTo(1)
        assertThat(faixas(id)).isEqualTo(1)
        assertThat(lido(id).horas).isEqualByComparingTo("1.5")
    }

    @Test
    fun `sem o tamanho, continua valendo a quantidade de faixas`() {
        val id = criar(2, "2") // duas faixas

        agenda.mover(id, MoverAgendamentoRequest(segunda, coluna.id, 1), autor())

        assertThat(faixas(id)).isEqualTo(2) // 07:30 as 10:00
        assertThat(lido(id).horas).isEqualByComparingTo("2.5")
    }

    @Test
    fun `na troca, cada um chega com o tamanho que a tela pediu`() {
        val deCima = criar(1, "1.5", "DE CIMA") // 07:30, 1 faixa
        val deBaixo = criar(2, "2", "DE BAIXO") // 09:00, 2 faixas

        // Trocam: o de baixo vai para 07:30 (1h30) e o de cima desce para 09:00 (2h).
        agenda.mover(
            deBaixo,
            MoverAgendamentoRequest(segunda, coluna.id, 1, BigDecimal("1.5"), BigDecimal("2")),
            autor()
        )

        assertThat(lido(deBaixo).slotInicio).isEqualTo(1)
        assertThat(faixas(deBaixo)).isEqualTo(1)
        assertThat(lido(deCima).slotInicio).isEqualTo(2)
        assertThat(faixas(deCima)).isEqualTo(2)
    }

    @Test
    fun `atravessar o almoco nao vira espaco a mais`() {
        // O 3o espaco comeca as 11:00 e vale 2h30: 11:00-12:00 e 13:30-15:00.
        val id = criar(1, "1.5")

        agenda.mover(id, MoverAgendamentoRequest(segunda, coluna.id, 4, BigDecimal("2.5")), autor())

        assertThat(lido(id).slotInicio).isEqualTo(4)
        assertThat(faixas(id)).isEqualTo(2) // as duas de trabalho; o almoco nao conta
        assertThat(lido(id).posicoesOcupadas).containsExactly(3, 5)
    }
}
