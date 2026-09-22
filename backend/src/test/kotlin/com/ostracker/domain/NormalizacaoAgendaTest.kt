package com.ostracker.domain

import com.ostracker.domain.NormalizacaoAgenda.Item
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class NormalizacaoAgendaTest {

    private val segunda = LocalDate.of(2030, 3, 4)
    private val terca = segunda.plusDays(1)
    private val sexta = segunda.plusDays(4)

    private fun item(id: Long, dia: LocalDate, faixa: Int, horas: String, importado: Boolean = true) =
        Item(id, adesivadorId = 1, data = dia, slotInicio = faixa, horas = BigDecimal(horas), importado = importado)

    @Test
    fun `o ultimo do dia que vaza sobre o primeiro do dia seguinte e encurtado ate o fim do dia`() {
        val ajustes = NormalizacaoAgenda.horasSemSobreposicao(
            listOf(item(1, segunda, 7, "3.5"), item(2, terca, 1, "2"))
        )

        assertThat(ajustes).containsOnlyKeys(1L)
        assertThat(ajustes[1]).isEqualByComparingTo("3") // 15:00-18:00
    }

    @Test
    fun `sobreposicao dentro do mesmo dia encurta ate onde o seguinte comeca`() {
        val ajustes = NormalizacaoAgenda.horasSemSobreposicao(
            listOf(item(1, segunda, 1, "9"), item(2, segunda, 9, "1"))
        )

        assertThat(ajustes[1]).isEqualByComparingTo("8") // 07:30-17:00
    }

    @Test
    fun `sexta que vaza sobre a segunda seguinte tambem e encurtada`() {
        val ajustes = NormalizacaoAgenda.horasSemSobreposicao(
            listOf(item(1, sexta, 9, "2"), item(2, sexta.plusDays(3), 1, "1.5"))
        )

        assertThat(ajustes[1]).isEqualByComparingTo("1")
    }

    @Test
    fun `horas digitadas por alguem no sistema nao sao tocadas`() {
        val ajustes = NormalizacaoAgenda.horasSemSobreposicao(
            listOf(item(1, segunda, 7, "3.5", importado = false), item(2, terca, 1, "2"))
        )

        assertThat(ajustes).isEmpty()
    }

    @Test
    fun `quem ja cabe fica como esta, e servico longo sem ninguem depois tambem`() {
        val ajustes = NormalizacaoAgenda.horasSemSobreposicao(
            listOf(item(1, segunda, 1, "2.5"), item(2, segunda, 3, "1"), item(3, segunda, 6, "27"))
        )

        assertThat(ajustes).isEmpty()
    }

    @Test
    fun `dois que comecam na mesma faixa ficam juntos, mas nenhum invade o dia seguinte`() {
        val ajustes = NormalizacaoAgenda.horasSemSobreposicao(
            listOf(item(1, segunda, 9, "2"), item(2, segunda, 9, "1"), item(3, terca, 1, "1.5"))
        )

        assertThat(ajustes).containsOnlyKeys(1L)
        assertThat(ajustes[1]).isEqualByComparingTo("1")
    }

    @Test
    fun `a regua de dias uteis pula o fim de semana`() {
        assertThat(NormalizacaoAgenda.indiceDiaUtil(sexta.plusDays(3)) - NormalizacaoAgenda.indiceDiaUtil(sexta))
            .isEqualTo(1)
    }
}
