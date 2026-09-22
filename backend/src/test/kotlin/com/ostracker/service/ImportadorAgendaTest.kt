package com.ostracker.service

import com.ostracker.TesteIntegracao
import com.ostracker.repository.AgendamentoRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate

class ImportadorAgendaTest : TesteIntegracao() {

    @Autowired lateinit var importador: ImportadorAgendaService
    @Autowired lateinit var agendamentos: AgendamentoRepository

    private fun ler(arquivo: String) = javaClass.getResource("/planilha/$arquivo")!!.readText()

    @Test
    fun `o dia importado cabe nas 9 horas, sem servico em cima de outro`() {
        importador.importar("Agosto", 2026, ler("agosto-2026.csv"))
        importador.importar("Setembro", 2026, ler("setembro-2026.csv"))

        val todos = agendamentos.findAll()
        assertThat(todos).hasSizeGreaterThan(300)

        todos.groupBy { it.adesivador.id to it.data }.forEach { (chave, doDia) ->
            val posicoes = doDia.flatMap { it.posicoesOcupadas }
            assertThat(posicoes)
                .describedAs("coluna ${doDia.first().adesivador.nome} em ${chave.second}")
                .doesNotHaveDuplicates()
                .allMatch { it < 9 } // nada vaza para o dia seguinte
        }
    }

    @Test
    fun `a semana de um dia so, no fim da aba, tambem e importada`() {
        importador.importar("Agosto", 2026, ler("agosto-2026.csv"))

        assertThat(agendamentos.countByDataBetween(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 31)))
            .isGreaterThan(0)
    }

    @Test
    fun `com a partir de, so entra a agenda daquele dia em diante`() {
        val corte = LocalDate.of(2026, 9, 16) // quarta: a semana de 14/09 entra so pela metade
        importador.importar("Setembro", 2026, ler("setembro-2026.csv"), aPartirDe = corte)

        val datas = agendamentos.findAll().map { it.data }
        assertThat(datas).isNotEmpty().allMatch { !it.isBefore(corte) }
        assertThat(datas).contains(corte)
    }

    @Test
    fun `um dia antes do corte com agenda nao impede importar dali em diante`() {
        importador.importar("Setembro", 2026, ler("setembro-2026.csv"), aPartirDe = LocalDate.of(2026, 9, 14))
        val antes = agendamentos.count()
        agendamentos.deleteAll(agendamentos.findAll().filter { !it.data.isBefore(LocalDate.of(2026, 9, 21)) })

        importador.importar("Setembro", 2026, ler("setembro-2026.csv"), aPartirDe = LocalDate.of(2026, 9, 21))

        assertThat(agendamentos.count()).isEqualTo(antes)
    }

    @Test
    fun `importar de novo um periodo que ja esta no sistema e recusado`() {
        importador.importar("Setembro", 2026, ler("setembro-2026.csv"))
        val antes = agendamentos.count()

        assertThatThrownBy { importador.importar("Setembro", 2026, ler("setembro-2026.csv")) }
            .isInstanceOf(RegraDeNegocioException::class.java)
            .hasMessageContaining("ja tem")
        assertThat(agendamentos.count()).isEqualTo(antes)
    }

    @Test
    fun `o reparo grava so os dias que nunca chegaram e so aponta score divergente`() {
        importador.importar("Setembro", 2026, ler("setembro-2026.csv"))
        val quarta = LocalDate.of(2026, 9, 2)
        val quinta = LocalDate.of(2026, 9, 3)
        // Simula a base antiga: um dia que nunca foi importado e um score perdido.
        agendamentos.deleteAll(agendamentos.findAll().filter { it.data == quarta })
        val alterado = agendamentos.findAll().first { it.data == quinta && it.score != null }
        alterado.score = alterado.score!! + java.math.BigDecimal.ONE
        val quintaAntes = agendamentos.countByDataBetween(quinta, quinta)

        val resultado = importador.completarDiasAusentes("Setembro", 2026, ler("setembro-2026.csv"))

        assertThat(resultado.diasCompletados).containsExactly(quarta)
        assertThat(agendamentos.countByDataBetween(quarta, quarta)).isGreaterThan(0)
        assertThat(agendamentos.countByDataBetween(quinta, quinta)).isEqualTo(quintaAntes)
        assertThat(resultado.avisos).anyMatch { it.contains(alterado.descricao) && it.contains("no sistema") }
        assertThat(agendamentos.findById(alterado.id!!).get().score).isEqualTo(alterado.score) // nao mexe
    }
}
