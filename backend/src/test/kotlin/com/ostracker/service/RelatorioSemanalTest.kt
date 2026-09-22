package com.ostracker.service

import com.ostracker.TesteIntegracao
import com.ostracker.domain.Adesivador
import com.ostracker.domain.Agendamento
import com.ostracker.domain.StatusAgendamento
import com.ostracker.domain.TipoAgendamento
import com.ostracker.domain.TipoColunaAgenda
import com.ostracker.repository.AdesivadorRepository
import com.ostracker.repository.AgendamentoRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

class RelatorioSemanalTest : TesteIntegracao() {

    @Autowired lateinit var produtividade: ProdutividadeService
    @Autowired lateinit var adesivadores: AdesivadorRepository
    @Autowired lateinit var agendamentos: AgendamentoRepository

    private val segunda = LocalDate.of(2030, 3, 4)

    private fun lancar(
        coluna: Adesivador,
        dia: LocalDate,
        faixa: Int,
        status: StatusAgendamento,
        score: String? = null,
        horas: String = "1",
        tipo: TipoAgendamento = TipoAgendamento.SERVICO
    ) = agendamentos.save(
        Agendamento(
            data = dia, adesivador = coluna, slotInicio = faixa, tipo = tipo,
            horasEstimadas = BigDecimal(horas), descricao = "CARRO $faixa",
            status = status, score = score?.let(::BigDecimal)
        )
    )

    @Test
    fun `o score da pessoa soma so os servicos concluidos`() {
        val pessoa = adesivadores.save(Adesivador(nome = "FULANO", ordem = 901))
        lancar(pessoa, segunda, 1, StatusAgendamento.CONCLUIDO, "3")
        lancar(pessoa, segunda, 2, StatusAgendamento.CONCLUIDO, "4.5")
        lancar(pessoa, segunda, 3, StatusAgendamento.PROGRAMADO, "10")
        lancar(pessoa, segunda, 4, StatusAgendamento.NAO_VEIO)
        lancar(pessoa, segunda.plusDays(1), 1, StatusAgendamento.PROGRAMADO, horas = "9",
            tipo = TipoAgendamento.INDISPONIVEL)

        val linha = produtividade.relatorioSemanal(segunda.plusDays(2)).adesivadores
            .single { it.adesivador == "FULANO" }

        assertThat(linha.totalServicos).isEqualTo(4) // o bloqueio nao e servico
        assertThat(linha.concluidos).isEqualTo(2)
        assertThat(linha.naoCompareceu).isEqualTo(1)
        assertThat(linha.score).isEqualByComparingTo("7.5")
        assertThat(linha.horasIndisponiveis).isEqualByComparingTo("9")
        assertThat(linha.servicos).hasSize(5)
    }

    @Test
    fun `a soma de pontos lancados conta todos os servicos com score, concluidos ou nao`() {
        val pessoa = adesivadores.save(Adesivador(nome = "PONTOS", ordem = 903))
        lancar(pessoa, segunda, 1, StatusAgendamento.CONCLUIDO, "3")
        lancar(pessoa, segunda, 2, StatusAgendamento.PROGRAMADO, "10")
        lancar(pessoa, segunda, 3, StatusAgendamento.PROGRAMADO)

        val linha = produtividade.relatorioSemanal(segunda).adesivadores.single { it.adesivador == "PONTOS" }

        assertThat(linha.score).isEqualByComparingTo("3")
        assertThat(linha.scoreLancado).isEqualByComparingTo("13")
    }

    @Test
    fun `servico que passa do fim do dia mostra o dia em que termina`() {
        val pessoa = adesivadores.save(Adesivador(nome = "LONGO", ordem = 904))
        lancar(pessoa, segunda, 1, StatusAgendamento.PROGRAMADO, horas = "20") // 9 h por dia: seg, ter e qua
        lancar(pessoa, segunda.plusDays(4), 1, StatusAgendamento.PROGRAMADO, horas = "10") // sexta e segunda
        lancar(pessoa, segunda.plusDays(1), 1, StatusAgendamento.PROGRAMADO, horas = "1")

        val servicos = produtividade.relatorioSemanal(segunda).adesivadores
            .single { it.adesivador == "LONGO" }.servicos.associateBy { it.data }

        with(servicos.getValue(segunda)) {
            assertThat(dias).isEqualTo(3)
            assertThat(dataFim).isEqualTo(segunda.plusDays(2))
            assertThat(diaSemanaFim).isEqualTo("QUA")
        }
        with(servicos.getValue(segunda.plusDays(4))) {
            assertThat(dias).isEqualTo(2)
            assertThat(dataFim).isEqualTo(segunda.plusDays(7)) // pula o fim de semana
            assertThat(diaSemanaFim).isEqualTo("SEG")
        }
        with(servicos.getValue(segunda.plusDays(1))) {
            assertThat(dias).isEqualTo(1)
            assertThat(dataFim).isEqualTo(data)
        }
    }

    @Test
    fun `sem nenhum score lancado o total e vazio, nao zero`() {
        val pessoa = adesivadores.save(Adesivador(nome = "SEM SCORE", ordem = 902))
        lancar(pessoa, segunda, 1, StatusAgendamento.CONCLUIDO)

        val linha = produtividade.relatorioSemanal(segunda).adesivadores
            .single { it.adesivador == "SEM SCORE" }

        assertThat(linha.score).isNull()
    }

    @Test
    fun `encaixe e noturno sao colunas da grade, nao pessoas`() {
        val encaixe = adesivadores.save(
            Adesivador(nome = "ENCAIXE TESTE", ordem = 990, tipo = TipoColunaAgenda.ENCAIXE)
        )
        lancar(encaixe, segunda, 1, StatusAgendamento.CONCLUIDO, "5")

        val nomes = produtividade.relatorioSemanal(segunda).adesivadores.map { it.adesivador }

        assertThat(nomes).doesNotContain("ENCAIXE TESTE")
    }
}
