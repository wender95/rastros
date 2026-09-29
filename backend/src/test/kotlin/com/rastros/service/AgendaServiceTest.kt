package com.rastros.service

import com.rastros.TesteIntegracao
import com.rastros.api.MoverAgendamentoRequest
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
 * Regras da agenda que o pessoal usa todo dia: horario exato, almoco protegido,
 * empurrar os de baixo, trocar arrastando e desfazer.
 */
class AgendaServiceTest : TesteIntegracao() {

    @Autowired lateinit var agenda: AgendaService
    @Autowired lateinit var adesivadores: AdesivadorRepository
    @Autowired lateinit var agendamentos: AgendamentoRepository

    /** Semana longe da base de demonstracao; 04/03/2030 e segunda-feira. */
    private val segunda = LocalDate.of(2030, 3, 4)
    private val sexta = segunda.plusDays(4)
    private val proximaSegunda = segunda.plusDays(7)

    private lateinit var coluna: Adesivador

    @BeforeEach
    fun preparar() {
        coluna = adesivadores.save(Adesivador(nome = "TESTE", ordem = 900))
    }

    private fun criar(
        dia: LocalDate,
        faixa: Int,
        horas: String,
        descricao: String = "CARRO",
        tipo: TipoAgendamento = TipoAgendamento.SERVICO
    ): Long = agenda.criar(
        NovoAgendamentoRequest(
            data = dia,
            adesivadorId = coluna.id,
            tipo = tipo,
            slotInicio = faixa,
            horasEstimadas = BigDecimal(horas),
            descricao = descricao
        ),
        autor()
    ).id

    private fun onde(id: Long) = agendamentos.findById(id).get().let { it.data to it.slotInicio }

    @Test
    fun `servico marcado num horario exato fica nele, mesmo com o dia vazio`() {
        val id = criar(segunda, 6, "1.5") // 13:30

        assertThat(onde(id)).isEqualTo(segunda to 6)
    }

    @Test
    fun `o almoco nao recebe inicio de servico`() {
        assertThatThrownBy { criar(segunda, 5, "1") }
            .isInstanceOf(RegraDeNegocioException::class.java)
            .hasMessageContaining("almoco")
    }

    @Test
    fun `aumentar as horas empurra o de baixo, pulando o almoco`() {
        val a = criar(segunda, 1, "2.5") // 07:30-10:00
        val b = criar(segunda, 3, "1") // 10:00

        agenda.alterarHoras(a, BigDecimal("4.5"), autor()) // agora 07:30-12:00

        assertThat(onde(b)).isEqualTo(segunda to 6) // 13:30, depois do almoco
    }

    @Test
    fun `diminuir as horas nao puxa o de baixo para cima`() {
        val a = criar(segunda, 1, "2.5")
        val b = criar(segunda, 3, "1")
        agenda.alterarHoras(a, BigDecimal("4.5"), autor())

        agenda.alterarHoras(a, BigDecimal("1.5"), autor())

        assertThat(onde(b)).isEqualTo(segunda to 6)
    }

    @Test
    fun `quem passa do fim do dia continua no proximo dia util`() {
        val b = criar(sexta, 8, "1") // 16:00 de sexta
        val a = criar(sexta, 1, "2.5")

        agenda.alterarHoras(a, BigDecimal("8"), autor()) // sexta inteira: ate as 17:00

        assertThat(onde(b)).isEqualTo(proximaSegunda to 1) // pula o fim de semana
    }

    @Test
    fun `arrastar um sobre o outro troca os dois de lugar`() {
        val a = criar(segunda, 1, "1.5", "A")
        val b = criar(segunda, 6, "1.5", "B")

        agenda.mover(a, MoverAgendamentoRequest(segunda, coluna.id, 6), autor())

        assertThat(onde(b)).isEqualTo(segunda to 1)
        assertThat(onde(a)).isEqualTo(segunda to 6)
    }

    @Test
    fun `desfazer devolve tambem quem tinha sido empurrado`() {
        val a = criar(segunda, 1, "2.5")
        val b = criar(segunda, 3, "1")
        agenda.alterarHoras(a, BigDecimal("4.5"), autor())

        agenda.desfazer(autor())

        assertThat(onde(b)).isEqualTo(segunda to 3)
        assertThat(agendamentos.findById(a).get().horas).isEqualByComparingTo("2.5")
    }

    @Test
    fun `servico que comecou na semana anterior vem junto com a semana que ele ainda ocupa`() {
        val longo = criar(sexta, 7, "7.5", "VEM DE SEXTA") // 15:00 de sexta ate 12:00 de segunda

        val seguinte = agenda.semana(proximaSegunda)
        val depois = agenda.semana(proximaSegunda.plusWeeks(1))

        assertThat(seguinte.continuacoes.map { it.id }).contains(longo)
        assertThat(seguinte.dias.flatMap { it.agendamentos }.map { it.id }).doesNotContain(longo)
        assertThat(depois.continuacoes.map { it.id }).doesNotContain(longo)
    }

