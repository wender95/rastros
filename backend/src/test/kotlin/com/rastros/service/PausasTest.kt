package com.rastros.service

import com.rastros.TesteIntegracao
import com.rastros.domain.Adesivador
import com.rastros.domain.Agendamento
import com.rastros.domain.PausaProjeto
import com.rastros.domain.StatusAgendamento
import com.rastros.repository.AdesivadorRepository
import com.rastros.repository.AgendamentoRepository
import com.rastros.repository.PausaProjetoRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** O adesivador pausa e retoma o projeto; os horarios das pausas aparecem no relatorio. */
class PausasTest : TesteIntegracao() {

    @Autowired lateinit var minhaAgenda: MinhaAgendaService
    @Autowired lateinit var agenda: AgendaService
    @Autowired lateinit var produtividade: ProdutividadeService
    @Autowired lateinit var adesivadores: AdesivadorRepository
    @Autowired lateinit var agendamentos: AgendamentoRepository
    @Autowired lateinit var pausas: PausaProjetoRepository

    private val hoje = LocalDate.now()
    private val heitor get() = autor("frota@rastros.cloud") // Heitor Frota tem a coluna dele
    private lateinit var coluna: Adesivador

    @BeforeEach
    fun preparar() {
        coluna = adesivadores.save(Adesivador(nome = "HEITOR FROTA", ordem = 1))
    }

    private fun projeto(descricao: String) = agendamentos.save(
        Agendamento(
            data = hoje, adesivador = coluna, slotInicio = 1, horasEstimadas = BigDecimal.ONE, descricao = descricao
        )
    ).id!!

    @Test
    fun `pausar e retomar guardam os horarios, e a Minha agenda mostra o projeto pausado`() {
        val id = projeto("TAXI PAUSADO")
        minhaAgenda.iniciar(id, heitor)
        assertThatThrownBy { minhaAgenda.retomar(id, heitor) }.isInstanceOf(RegraDeNegocioException::class.java)

        minhaAgenda.pausar(id, heitor)
        val pausado = minhaAgenda.doDia(hoje, heitor).carros.single { it.agendamentoId == id }
        assertThat(pausado.pausadoDesde).isNotNull()
        assertThat(pausado.podeRetomar).isTrue()
        assertThat(pausado.podePausar).isFalse()
        assertThatThrownBy { minhaAgenda.pausar(id, heitor) }.isInstanceOf(RegraDeNegocioException::class.java)

        minhaAgenda.retomar(id, heitor)
        val retomado = minhaAgenda.doDia(hoje, heitor).carros.single { it.agendamentoId == id }
        assertThat(retomado.pausadoDesde).isNull()
        assertThat(retomado.pausas).hasSize(1).allSatisfy { assertThat(it.fim).isNotNull() }
        assertThat(retomado.podePausar).isTrue()
    }

    @Test
    fun `concluir um projeto pausado fecha a pausa, e o relatorio traz os horarios`() {
        val id = projeto("VAN PAUSADA")
        minhaAgenda.iniciar(id, heitor)
        // Uma pausa ja encerrada, com horario conhecido, e uma em aberto.
        val inicio = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES)
        pausas.save(PausaProjeto(servicoId = id, inicio = inicio, fim = inicio.plus(25, ChronoUnit.MINUTES)))
        minhaAgenda.pausar(id, heitor)

        minhaAgenda.concluir(id, heitor)
        assertThat(pausas.findByServicoIdAndFimIsNull(id)).isEmpty()

        val linha = produtividade.relatorio(hoje, hoje).adesivadores
            .single { it.adesivadorId == coluna.id }.servicos.single { it.agendamentoId == id }
        assertThat(linha.pausas).hasSize(2)
        assertThat(linha.pausas.first().inicio).isEqualTo(inicio)
        assertThat(linha.pausas.first().fim).isEqualTo(inicio.plus(25, ChronoUnit.MINUTES))
        assertThat(linha.pausas).allSatisfy { assertThat(it.fim).isNotNull() }
    }

    @Test
    fun `o Agora e so o ultimo que entrou em execucao, iniciado ou retomado`() {
        val primeiro = projeto("PRIMEIRO")
        val segundo = projeto("SEGUNDO")
        fun atual() = minhaAgenda.doDia(hoje, heitor).carros.filter { it.atual }.map { it.descricao }
        fun status(nome: String) = minhaAgenda.doDia(hoje, heitor).carros.single { it.descricao == nome }.status
        fun depois() = Thread.sleep(5) // uma acao depois da outra

        minhaAgenda.iniciar(primeiro, heitor)
        assertThat(atual()).containsExactly("PRIMEIRO")

        // Iniciou outro: ele toma o destaque e o anterior volta para a lista, ainda em execucao.
        depois(); minhaAgenda.iniciar(segundo, heitor)
        assertThat(atual()).containsExactly("SEGUNDO")
        assertThat(status("PRIMEIRO")).isEqualTo(StatusAgendamento.EXECUTANDO)

        // Pausado sai do destaque; o outro em execucao ocupa.
        depois(); minhaAgenda.pausar(segundo, heitor)
        assertThat(atual()).containsExactly("PRIMEIRO")
        depois(); minhaAgenda.pausar(primeiro, heitor)
        assertThat(atual()).isEmpty()

        // Retomado volta para o destaque (o ultimo retomado fica).
        depois(); minhaAgenda.retomar(primeiro, heitor)
        assertThat(atual()).containsExactly("PRIMEIRO")
        depois(); minhaAgenda.retomar(segundo, heitor)
        assertThat(atual()).containsExactly("SEGUNDO")

        // Concluido sai do destaque; o outro em execucao ocupa.
        depois(); minhaAgenda.concluir(segundo, heitor)
        assertThat(atual()).containsExactly("PRIMEIRO")
    }

    @Test
    fun `so pausa projeto em andamento, e o escritorio mudar o status fecha a pausa`() {
        val id = projeto("CAMINHAO")
        assertThatThrownBy { minhaAgenda.pausar(id, heitor) }.isInstanceOf(RegraDeNegocioException::class.java)

        minhaAgenda.iniciar(id, heitor)
        minhaAgenda.pausar(id, heitor)
        agenda.alterarStatus(id, StatusAgendamento.PROGRAMADO, autor())
        assertThat(pausas.findByServicoIdAndFimIsNull(id)).isEmpty()
    }
}
