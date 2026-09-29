package com.rastros.service

import com.rastros.TesteIntegracao
import com.rastros.api.DespacharRequest
import com.rastros.api.NovaOrdemRequest
import com.rastros.api.NovoAgendamentoRequest
import com.rastros.api.NovoFluxoRequest
import com.rastros.api.ReceberRequest
import com.rastros.api.VinculoOsRequest
import com.rastros.domain.Adesivador
import com.rastros.domain.SetorNome
import com.rastros.domain.StatusAgendamento
import com.rastros.repository.AdesivadorRepository
import com.rastros.repository.AgendamentoRepository
import com.rastros.repository.SetorRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Carro ligado à OS **depois** que o material já andou.
 *
 * As regras da Frota valem no momento do movimento: se o vínculo não existia na hora em
 * que a OS foi para o Pátio, o carro ficava Programado para sempre. Ao ligar a OS, o
 * carro passa a assumir o estado em que ela está.
 */
class VinculoDepoisDoTrabalhoTest : TesteIntegracao() {

    @Autowired lateinit var agenda: AgendaService
    @Autowired lateinit var fluxos: FluxoService
    @Autowired lateinit var adesivadores: AdesivadorRepository
    @Autowired lateinit var agendamentos: AgendamentoRepository
    @Autowired lateinit var setores: SetorRepository

    /** 04/03/2030 é segunda. */
    private val segunda = LocalDate.of(2030, 3, 4)
    private val frota get() = autor("frota@rastros.cloud")
    private val vendedor get() = autor("vendedor@rastros.cloud")
    private lateinit var coluna: Adesivador

    @BeforeEach
    fun preparar() {
        coluna = adesivadores.save(Adesivador(nome = "HEITOR FROTA", ordem = 1))
    }

    private fun osNaFrota(numero: String): Pair<Int, Int> {
        val os = fluxos.criarOrdem(
            NovaOrdemRequest(
                numeroOsErp = numero,
                cliente = "Cooperativa Vila Nova",
                fluxos = listOf(NovoFluxoRequest(setorInicialId = setores.findByNome(SetorNome.FROTA)!!.id!!)),
            ),
            vendedor,
        )
        return os.id to os.fluxos.single().id
    }

    private fun carroSemOs(descricao: String, faixa: Int = 1): Long = agenda.criar(
        NovoAgendamentoRequest(
            data = segunda,
            adesivadorId = coluna.id,
            slotInicio = faixa,
            horasEstimadas = BigDecimal("2"),
            descricao = descricao,
        ),
        vendedor,
    ).id

    private fun status(id: Long) = agendamentos.findById(id).get().status

    @Test
    fun `OS ja entregue no Patio deixa o carro concluido assim que e ligada a ele`() {
        val (_, fluxo) = osNaFrota("VD-1")
        fluxos.receber(fluxo, ReceberRequest(), frota)
        val patio = setores.findByNome(SetorNome.PATIO)!!.id!!
        fluxos.despachar(fluxo, DespacharRequest(setorDestinoId = patio), frota)

        // Só agora alguém liga a OS ao carro da agenda.
        val carro = carroSemOs("TAXI 900 COMPLETO")
        assertThat(status(carro)).isEqualTo(StatusAgendamento.PROGRAMADO)

        agenda.vincularOs(carro, VinculoOsRequest(numeroOsErp = "VD-1"), vendedor)

        assertThat(status(carro)).isEqualTo(StatusAgendamento.CONCLUIDO)
    }

    @Test
    fun `OS na mao da Frota agora deixa o carro executando`() {
        val (_, fluxo) = osNaFrota("VD-2")
        fluxos.receber(fluxo, ReceberRequest(), frota)

        val carro = carroSemOs("VAN 55 ENVELOPAMENTO", faixa = 3)
        agenda.vincularOs(carro, VinculoOsRequest(numeroOsErp = "VD-2"), vendedor)

        assertThat(status(carro)).isEqualTo(StatusAgendamento.EXECUTANDO)
    }

    @Test
    fun `OS que ainda nao chegou na Frota nao mexe no carro`() {
        osNaFrota("VD-3") // criada, ainda esperando ser recebida

        val carro = carroSemOs("S10 FAIXAS", faixa = 4)
        agenda.vincularOs(carro, VinculoOsRequest(numeroOsErp = "VD-3"), vendedor)

        assertThat(status(carro)).isEqualTo(StatusAgendamento.PROGRAMADO)
    }

    @Test
    fun `carro ja marcado na mao como Nao veio nao e mexido pelo vinculo`() {
        val (_, fluxo) = osNaFrota("VD-4")
        fluxos.receber(fluxo, ReceberRequest(), frota)
        fluxos.despachar(fluxo, DespacharRequest(setorDestinoId = setores.findByNome(SetorNome.PATIO)!!.id!!), frota)

        val carro = carroSemOs("HILUX LOGO", faixa = 6)
        agenda.alterarStatus(carro, StatusAgendamento.NAO_VEIO, vendedor)

        agenda.vincularOs(carro, VinculoOsRequest(numeroOsErp = "VD-4"), vendedor)

        assertThat(status(carro)).isEqualTo(StatusAgendamento.NAO_VEIO)
    }

    @Test
    fun `criar o carro ja com a OS pronta tambem nasce concluido`() {
        val (osId, fluxo) = osNaFrota("VD-5")
        fluxos.receber(fluxo, ReceberRequest(), frota)
        fluxos.despachar(fluxo, DespacharRequest(setorDestinoId = setores.findByNome(SetorNome.PATIO)!!.id!!), frota)

        val carro = agenda.criar(
            NovoAgendamentoRequest(
                data = segunda,
                adesivadorId = coluna.id,
                slotInicio = 7,
                horasEstimadas = BigDecimal("2"),
                descricao = "KOMBI FAIXAS",
                osId = osId,
            ),
            vendedor,
        ).id

        assertThat(status(carro)).isEqualTo(StatusAgendamento.CONCLUIDO)
    }
}