    @Test
    fun `score nao aceita valor negativo`() {
        val id = criar(segunda, 1, "1.5")

        assertThatThrownBy { agenda.alterarScore(id, BigDecimal("-1"), autor()) }
            .isInstanceOf(RegraDeNegocioException::class.java)
    }

    @Test
    fun `bloqueio de horario nao recebe score`() {
        val id = criar(segunda, 1, "9", "FERIAS", TipoAgendamento.INDISPONIVEL)

        assertThatThrownBy { agenda.alterarScore(id, BigDecimal("3"), autor()) }
            .isInstanceOf(RegraDeNegocioException::class.java)
    }

    @Test
    fun `operador da frota nao remaneja a agenda`() {
        assertThatThrownBy {
            agenda.criar(
                NovoAgendamentoRequest(
                    data = segunda, adesivadorId = coluna.id, slotInicio = 1,
                    horasEstimadas = BigDecimal.ONE, descricao = "X"
                ),
                autor("frota@rastros.cloud")
            )
        }.isInstanceOf(PermissaoNegadaException::class.java)
    }

    private fun horas(id: Long) = agendamentos.findById(id).get().horasEstimadas!!

    // ------------------------------------------------ troca de tamanhos diferentes

    private fun faixas(id: Long) = agendamentos.findById(id).get().posicoesOcupadas.size

    @Test
    fun `maior sobre o menor na mesma coluna - trocam de ordem, encostados, e cada um mantem seus horarios`() {
        val menor = criar(segunda, 1, "3.5", "MENOR") // 07:30 as 11:00, 3 horarios
        val maior = criar(segunda, 4, "5.5", "MAIOR") // 11:00 as 18:00, 5 horarios
        val depois = criar(segunda.plusDays(1), 1, "1.5", "TERCA")

        agenda.mover(maior, MoverAgendamentoRequest(segunda, coluna.id, 1), autor())

        assertThat(onde(maior)).isEqualTo(segunda to 1)
        assertThat(faixas(maior)).isEqualTo(5)
        assertThat(onde(menor)).isEqualTo(segunda to 7) // logo depois do maior: 15:00
        assertThat(faixas(menor)).isEqualTo(3) // 15-16, 16-17, 17-18: nao vaza para terca
        assertThat(horas(menor)).isEqualByComparingTo("3")
        assertThat(onde(depois)).isEqualTo(segunda.plusDays(1) to 1) // ninguem empurrado
    }

    @Test
    fun `menor sobre o maior na mesma coluna tambem troca a ordem`() {
        val maior = criar(segunda, 1, "6", "MAIOR") // 07:30 as 15:00, 5 horarios
        val menor = criar(segunda, 7, "3", "MENOR") // 15:00 as 18:00, 3 horarios

        agenda.mover(menor, MoverAgendamentoRequest(segunda, coluna.id, 1), autor())

        assertThat(onde(menor)).isEqualTo(segunda to 1)
        assertThat(faixas(menor)).isEqualTo(3) // 07:30 as 11:00
        assertThat(onde(maior)).isEqualTo(segunda to 4) // 11:00, encostado
        assertThat(faixas(maior)).isEqualTo(5)
    }

    @Test
    fun `troca entre colunas mantem a quantidade de horarios de cada um`() {
        val outra = adesivadores.save(Adesivador(nome = "OUTRA", ordem = 901))
        val menor = criar(segunda, 1, "3.5", "MENOR") // 07:30 as 11:00, 3 horarios
        val maior = agenda.criar(
            NovoAgendamentoRequest(
                data = segunda, adesivadorId = outra.id, tipo = TipoAgendamento.SERVICO, slotInicio = 7,
                horasEstimadas = BigDecimal("12"), descricao = "MAIOR"
            ),
            autor()
        ).id

        agenda.mover(maior, MoverAgendamentoRequest(segunda, coluna.id, 1), autor())

        assertThat(onde(maior)).isEqualTo(segunda to 1)
        assertThat(agendamentos.findById(maior).get().adesivador.id).isEqualTo(coluna.id)
        assertThat(onde(menor)).isEqualTo(segunda to 7)
        assertThat(agendamentos.findById(menor).get().adesivador.id).isEqualTo(outra.id)
        assertThat(faixas(menor)).isEqualTo(3) // 15:00 as 18:00, sem vazar para terca
    }

