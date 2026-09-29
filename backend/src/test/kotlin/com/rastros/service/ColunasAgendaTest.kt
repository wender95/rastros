package com.rastros.service

import com.rastros.TesteIntegracao
import com.rastros.domain.Adesivador
import com.rastros.domain.Agendamento
import com.rastros.domain.TipoAgendamento
import com.rastros.domain.TipoColunaAgenda
import com.rastros.repository.AdesivadorRepository
import com.rastros.repository.AgendamentoRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Modo edicao dos adesivadores da agenda: adicionar, renomear, reordenar, remover, restaurar. */
class ColunasAgendaTest : TesteIntegracao() {

    @Autowired lateinit var colunas: ColunasAgendaService
    @Autowired lateinit var agenda: AgendaService
    @Autowired lateinit var adesivadores: AdesivadorRepository
    @Autowired lateinit var agendamentos: AgendamentoRepository

    private fun ativas() = adesivadores.findAllByOrderByOrdemAsc().filter { it.ativo }

    private fun carro(coluna: Adesivador, dia: LocalDate) = agendamentos.save(
        Agendamento(data = dia, adesivador = coluna, slotInicio = 1, tipo = TipoAgendamento.SERVICO, descricao = "CARRO")
    )

    @Test
    fun `adicionar entra depois do ultimo adesivador, antes de Encaixe e Noturno, em maiusculas`() {
        adesivadores.save(Adesivador(nome = "PRIMEIRO", ordem = 1))
        adesivadores.save(Adesivador(nome = "ENCAIXE TESTE", tipo = TipoColunaAgenda.ENCAIXE, ordem = 90))

        val novo = colunas.adicionar("  joao   pedro ", autor())

        assertThat(novo.nome).isEqualTo("JOAO PEDRO")
        assertThat(ativas().map { it.nome }).containsSubsequence("PRIMEIRO", "JOAO PEDRO", "ENCAIXE TESTE")
        assertThat(ativas().last().tipo).isNotEqualTo(TipoColunaAgenda.ADESIVADOR)
    }

    @Test
    fun `nao aceita nome repetido nem vazio`() {
        colunas.adicionar("Repetido", autor())

        assertThatThrownBy { colunas.adicionar("REPETIDO", autor()) }.isInstanceOf(RegraDeNegocioException::class.java)
        assertThatThrownBy { colunas.adicionar("   ", autor()) }.isInstanceOf(RegraDeNegocioException::class.java)
    }

    @Test
    fun `renomear troca o nome e recusa o de outra coluna`() {
        val a = colunas.adicionar("Alfa", autor())
        colunas.adicionar("Beta", autor())

        assertThat(colunas.renomear(a.id, "gama", autor()).nome).isEqualTo("GAMA")
        assertThatThrownBy { colunas.renomear(a.id, "beta", autor()) }.isInstanceOf(RegraDeNegocioException::class.java)
    }

    @Test
    fun `reordenar grava a nova ordem da esquerda para a direita`() {
        listOf("Um", "Dois", "Tres").forEach { colunas.adicionar(it, autor()) }
        val ids = ativas().mapNotNull { it.id }
        val invertida = ids.reversed()

        colunas.reordenar(invertida, autor())

        assertThat(ativas().mapNotNull { it.id }).isEqualTo(invertida)
        assertThatThrownBy { colunas.reordenar(invertida.drop(1), autor()) }
            .isInstanceOf(RegraDeNegocioException::class.java)
    }

    @Test
    fun `quem tem carro de hoje em diante nao sai da agenda`() {
        val coluna = adesivadores.findById(colunas.adicionar("Ocupado", autor()).id).get()
        carro(coluna, LocalDate.now().plusDays(3))

        assertThatThrownBy { colunas.remover(coluna.id!!, autor()) }
            .isInstanceOf(RegraDeNegocioException::class.java)
            .hasMessageContaining("1 carro")
        assertThat(adesivadores.findById(coluna.id!!).get().ativo).isTrue()
    }

    @Test
    fun `quem so tem historico sai da agenda mas continua nas semanas em que trabalhou`() {
        val coluna = adesivadores.findById(colunas.adicionar("Antigo", autor()).id).get()
        val semanaPassada = LocalDate.now().minusWeeks(2).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        carro(coluna, semanaPassada)

        assertThat(colunas.remover(coluna.id!!, autor()).resultado).isEqualTo(Remocao.RETIRADO_DA_AGENDA)

        assertThat(adesivadores.findById(coluna.id!!).get().ativo).isFalse()
        assertThat(agenda.semana(LocalDate.now().plusWeeks(1)).colunas.map { it.id }).doesNotContain(coluna.id)
        assertThat(agenda.semana(semanaPassada).colunas.map { it.id }).contains(coluna.id)

        // Restaurar traz de volta, depois do ultimo adesivador.
        colunas.restaurar(coluna.id!!, autor())
        assertThat(ativas().last { it.tipo == TipoColunaAgenda.ADESIVADOR }.id).isEqualTo(coluna.id)
    }

    @Test
    fun `quem nunca teve carro e apagado de vez`() {
        val id = colunas.adicionar("Novato", autor()).id

        assertThat(colunas.remover(id, autor()).resultado).isEqualTo(Remocao.APAGADO)
        assertThat(adesivadores.findById(id)).isEmpty()
    }

    @Test
    fun `adicionar um nome removido traz de volta a coluna antiga, com o historico`() {
        val coluna = adesivadores.findById(colunas.adicionar("Voltou", autor()).id).get()
        carro(coluna, LocalDate.now().minusMonths(1))
        colunas.remover(coluna.id!!, autor())

        assertThat(colunas.adicionar("voltou", autor()).id).isEqualTo(coluna.id)
        assertThat(adesivadores.findById(coluna.id!!).get().ativo).isTrue()
    }

    @Test
    fun `so diretoria e administrador editam`() {
        assertThatThrownBy { colunas.adicionar("Intruso", autor("vendedor@rastros.cloud")) }
            .isInstanceOf(PermissaoNegadaException::class.java)
        colunas.adicionar("Do admin", autor("admin@rastros.cloud"))
    }
}
