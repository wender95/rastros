package com.rastros.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** O relogio das OS so anda com a empresa aberta: seg a sex, 07:30-12:00 e 13:30-18:00. */
class HorarioComercialTest {

    private val zona = HorarioComercial.zona
    private fun em(texto: String) = LocalDateTime.parse(texto).atZone(zona).toInstant()
    private fun entre(de: String, ate: String, feriados: Set<LocalDate> = emptySet()) =
        HorarioComercial.entre(em(de), em(ate), feriados)

    @Test
    fun `de sexta no fim da tarde a segunda cedo conta so o expediente - a sexta fecha as 17h`() {
        // 21/09/2026 e segunda. Sexta 18/09 17:00 -> segunda 21/09 08:00.
        assertThat(entre("2026-09-18T16:00", "2026-09-21T08:00")).isEqualTo(Duration.ofMinutes(90)) // 1h na sexta + 30min na segunda
        assertThat(entre("2026-09-18T17:00", "2026-09-21T08:00")).isEqualTo(Duration.ofMinutes(30)) // sexta ja fechou
    }

    @Test
    fun `o almoco nao conta`() {
        assertThat(entre("2026-09-21T11:00", "2026-09-21T14:00")).isEqualTo(Duration.ofMinutes(90))
        assertThat(entre("2026-09-21T12:15", "2026-09-21T13:00")).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `antes de abrir e depois de fechar nao contam`() {
        assertThat(entre("2026-09-21T06:00", "2026-09-21T20:00")).isEqualTo(Duration.ofHours(9))
        assertThat(entre("2026-09-21T19:00", "2026-09-22T07:00")).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `uma semana inteira sao 44 horas`() {
        assertThat(entre("2026-09-21T00:00", "2026-09-28T00:00")).isEqualTo(Duration.ofHours(44))
    }

    @Test
    fun `feriado cadastrado nao conta`() {
        val feriado = setOf(LocalDate.of(2026, 9, 22))
        assertThat(entre("2026-09-21T17:00", "2026-09-23T08:00", feriado)).isEqualTo(Duration.ofMinutes(90))
        assertThat(entre("2026-09-21T17:00", "2026-09-23T08:00")).isEqualTo(Duration.ofMinutes(90 + 9 * 60))
    }

    @Test
    fun `fim antes do inicio da zero`() {
        assertThat(entre("2026-09-21T10:00", "2026-09-21T09:00")).isEqualTo(Duration.ZERO)
    }
}
