package com.rastros.service

import com.rastros.TesteIntegracao
import com.rastros.api.DestinoNoLote
import com.rastros.api.NovoAgendamentoRequest
import com.rastros.domain.Adesivador
import com.rastros.domain.StatusAgendamento
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
 * Varios cards de uma vez (selecao com Shift na agenda): mudar o estado e excluir, com um
 * desfazer so para o lote inteiro.
 */
class LoteAgendaTest : TesteIntegracao() {

    @Autowired lateinit var agenda: AgendaService
    @Autowired lateinit var adesivadores: AdesivadorRepository
    @Autowired lateinit var agendamentos: AgendamentoRepository

    /** 04/03/2030 e segunda. */
    private val segunda = LocalDate.of(2030, 3, 4)
    private lateinit var um: Adesivador
    private lateinit var outro: Adesivador

    @BeforeEach
    fun preparar() {
        um = adesivadores.save(Adesivador(nome = "LOTE UM", ordem = 940))
        outro = adesivadores.save(Adesivador(nome = "LOTE OUTRO", ordem = 941))
    }

    private fun criar(coluna: Adesivador, dia: LocalDate, faixa: Int, tipo: TipoAgendamento = TipoAgendamento.SERVICO) =
        agenda.criar(
            NovoAgendamentoRequest(
                data = dia, adesivadorId = coluna.id, tipo = tipo, slotInicio = faixa,
                horasEstimadas = BigDecimal("1"), descricao = "CARRO $faixa"
            ),
            autor()
        ).id

    private fun status(id: Long) = agendamentos.findById(id).get().status

    @Test
    fun `muda o estado de todos, pula bloqueio, e um desfazer volta todos`() {
        val a = criar(um, segunda, 2)
        val b = criar(outro, segunda.plusDays(3), 3)
        val bloqueio = criar(um, segunda, 4, TipoAgendamento.INDISPONIVEL)

        val mudados = agenda.alterarStatusDeVarios(listOf(a, b, bloqueio), StatusAgendamento.CONCLUIDO, autor())

        assertThat(mudados).isEqualTo(2)
        assertThat(status(a)).isEqualTo(StatusAgendamento.CONCLUIDO)
        assertThat(status(b)).isEqualTo(StatusAgendamento.CONCLUIDO)
        // Pela agenda, o status muda mas nao grava horario: so o adesivador registra.
        assertThat(agendamentos.findById(a).get().concluidoEm).isNull()

        agenda.desfazer(autor())

        assertThat(status(a)).isEqualTo(StatusAgendamento.PROGRAMADO)
        assertThat(status(b)).isEqualTo(StatusAgendamento.PROGRAMADO)
    }

    @Test
    fun `exclui todos, e um desfazer traz todos de volta, mesmo em semanas diferentes`() {
        val a = criar(um, segunda, 2)
        val b = criar(outro, segunda.plusWeeks(2), 3)
        val fica = criar(um, segunda, 6)

        assertThat(agenda.excluirVarios(listOf(a, b), autor())).isEqualTo(2)
        assertThat(agendamentos.existsById(a)).isFalse()
        assertThat(agendamentos.existsById(b)).isFalse()
        assertThat(agendamentos.existsById(fica)).isTrue()

        agenda.desfazer(autor())

        val deVolta = agendamentos.findAll().filter { it.adesivador.id in setOf(um.id, outro.id) }.map { it.descricao }
        assertThat(deVolta).containsExactlyInAnyOrder("CARRO 2", "CARRO 3", "CARRO 6")
    }

    private fun onde(id: Long) = agendamentos.findById(id).get().let { Triple(it.data, it.adesivador.id, it.slotInicio) }

    private fun destino(id: Long, dia: LocalDate, coluna: Adesivador, faixa: Int) =
        DestinoNoLote(id = id, data = dia, adesivadorId = coluna.id, slotInicio = faixa, horasEstimadas = BigDecimal("1"))

    @Test
    fun `arrasta o grupo junto, e um desfazer volta todos`() {
        val a = criar(um, segunda, 2)
        val b = criar(outro, segunda, 3)

        // O grupo desce um dia e troca de coluna, cada um mantendo a posicao relativa.
        val movidos = agenda.moverVarios(
            listOf(destino(a, segunda.plusDays(1), outro, 2), destino(b, segunda.plusDays(1), um, 3)),
            autor()
        )

        assertThat(movidos).isEqualTo(2)
        assertThat(onde(a)).isEqualTo(Triple(segunda.plusDays(1), outro.id, 2))
        assertThat(onde(b)).isEqualTo(Triple(segunda.plusDays(1), um.id, 3))

        agenda.desfazer(autor())

        assertThat(onde(a)).isEqualTo(Triple(segunda, um.id, 2))
        assertThat(onde(b)).isEqualTo(Triple(segunda, outro.id, 3))
    }

    @Test
    fun `se algum cai em cima de um card de fora, nada se move`() {
        val a = criar(um, segunda, 2)
        val b = criar(um, segunda, 3)
        criar(um, segunda.plusDays(1), 3) // no caminho do b

        assertThatThrownBy {
            agenda.moverVarios(
                listOf(destino(a, segunda.plusDays(1), um, 2), destino(b, segunda.plusDays(1), um, 3)),
                autor()
            )
        }.isInstanceOf(RegraDeNegocioException::class.java).hasMessageContaining("no caminho")

        assertThat(onde(a)).isEqualTo(Triple(segunda, um.id, 2))
        assertThat(onde(b)).isEqualTo(Triple(segunda, um.id, 3))
    }
}
