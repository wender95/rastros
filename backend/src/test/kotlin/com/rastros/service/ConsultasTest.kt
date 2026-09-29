package com.rastros.service

import com.rastros.TesteIntegracao
import com.rastros.repository.AgendamentoRepository
import com.rastros.repository.OrdemServicoRepository
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
    @Autowired lateinit var fluxos: FluxoService
    @Autowired lateinit var setores: com.rastros.repository.SetorRepository

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

    /** Muitas OS abertas: e o que cresce com o tempo de uso. */
    private var numerosUsados = 0

    private fun abrirMuitasOs(quantas: Int) {
        val criacao = setores.findByNome(com.rastros.domain.SetorNome.CRIACAO)!!.id!!
        repeat(quantas) { i ->
            fluxos.criarOrdem(
                com.rastros.api.NovaOrdemRequest(
                    numeroOsErp = "HIST${numerosUsados + i}", cliente = "Cliente $i", servico = "Servico $i",
                    fluxos = listOf(com.rastros.api.NovoFluxoRequest(setorInicialId = criacao))
                ),
                autor("vendedor@rastros.cloud")
            )
        }
        numerosUsados += quantas
        entityManager.flush()
        entityManager.clear()
    }

    @Test
    fun `a lista de OS para vincular na agenda nao faz uma consulta por OS`() {
        abrirMuitasOs(40)
        val abertas = fluxos.ordensAbertas()
        entityManager.clear()

        val total = consultas { fluxos.ordensAbertas() }

        println("OS abertas: $total consultas para ${abertas.size} OS")
        assertThat(abertas.size).isGreaterThan(40)
        assertThat(total).isLessThanOrEqualTo(LIMITE)
    }

    @Test
    fun `a consulta sem busca traz so as 300 mais recentes, e a busca acha as antigas`() {
        abrirMuitasOs(305)

        val lista = fluxos.listarFluxos(autor(), null, null)
        assertThat(lista).hasSize(300)
        assertThat(lista.map { it.id }).isSortedAccordingTo(compareByDescending { it })

        val aguardando = fluxos.listarFluxos(autor(), null, com.rastros.domain.StatusFluxo.AGUARDANDO_RECEBIMENTO)
        assertThat(aguardando).isNotEmpty.allSatisfy {
            assertThat(it.status).isEqualTo(com.rastros.domain.StatusFluxo.AGUARDANDO_RECEBIMENTO)
        }

        // A primeira aberta ficou fora das 300, mas a busca acha.
        assertThat(lista.map { it.numeroOsErp }).doesNotContain("HIST0")
        assertThat(fluxos.listarFluxos(autor(), "HIST0", null).map { it.numeroOsErp }).contains("HIST0")
    }

    @Test
    fun `o painel nao le o historico inteiro`() {
        val hoje = LocalDate.now()
        abrirMuitasOs(5)
        val comPoucas = consultas { fluxos.painel(autor(), hoje, hoje) }
        entityManager.clear()

        abrirMuitasOs(40)
        val painel = fluxos.painel(autor(), hoje, hoje)
        entityManager.clear()
        val comMuitas = consultas { fluxos.painel(autor(), hoje, hoje) }

        // O numero de consultas nao cresce com a quantidade de OS.
        println("painel: $comPoucas consultas com poucas OS, $comMuitas com mais 40")
        assertThat(painel.osAbertas).isGreaterThanOrEqualTo(45)
        assertThat(comMuitas).isEqualTo(comPoucas)
    }

    private companion object {
        const val LIMITE = 12L
    }
}
