package com.rastros.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class FaixasDoDiaTest {

    private val almoco = 4 // posicao da faixa 12:00-13:30 na regua do dia

    @Test
    fun `o dia tem 9 horas uteis, sem contar o almoco`() {
        assertThat(FaixasDoDia.HORAS_UTEIS_DO_DIA).isEqualByComparingTo("9")
    }

    @Test
    fun `servico que atravessa o meio-dia pula o almoco e nao conta a hora dele`() {
        // 09:00 com 4,5h: 09-10, 10-11, 11-12, (almoco), 13:30-15:00
        val posicoes = FaixasDoDia.posicoesOcupadas(2, BigDecimal("4.5"))

        assertThat(posicoes).containsExactly(1, 2, 3, 5)
        assertThat(posicoes).doesNotContain(almoco)
        assertThat(FaixasDoDia.horasDasPosicoes(posicoes)).isEqualByComparingTo("4.5")
    }

    @Test
    fun `servico maior que o dia continua na regua do dia seguinte`() {
        // 18h a partir das 07:30 = dois dias inteiros
        val posicoes = FaixasDoDia.posicoesOcupadas(1, BigDecimal("18"))

        assertThat(posicoes).hasSize(16)
        assertThat(posicoes.last()).isEqualTo(17) // ultima faixa (17:00) do segundo dia
        assertThat(posicoes).doesNotContain(almoco, almoco + FaixasDoDia.QUANTIDADE)
    }

    @Test
    fun `menos de meia hora vira meia hora e ocupa uma faixa`() {
        assertThat(FaixasDoDia.posicoesOcupadas(3, BigDecimal.ZERO)).containsExactly(2)
    }
}
