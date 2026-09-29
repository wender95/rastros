package com.rastros.service

import com.rastros.TesteIntegracao
import com.rastros.api.NovaParteRequest
import com.rastros.api.NovoAgendamentoRequest
import com.rastros.api.StatusAgendamentoRequest
import com.rastros.domain.Adesivador
import com.rastros.domain.StatusAgendamento
import com.rastros.repository.AdesivadorRepository
import com.rastros.repository.AgendamentoRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Um serviço feito em pedaços separados da agenda — por exemplo, começa numa sexta e
 * termina na terça seguinte, com o carro fora da oficina no meio.
 *
 * As partes são **o mesmo serviço**: nome, vendedor, OS, observação e estado andam juntos;
 * lugar e tamanho são de cada parte.
 */
class PartesAgendaTest : TesteIntegracao() {

    @Autowired lateinit var agenda: AgendaService
    @Autowired lateinit var adesivadores: AdesivadorRepository
    @Autowired lateinit var agendamentos: AgendamentoRepository

    /** Semana longe da base de demonstração; 04/03/2030 é segunda-feira. */
    private val segunda = LocalDate.of(2030, 3, 4)
    private val proximaSegunda = segunda.plusDays(7)

    private lateinit var coluna: Adesivador

    @BeforeEach
    fun preparar() {
        coluna = adesivadores.save(Adesivador(nome = "PARTES", ordem = 910))
    }

    private fun criar(dia: LocalDate, faixa: Int, horas: String, descricao: String = "TAXI 100"): Long =
        agenda.criar(
            NovoAgendamentoRequest(
                data = dia,
                adesivadorId = coluna.id,
                slotInicio = faixa,
                horasEstimadas = BigDecimal(horas),
                descricao = descricao,
                vendedorCodigo = "C"
            ),
            autor()
        ).id

    private fun continuar(id: Long, dia: LocalDate, faixa: Int, horas: String) =
        agenda.dividirEmParte(
            id,
            NovaParteRequest(data = dia, adesivadorId = coluna.id, slotInicio = faixa, horasEstimadas = BigDecimal(horas)),
            autor()
        )

    private fun lido(id: Long) = agendamentos.findById(id).get()

    @Test
    fun `colar o carro noutra semana cria outra parte do mesmo servico`() {
        val primeira = criar(segunda, 1, "3")

        val segunda2 = continuar(primeira, proximaSegunda, 2, "2")

        assertThat(lido(primeira).grupoId).isEqualTo(primeira)
        assertThat(lido(segunda2.id).grupoId).isEqualTo(primeira)
        assertThat(segunda2.partes).isEqualTo(2)
        // Cada parte tem o seu lugar e o seu tamanho.
        assertThat(lido(segunda2.id).data).isEqualTo(proximaSegunda)
        assertThat(lido(segunda2.id).horas).isEqualByComparingTo("2")
        assertThat(lido(primeira).horas).isEqualByComparingTo("3")
    }

    @Test
    fun `renomear uma parte renomeia o servico inteiro`() {
        val primeira = criar(segunda, 1, "3")
        val outra = continuar(primeira, proximaSegunda, 2, "2").id

        agenda.atualizar(
            outra,
            NovoAgendamentoRequest(
                data = proximaSegunda,
                adesivadorId = coluna.id,
                slotInicio = 2,
                horasEstimadas = BigDecimal("2"),
                descricao = "TAXI 100 ENVELOPAMENTO",
                vendedorCodigo = "M",
                observacao = "cliente busca sexta"
            ),
            autor()
        )

        val antiga = lido(primeira)
        assertThat(antiga.descricao).isEqualTo("TAXI 100 ENVELOPAMENTO")
        assertThat(antiga.vendedorCodigo).isEqualTo("M")
        assertThat(antiga.observacao).isEqualTo("cliente busca sexta")
        // O lugar e o tamanho da outra parte continuam os dela.
        assertThat(antiga.data).isEqualTo(segunda)
        assertThat(antiga.horas).isEqualByComparingTo("3")
    }

    @Test
    fun `marcar concluido numa parte marca todas`() {
        val primeira = criar(segunda, 1, "3")
        val outra = continuar(primeira, proximaSegunda, 2, "2").id

        agenda.alterarStatus(outra, StatusAgendamentoRequest(StatusAgendamento.CONCLUIDO).status!!, autor())

        assertThat(lido(primeira).status).isEqualTo(StatusAgendamento.CONCLUIDO)
    }

    @Test
    fun `mover uma parte nao mexe na outra`() {
        val primeira = criar(segunda, 1, "3")
        val outra = continuar(primeira, proximaSegunda, 2, "2").id

        agenda.alterarHoras(outra, BigDecimal("5.5"), autor())

        assertThat(lido(outra).horas).isEqualByComparingTo("5.5")
        assertThat(lido(primeira).horas).isEqualByComparingTo("3")
    }

    @Test
    fun `excluir uma parte deixa a outra, que volta a ser servico comum`() {
        val primeira = criar(segunda, 1, "3")
        val outra = continuar(primeira, proximaSegunda, 2, "2").id

        agenda.excluir(outra, autor())

        assertThat(agendamentos.findById(outra)).isEmpty
        val restante = lido(primeira)
        assertThat(restante.grupoId).isNull()
        assertThat(restante.descricao).isEqualTo("TAXI 100")
    }

    @Test
    fun `tres partes continuam ligadas entre si`() {
        val primeira = criar(segunda, 1, "3")
        val segundaParte = continuar(primeira, segunda.plusDays(1), 1, "2").id
        val terceira = continuar(segundaParte, proximaSegunda, 1, "2")

        assertThat(terceira.partes).isEqualTo(3)
        assertThat(lido(terceira.id).grupoId).isEqualTo(primeira)

        agenda.alterarStatus(primeira, StatusAgendamento.EM_PATIO, autor())
        assertThat(lido(segundaParte).status).isEqualTo(StatusAgendamento.EM_PATIO)
        assertThat(lido(terceira.id).status).isEqualTo(StatusAgendamento.EM_PATIO)
    }
}
