package com.ostracker.service

import com.ostracker.TesteIntegracao
import com.ostracker.api.NovaOrdemRequest
import com.ostracker.api.NovoFluxoRequest
import com.ostracker.api.ReceberRequest
import com.ostracker.domain.Adesivador
import com.ostracker.domain.Agendamento
import com.ostracker.domain.SetorNome
import com.ostracker.domain.StatusAgendamento
import com.ostracker.repository.AdesivadorRepository
import com.ostracker.repository.AgendamentoRepository
import com.ostracker.repository.OrdemServicoRepository
import com.ostracker.repository.SetorRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

/** A aba "Minha agenda" do adesivador: so a coluna dele, dia a dia, com o receber da OS. */
class MinhaAgendaTest : TesteIntegracao() {

    @Autowired lateinit var minhaAgenda: MinhaAgendaService
    @Autowired lateinit var fluxos: FluxoService
    @Autowired lateinit var adesivadores: AdesivadorRepository
    @Autowired lateinit var agendamentos: AgendamentoRepository
    @Autowired lateinit var ordens: OrdemServicoRepository
    @Autowired lateinit var setores: SetorRepository

    /** 04/03/2030 e segunda. */
    private val segunda = LocalDate.of(2030, 3, 4)
    private val frota get() = autor("frota@ostracker.com") // Heitor Frota
    private lateinit var minhaColuna: Adesivador
    private lateinit var outraColuna: Adesivador

    @BeforeEach
    fun preparar() {
        minhaColuna = adesivadores.save(Adesivador(nome = "HEITOR FROTA", ordem = 1))
        outraColuna = adesivadores.save(Adesivador(nome = "OUTRO", ordem = 2))
    }

    private fun osNaFrota(numero: String): Int = fluxos.criarOrdem(
        NovaOrdemRequest(
            numeroOsErp = numero, cliente = "Coopertaxi",
            fluxos = listOf(NovoFluxoRequest(setorInicialId = setores.findByNome(SetorNome.FROTA)!!.id!!))
        ),
        autor("vendedor@ostracker.com")
    ).fluxos.single().id

    private fun carro(coluna: Adesivador, dia: LocalDate, faixa: Int, horas: String, descricao: String, os: String? = null) =
        agendamentos.save(
            Agendamento(
                data = dia, adesivador = coluna, slotInicio = faixa, horasEstimadas = BigDecimal(horas),
                descricao = descricao, ordemServico = os?.let { ordens.findByNumeroOsErpIgnoreCase(it).single() }
            )
        ).id!!

    @Test
    fun `mostra so os carros da coluna dele, com o botao de receber a OS que esta na Frota`() {
        val fluxo = osNaFrota("MA-1")
        carro(minhaColuna, segunda, 1, "3.5", "TAXI 888 SPIN COMPLETO", "MA-1")
        carro(outraColuna, segunda, 1, "2", "DE OUTRO")

        val dia = minhaAgenda.doDia(segunda, frota)

        assertThat(dia.adesivador).isEqualTo("HEITOR FROTA")
        assertThat(dia.carros.map { it.descricao }).containsExactly("TAXI 888 SPIN COMPLETO")
        val carro = dia.carros.single()
        assertThat(carro.horarioInicio).isEqualTo("07:30")
        assertThat(carro.horarioFim).isEqualTo("11:00")
        assertThat(carro.os!!.fluxoId).isEqualTo(fluxo)
        assertThat(carro.os!!.podeReceber).isTrue()
    }

    @Test
    fun `recebida pela tela, o carro passa a executando e o botao some`() {
        val fluxo = osNaFrota("MA-2")
        val id = carro(minhaColuna, segunda, 1, "3.5", "TAXI 123", "MA-2")

        fluxos.receber(fluxo, ReceberRequest(), frota)

        val carro = minhaAgenda.doDia(segunda, frota).carros.single()
        assertThat(carro.status).isEqualTo(StatusAgendamento.EXECUTANDO)
        assertThat(carro.os!!.podeReceber).isFalse()
        assertThat(carro.os!!.recebidoPor).isEqualTo("Heitor Frota")
        assertThat(agendamentos.findById(id).get().status).isEqualTo(StatusAgendamento.EXECUTANDO)
    }

    @Test
    fun `recebida, o adesivador entrega no Patio pela propria agenda e o carro fica concluido`() {
        val fluxo = osNaFrota("MA-4")
        val id = carro(minhaColuna, segunda, 1, "3.5", "TAXI 456", "MA-4")
        fluxos.receber(fluxo, ReceberRequest(), frota)

        val os = minhaAgenda.doDia(segunda, frota).carros.single().os!!
        assertThat(os.podeEntregar).isTrue()
        assertThat(os.patioId).isEqualTo(setores.findByNome(SetorNome.PATIO)!!.id)

        fluxos.despachar(fluxo, com.ostracker.api.DespacharRequest(setorDestinoId = os.patioId), frota)

        val depois = minhaAgenda.doDia(segunda, frota).carros.single()
        assertThat(depois.status).isEqualTo(StatusAgendamento.CONCLUIDO)
        assertThat(depois.os!!.podeEntregar).isFalse()
        assertThat(depois.os!!.setorAtual).isEqualTo(SetorNome.PATIO)
        assertThat(agendamentos.findById(id).get().status).isEqualTo(StatusAgendamento.CONCLUIDO)
    }

    @Test
    fun `servico longo aparece em cada dia que ocupa, com o horario daquele dia`() {
        carro(minhaColuna, segunda, 6, "13.5", "CAMINHAO BAU") // seg 13:30-18:00 (4.5h) + ter inteira (9h)

        val terca = minhaAgenda.doDia(segunda.plusDays(1), frota).carros.single()

        assertThat(terca.horarioInicio).isEqualTo("07:30")
        assertThat(terca.horarioFim).isEqualTo("18:00")
        assertThat(terca.comecaEm).isEqualTo(segunda)
        assertThat(minhaAgenda.doDia(segunda.plusDays(2), frota).carros).isEmpty()
    }

    @Test
    fun `quem nao e da Frota ve a agenda mas nao recebe, e quem nao adesiva nao tem agenda`() {
        adesivadores.save(Adesivador(nome = "Elisa Recorte", ordem = 3)).also {
            osNaFrota("MA-3")
            carro(it, segunda, 1, "2", "ADESIVO", "MA-3")
        }
        val daRecorte = minhaAgenda.doDia(segunda, autor("recorte@ostracker.com")).carros.single()
        assertThat(daRecorte.os!!.podeReceber).isFalse()

        assertThatThrownBy { minhaAgenda.doDia(segunda, autor("impressao@ostracker.com")) }
            .isInstanceOf(NaoEncontradoException::class.java)
        assertThat(minhaAgenda.colunaDe(usuarioRepository.findByEmailIgnoreCase("impressao@ostracker.com")!!)).isNull()
    }
}
