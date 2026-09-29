package com.rastros.service

import com.rastros.TesteIntegracao
import com.rastros.api.NovoAgendamentoRequest
import com.rastros.domain.Adesivador
import com.rastros.domain.Agendamento
import com.rastros.domain.TipoAgendamento
import com.rastros.repository.AdesivadorRepository
import com.rastros.repository.AgendamentoRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

/**
 * O empurrao so alcanca quem a edicao atingiu.
 *
 * A base importada da planilha tem servicos que invadem o seguinte (a conversao de
 * linhas em horas arredondava para cima). Antes, qualquer salvamento - ate sem mudar
 * nada - "corrigia" essas sobreposicoes empurrando tudo por 20 dias uteis, inclusive a
 * semana corrente.
 */
class ReempilharTest : TesteIntegracao() {

    @Autowired lateinit var agenda: AgendaService
    @Autowired lateinit var adesivadores: AdesivadorRepository
    @Autowired lateinit var agendamentos: AgendamentoRepository

    private val segunda = LocalDate.of(2030, 3, 4)
    private val terca = segunda.plusDays(1)
    private val quarta = segunda.plusDays(2)

    private lateinit var coluna: Adesivador

    @BeforeEach
    fun preparar() {
        coluna = adesivadores.save(Adesivador(nome = "TESTE", ordem = 900))
    }

    /** Grava direto no banco, como o importador antigo fazia - sem passar pelo reempilhar. */
    private fun legado(dia: LocalDate, faixa: Int, horas: String, descricao: String) = agendamentos.save(
        Agendamento(
            data = dia, adesivador = coluna, slotInicio = faixa, tipo = TipoAgendamento.SERVICO,
            horasEstimadas = BigDecimal(horas), descricao = descricao
        )
    ).id!!

    private fun criar(dia: LocalDate, faixa: Int, horas: String, tipo: TipoAgendamento = TipoAgendamento.SERVICO) =
        agenda.criar(
            NovoAgendamentoRequest(
                data = dia, adesivadorId = coluna.id, tipo = tipo, slotInicio = faixa,
                horasEstimadas = BigDecimal(horas), descricao = "NOVO"
            ),
            autor()
        ).id

    private fun salvarSemMudar(id: Long) {
        val a = agendamentos.findById(id).get()
        agenda.atualizar(
            id,
            NovoAgendamentoRequest(
                data = a.data, adesivadorId = a.adesivador.id, tipo = a.tipo!!, slotInicio = a.slotInicio,
                horasEstimadas = a.horas, descricao = a.descricao, status = a.status
            ),
            autor()
        )
    }

    private fun retrato() = agendamentos.findAll()
        .filter { it.adesivador.id == coluna.id }
        .associate { it.id to (it.data to it.slotInicio) }

    /** Segunda 15:00 com 3,5h invade a terca 07:30-09:00, onde ja comeca outro servico. */
    private fun baseComSobreposicaoAntiga(): Triple<Long, Long, Long> {
        val vaza = legado(segunda, 7, "3.5", "VAZA PARA TERCA")
        val invadido = legado(terca, 1, "2.5", "INVADIDO")
        val depois = legado(terca, 3, "2", "DEPOIS")
        return Triple(vaza, invadido, depois)
    }

    @Test
    fun `salvar sem mudar nada nao mexe em ninguem`() {
        val (vaza, invadido, depois) = baseComSobreposicaoAntiga()
        val antes = retrato()

        salvarSemMudar(vaza)
        salvarSemMudar(invadido)
        salvarSemMudar(depois)

        assertThat(retrato()).isEqualTo(antes)
    }

    @Test
    fun `mexer num servico nao corrige sobreposicao antiga de quem ninguem tocou`() {
        val (vaza, invadido, _) = baseComSobreposicaoAntiga()
        val cedo = legado(segunda, 1, "1.5", "SEGUNDA CEDO")

        agenda.alterarHoras(cedo, BigDecimal("2.5"), autor()) // cresce, mas nao encosta em ninguem

        assertThat(agendamentos.findById(vaza).get().slotInicio).isEqualTo(7)
        assertThat(agendamentos.findById(invadido).get().let { it.data to it.slotInicio }).isEqualTo(terca to 1)
    }

    @Test
    fun `quem a edicao atinge continua sendo empurrado, em cascata`() {
        val (_, _, depois) = baseComSobreposicaoAntiga()
        val ultimo = legado(terca, 6, "1.5", "TARDE")

        agenda.alterarHoras(depois, BigDecimal("4"), autor()) // 10:00 ate depois do almoco

        assertThat(agendamentos.findById(ultimo).get().slotInicio).isGreaterThan(6)
    }

    @Test
    fun `bloqueio marcado num dia ocupado toma o lugar e empurra os servicos`() {
        val servico = criar(quarta, 1, "1.5")

        criar(quarta, 1, "9", TipoAgendamento.INDISPONIVEL) // o adesivador faltou

        assertThat(agendamentos.findById(servico).get().let { it.data to it.slotInicio })
            .isEqualTo(quarta.plusDays(1) to 1)
    }

    @Test
    fun `servico longo que comecou num dia anterior continua ocupando o dia seguinte`() {
        criar(segunda, 1, "13.5") // segunda inteira + terca 07:30-12:00

        val novo = criar(terca, 1, "1.5")

        assertThat(agendamentos.findById(novo).get().slotInicio).isEqualTo(6) // 13:30
    }
}
