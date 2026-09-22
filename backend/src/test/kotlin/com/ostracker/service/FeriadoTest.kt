package com.ostracker.service

import com.ostracker.TesteIntegracao
import com.ostracker.domain.HorarioComercial
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate

class FeriadoTest : TesteIntegracao() {

    @Autowired lateinit var feriados: FeriadoService

    private val original = HorarioComercial.feriados

    @AfterEach
    fun restaurar() {
        HorarioComercial.feriados = original
    }

    @Test
    fun `a migracao ja traz os feriados nacionais`() {
        val datas = feriados.listar().map { it.data }
        assertThat(datas).contains(LocalDate.of(2026, 12, 25), LocalDate.of(2027, 4, 21))
    }

    @Test
    fun `cadastrar recusa data repetida e nome vazio`() {
        val novo = feriados.adicionar(LocalDate.of(2030, 1, 20), "  Sao   Sebastiao ")
        assertThat(novo.descricao).isEqualTo("Sao Sebastiao")

        assertThatThrownBy { feriados.adicionar(LocalDate.of(2030, 1, 20), "Outro") }
            .isInstanceOf(RegraDeNegocioException::class.java)
        assertThatThrownBy { feriados.adicionar(LocalDate.of(2030, 1, 21), "  ") }
            .isInstanceOf(RegraDeNegocioException::class.java)

        feriados.remover(novo.id)
        assertThat(feriados.listar().map { it.data }).doesNotContain(LocalDate.of(2030, 1, 20))
    }

    @Test
    fun `o relogio carrega os feriados do banco`() {
        feriados.recarregar()
        assertThat(HorarioComercial.feriados).contains(LocalDate.of(2026, 11, 20))
    }
}
