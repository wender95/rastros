package com.rastros.service

import com.rastros.TesteIntegracao
import com.rastros.api.NovaParteRequest
import com.rastros.api.NovoAgendamentoRequest
import com.rastros.domain.Adesivador
import com.rastros.domain.TipoColunaAgenda
import com.rastros.repository.AdesivadorRepository
import com.rastros.repository.AgendamentoRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

/** Coluna Noturno: os adesivadores atribuidos a cada servico, e o card dela no painel. */
class NoturnoTest : TesteIntegracao() {

    @Autowired lateinit var agenda: AgendaService
    @Autowired lateinit var minhaAgenda: MinhaAgendaService
    @Autowired lateinit var adesivadores: AdesivadorRepository
    @Autowired lateinit var agendamentos: AgendamentoRepository

    /** 04/03/2030 e segunda. */
    private val segunda = LocalDate.of(2030, 3, 4)
    private lateinit var noturno: Adesivador
    private lateinit var ana: Adesivador
    private lateinit var beto: Adesivador

    @BeforeEach
    fun preparar() {
        noturno = adesivadores.findAll().firstOrNull { it.tipo == TipoColunaAgenda.NOTURNO && it.ativo }
            ?: adesivadores.save(Adesivador(nome = "NOTURNO", tipo = TipoColunaAgenda.NOTURNO, ordem = 990))
        ana = adesivadores.save(Adesivador(nome = "ANA NOITE", ordem = 980))
        beto = adesivadores.save(Adesivador(nome = "BETO NOITE", ordem = 981))
    }

    private fun criar(coluna: Adesivador, faixa: Int = 1) = agenda.criar(
        NovoAgendamentoRequest(
            data = segunda, adesivadorId = coluna.id, slotInicio = faixa,
            horasEstimadas = BigDecimal.ONE, descricao = "ONIBUS ${coluna.nome} $faixa"
        ),
        autor()
    ).id

    @Test
    fun `atribuir vale para as copias, aparece no card do Noturno do painel e o desfazer volta`() {
        val servico = criar(noturno, 2)
        agenda.replicar(servico, listOf(NovaParteRequest(segunda, noturno.id, 3, BigDecimal.ONE)), autor())

        val resposta = agenda.atribuir(servico, listOf(beto.id!!, ana.id!!), autor())
        assertThat(resposta.atribuidos.map { it.nome }).containsExactly("ANA NOITE", "BETO NOITE")
        assertThat(agendamentos.findAll().filter { it.grupoId == servico }.map { a -> a.atribuidos.map { it.nome }.toSet() })
            .hasSize(2).allSatisfy { assertThat(it).containsExactlyInAnyOrder("ANA NOITE", "BETO NOITE") }

        val cardDoNoturno = minhaAgenda.agendasDoDia(segunda).single { it.adesivadorId == noturno.id }
        assertThat(cardDoNoturno.noturno).isTrue()
        assertThat(cardDoNoturno.carros.single { it.agendamentoId == servico }.atribuidos).containsExactly("ANA NOITE", "BETO NOITE")

        agenda.atribuir(servico, emptyList(), autor())
        assertThat(agendamentos.findById(servico).get().atribuidos).isEmpty()
        agenda.desfazer(autor())
        assertThat(agendamentos.findById(servico).get().atribuidos.map { it.nome }).containsExactlyInAnyOrder("ANA NOITE", "BETO NOITE")
    }

    @Test
    fun `so servico do Noturno recebe atribuidos, e so adesivador pode ser atribuido`() {
        val deDia = criar(ana, 4)
        assertThatThrownBy { agenda.atribuir(deDia, listOf(beto.id!!), autor()) }
            .isInstanceOf(RegraDeNegocioException::class.java)

        val daNoite = criar(noturno, 6)
        assertThatThrownBy { agenda.atribuir(daNoite, listOf(noturno.id!!), autor()) }
            .isInstanceOf(RegraDeNegocioException::class.java)
    }
}
