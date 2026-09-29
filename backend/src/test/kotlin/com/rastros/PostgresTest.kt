package com.rastros

import com.rastros.api.NovoAgendamentoRequest
import com.rastros.domain.Adesivador
import com.rastros.repository.AdesivadorRepository
import com.rastros.repository.AgendamentoRepository
import com.rastros.service.AgendaService
import com.rastros.service.ImportadorAgendaService
import com.rastros.service.ProdutividadeService
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.math.BigDecimal
import java.time.LocalDate

/**
 * A aplicacao inteira sobre um PostgreSQL de verdade (14), criado do zero pelas mesmas
 * migracoes do Flyway. E a prova de que trocar o H2 pelo PostgreSQL em producao e so
 * mudar a URL do banco.
 */
class PostgresTest : TesteIntegracao() {

    companion object {
        private val postgres: EmbeddedPostgres = EmbeddedPostgres.start()

        @JvmStatic
        @DynamicPropertySource
        fun banco(registro: DynamicPropertyRegistry) {
            registro.add("spring.datasource.url") { postgres.getJdbcUrl("postgres", "postgres") }
            registro.add("spring.datasource.username") { "postgres" }
            registro.add("spring.datasource.password") { "" }
            registro.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
        }
    }

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var importador: ImportadorAgendaService
    @Autowired lateinit var produtividade: ProdutividadeService
    @Autowired lateinit var agenda: AgendaService
    @Autowired lateinit var adesivadores: AdesivadorRepository
    @Autowired lateinit var agendamentos: AgendamentoRepository

    @Test
    fun `o banco e mesmo PostgreSQL e todas as migracoes rodaram`() {
        assertThat(jdbc.queryForObject("select version()", String::class.java)).startsWith("PostgreSQL")
        val versoes = jdbc.queryForList(
            "select version from flyway_schema_history where success order by installed_rank", String::class.java
        )
        assertThat(versoes).containsSequence("1", "2", "3", "4")
    }

    @Test
    fun `importar a planilha e somar o score da semana funciona no PostgreSQL`() {
        val csv = javaClass.getResource("/planilha/setembro-2026.csv")!!.readText()
        importador.importar("Setembro", 2026, csv)

        val semana = produtividade.relatorioSemanal(LocalDate.of(2026, 9, 1)).adesivadores
            .associate { it.adesivador to it.score }

        assertThat(semana["ANDRE"]).isEqualByComparingTo("40.5")
        assertThat(semana["CAIO"]).isEqualByComparingTo("35.5")
        assertThat(semana["HUGO"]).isEqualByComparingTo("36")
    }

    @Test
    fun `empurrar e desfazer funcionam no PostgreSQL`() {
        val coluna = adesivadores.save(Adesivador(nome = "PG", ordem = 900))
        val segunda = LocalDate.of(2030, 3, 4)
        fun criar(faixa: Int, horas: String) = agenda.criar(
            NovoAgendamentoRequest(
                data = segunda, adesivadorId = coluna.id, slotInicio = faixa,
                horasEstimadas = BigDecimal(horas), descricao = "CARRO"
            ),
            autor()
        ).id
        val a = criar(1, "2.5")
        val b = criar(3, "1")

        agenda.alterarHoras(a, BigDecimal("4.5"), autor())
        assertThat(agendamentos.findById(b).get().slotInicio).isEqualTo(6)

        agenda.desfazer(autor())
        assertThat(agendamentos.findById(b).get().slotInicio).isEqualTo(3)
        assertThat(agenda.buscar("carro", false)).isNotEmpty()
    }
}