    @Test
    fun `mover para uma faixa vazia mantem a quantidade de horarios`() {
        val id = criar(segunda, 7, "3", "TRES") // 15:00 as 18:00, 3 horarios

        agenda.mover(id, MoverAgendamentoRequest(segunda.plusDays(1), coluna.id, 1), autor())

        assertThat(onde(id)).isEqualTo(segunda.plusDays(1) to 1)
        assertThat(faixas(id)).isEqualTo(3) // 07:30 as 11:00
    }

    @Test
    fun `o almoco entre os dois nao conta como espaco na troca`() {
        val menor = criar(segunda, 3, "2", "MENOR") // 10:00 as 12:00
        val maior = criar(segunda, 6, "4.5", "MAIOR") // 13:30 as 18:00, 4 horarios

        agenda.mover(maior, MoverAgendamentoRequest(segunda, coluna.id, 3), autor())

        assertThat(onde(maior)).isEqualTo(segunda to 3) // 10-11, 11-12, 13:30-15, 15-16
        assertThat(faixas(maior)).isEqualTo(4)
        assertThat(onde(menor)).isEqualTo(segunda to 8) // 16:00, encostado
        assertThat(faixas(menor)).isEqualTo(2)
    }

    @Test
    fun `espaco vago entre os dois continua depois da troca`() {
        val a = criar(segunda, 1, "1.5", "A") // 07:30
        val b = criar(segunda, 4, "1", "B") // 11:00 - vagos 09:00 e 10:00 entre eles

        agenda.mover(b, MoverAgendamentoRequest(segunda, coluna.id, 1), autor())

        assertThat(onde(b)).isEqualTo(segunda to 1)
        assertThat(onde(a)).isEqualTo(segunda to 4) // B, dois vagos, A
    }

    // ------------------------------------ sexta ate 17h, carga e corte do mes

    @Test
    fun `na sexta nao ha horario das 17h e o servico longo pula para segunda`() {
        assertThatThrownBy { criar(sexta, 9, "1") }
            .isInstanceOf(RegraDeNegocioException::class.java)
            .hasMessageContaining("17:00")

        val id = criar(sexta, 8, "2") // 16:00 de sexta, 2h
        val carro = agendamentos.findById(id).get()

        assertThat(carro.posicoesOcupadas).hasSize(2) // 16-17 de sexta e 07:30 de segunda
        assertThat(carro.ultimoDia).isEqualTo(proximaSegunda)
    }

    @Test
    fun `a semana cheia tem 44 horas e a carga so conta o que cai nela`() {
        val quinta = segunda.plusDays(3)
        criar(quinta, 1, "27") // quinta 9h + sexta 8h nesta semana; o resto na seguinte

        val esta = agenda.semana(segunda)
        val seguinte = agenda.semana(proximaSegunda)

        assertThat(esta.horasUteisDaSemana).isEqualByComparingTo("44")
        assertThat(esta.horasOcupadas.single { it.adesivadorId == coluna.id }.total).isEqualByComparingTo("17")
        // 10h que sobram ocupam a segunda inteira (9h) e a faixa das 07:30 de terca (1h30).
        assertThat(seguinte.horasOcupadas.single { it.adesivadorId == coluna.id }.total).isEqualByComparingTo("10.5")
    }

    @Test
    fun `a semana e cortada na virada do mes`() {
        // 25/02/2030 e segunda; 01/03/2030 e sexta.
        val fevereiro = agenda.semana(LocalDate.of(2030, 2, 27))
        assertThat(fevereiro.inicio).isEqualTo(LocalDate.of(2030, 2, 25))
        assertThat(fevereiro.fim).isEqualTo(LocalDate.of(2030, 2, 28))
        assertThat(fevereiro.dias).hasSize(4)
        assertThat(fevereiro.horasUteisDaSemana).isEqualByComparingTo("36")

        val marco = agenda.semana(LocalDate.of(2030, 3, 1))
        assertThat(marco.inicio).isEqualTo(LocalDate.of(2030, 3, 1))
        assertThat(marco.dias.map { it.data }).containsExactly(LocalDate.of(2030, 3, 1))
        assertThat(marco.horasUteisDaSemana).isEqualByComparingTo("8")
    }

    @Test
    fun `o carro que comecou em fevereiro aparece como continuacao no comeco de marco`() {
        val id = criar(LocalDate.of(2030, 2, 28), 1, "13") // quinta 28/02 inteira e sexta 01/03

        val marco = agenda.semana(LocalDate.of(2030, 3, 1))

        assertThat(marco.continuacoes.map { it.id }).contains(id)
        // 4h na sexta ocupam 07:30-09:00, 09-10, 10-11 e 11-12: 4h30 da grade.
        assertThat(marco.horasOcupadas.single { it.adesivadorId == coluna.id }.total).isEqualByComparingTo("4.5")
    }
}
