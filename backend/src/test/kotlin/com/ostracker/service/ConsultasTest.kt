package com.ostracker.service

import com.ostracker.TesteIntegracao
import com.ostracker.repository.AgendamentoRepository
import com.ostracker.repository.OrdemServicoRepository
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.SessionFactory
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate

/**
 * Quantas consultas SQL cada tela dispara.
 *
 * A semana da agenda e o relatorio mostram, para cada carro ligado a uma OS, o andamento
 * do material. Buscar os fluxos carro a carro (N+1) faz o numero de consultas crescer
 * com a agenda; o limite aqui garante que ele fique constante.
 */
class ConsultasTest : TesteIntegracao() {

    @Autowired lateinit var importador: ImportadorAgendaService
    @Autowired lateinit var agenda: AgendaService
    @Autowired lateinit var produtividade: ProdutividadeService
    @Autowired lateinit var agendamentos: AgendamentoRepository
    @Autowired lateinit var ordens: OrdemServicoRepository
    @Autowired lateinit var entityManager: EntityManager

    private val semana = LocalDate.of(2026, 9, 1)

    @BeforeEach
    fun preparar() {
        importador.importar("Setembro", 2026, javaClass.getResource("/planilha/setembro-2026.csv")!!.readText())
        // Cada carro da semana ligado a uma OS diferente: o pior caso para o N+1.
        val todasAsOs = ordens.findAll()
        agendamentos.findAll()
            .filter { !it.data.isBefore(semana) && it.data.isBefore(semana.plusDays(4)) }
            .forEachIndexed { i, a -> a.ordemServico = todasAsOs[i % todasAsOs.size] }
        entityManager.flush()
        entityManager.clear() // como numa requisicao nova, sem nada em memoria
    }

    private fun consultas(acao: () -> Unit): Long {
        val estatisticas = entityManager.entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        estatisticas.clear()
        acao()
        return estatisticas.prepareStatementCount
    }

    @Test
    fun `a semana da agenda nao faz uma consulta por carro`() {
        val carros = agenda.semana(semana).dias.sumOf { it.agendamentos.count { a -> a.material != null } }
        entityManager.clear()

        val total = consultas { agenda.semana(semana) }

        println("semana da agenda: $total consultas para $carros carros com OS")
        assertThat(carros).isGreaterThan(40)
        assertThat(total).isLessThanOrEqualTo(LIMITE)
    }

    @Test
    fun `o relatorio semanal nao faz uma consulta por carro`() {
        val total = consultas { produtividade.relatorioSemanal(semana) }

        println("relatorio semanal: $total consultas")
        assertThat(total).isLessThanOrEqualTo(LIMITE)
    }

    private companion object {
        const val LIMITE = 12L
    }
}
