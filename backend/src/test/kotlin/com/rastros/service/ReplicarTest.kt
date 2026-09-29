package com.rastros.service

import com.rastros.TesteIntegracao
import com.rastros.api.NovaParteRequest
import com.rastros.api.NovoAgendamentoRequest
import com.rastros.domain.Adesivador
import com.rastros.domain.TipoAgendamento
import com.rastros.repository.AdesivadorRepository
import com.rastros.repository.AgendamentoRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

/**
 * A alca do canto da agenda replica o card em vez de esticar: cada espaco de destino ganha
 * uma copia que e parte do mesmo servico.
 */
class ReplicarTest : TesteIntegracao() {

    @Autowired lateinit var agenda: AgendaService
    @Autowired lateinit var adesivadores: AdesivadorRepository
    @Autowired lateinit var agendamentos: AgendamentoRepository
    @Autowired lateinit var produtividade: ProdutividadeService
    @Autowired lateinit var minhaAgenda: MinhaAgendaService

    /** 04/03/2030 e segunda. */
    private val segunda = LocalDate.of(2030, 3, 4)
    private lateinit var coluna: Adesivador

    @BeforeEach
    fun preparar() {
        coluna = adesivadores.save(Adesivador(nome = "REPLICA", ordem = 950))
    }

    private fun criar(faixa: Int, descricao: String) = agenda.criar(
        NovoAgendamentoRequest(
            data = segunda, adesivadorId = coluna.id, slotInicio = faixa,
            horasEstimadas = BigDecimal("1"), descricao = descricao, vendedorCodigo = "M"
        ),
        autor()
    ).id

    private fun destino(faixa: Int, dia: LocalDate = segunda) =
        NovaParteRequest(data = dia, adesivadorId = coluna.id, slotInicio = faixa, horasEstimadas = BigDecimal("1"))

    private fun daColuna() = agendamentos.findAll().filter { it.adesivador.id == coluna.id }

    @Test
    fun `cada espaco ganha uma copia ligada ao original, e um desfazer tira todas`() {
        val original = criar(2, "TAXI 123")

        val feitas = agenda.replicar(original, listOf(destino(3), destino(4)), autor())

        assertThat(feitas).isEqualTo(2)
        val todos = daColuna()
        assertThat(todos).hasSize(3)
        assertThat(todos.map { it.descricao }.toSet()).containsExactly("TAXI 123")
        assertThat(todos.map { it.vendedorCodigo }.toSet()).containsExactly("M")
        assertThat(todos.map { it.grupoId }.toSet()).containsExactly(original)
        assertThat(todos.map { it.slotInicio }).containsExactlyInAnyOrder(2, 3, 4)

        agenda.desfazer(autor())

        assertThat(daColuna().map { it.id }).containsExactly(original)
    }

    @Test
    fun `se um dos espacos ja esta ocupado, nenhuma copia e feita`() {
        val original = criar(2, "TAXI 123")
        criar(4, "OUTRO CARRO")

        assertThatThrownBy { agenda.replicar(original, listOf(destino(3), destino(4)), autor()) }
            .isInstanceOf(RegraDeNegocioException::class.java)
            .hasMessageContaining("OUTRO CARRO")

        assertThat(daColuna().map { it.descricao }).containsExactlyInAnyOrder("TAXI 123", "OUTRO CARRO")
    }

    @Test
    fun `o servico com copias conta como um so no relatorio, no painel e com um score so`() {
        val original = criar(2, "TAXI 123")
        agenda.replicar(original, listOf(destino(3), destino(4), destino(1, segunda.plusDays(1))), autor())
        val copia = daColuna().first { it.id != original }.id!!

        // O score lancado numa copia vale para o servico inteiro, contado uma vez.
        agenda.alterarScore(copia, BigDecimal("2.5"), autor())
        assertThat(daColuna().map { it.score }).hasSize(4).allSatisfy { assertThat(it).isEqualByComparingTo("2.5") }

        val doRelatorio = produtividade.relatorio(segunda, segunda.plusDays(4)).adesivadores.first { it.adesivadorId == coluna.id }
        assertThat(doRelatorio.totalServicos).isEqualTo(1)
        assertThat(doRelatorio.servicos).hasSize(1)
        assertThat(doRelatorio.servicos.single().agendamentoId).isEqualTo(original)
        assertThat(doRelatorio.servicos.single().dataFim).isEqualTo(segunda.plusDays(1))
        assertThat(doRelatorio.scoreLancado).isEqualByComparingTo("2.5")

        // No painel (e na Minha agenda): um card so no dia, cobrindo os horarios das copias.
        val doPainel = minhaAgenda.agendasDoDia(segunda).first { it.adesivadorId == coluna.id }
        assertThat(doPainel.carros).hasSize(1)
        assertThat(doPainel.carros.single().terminaEm).isEqualTo(segunda.plusDays(1))
        assertThat(minhaAgenda.agendasDoDia(segunda.plusDays(1)).first { it.adesivadorId == coluna.id }.carros).hasSize(1)

        assertThat(produtividade.buscarServicos("TAXI 123").servicos.filter { it.adesivador == "REPLICA" }).hasSize(1)
    }

    @Test
    fun `um INDISPONIVEL se repete em cada linha como blocos soltos`() {
        val bloqueio = agenda.criar(
            NovoAgendamentoRequest(
                data = segunda, adesivadorId = coluna.id, slotInicio = 2, horasEstimadas = BigDecimal("1"),
                descricao = "INDISPONÍVEL", tipo = TipoAgendamento.INDISPONIVEL
            ),
            autor()
        ).id

        assertThat(agenda.replicar(bloqueio, listOf(destino(3), destino(4)), autor())).isEqualTo(2)

        val todos = daColuna()
        assertThat(todos).hasSize(3).allSatisfy {
            assertThat(it.tipo).isEqualTo(TipoAgendamento.INDISPONIVEL)
            assertThat(it.grupoId).isNull()
        }
        assertThat(todos.map { it.slotInicio }).containsExactlyInAnyOrder(2, 3, 4)
    }

    @Test
    fun `marcar varios espacos como indisponivel cria um bloco por linha, e um desfazer tira todos`() {
        criar(4, "OCUPADO")
        assertThatThrownBy { agenda.marcarIndisponivel(listOf(destino(3), destino(4)), autor()) }
            .isInstanceOf(RegraDeNegocioException::class.java)
            .hasMessageContaining("OCUPADO")

        assertThat(agenda.marcarIndisponivel(listOf(destino(1), destino(2), destino(3)), autor())).isEqualTo(3)
        val bloqueios = daColuna().filter { it.tipo == TipoAgendamento.INDISPONIVEL }
        assertThat(bloqueios.map { it.slotInicio }).containsExactlyInAnyOrder(1, 2, 3)

        agenda.desfazer(autor())
        assertThat(daColuna().map { it.descricao }).containsExactly("OCUPADO")
    }
}
