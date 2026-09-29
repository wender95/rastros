package com.rastros.service

import com.rastros.TesteIntegracao
import com.rastros.api.NovaOrdemRequest
import com.rastros.api.NovoFluxoRequest
import com.rastros.domain.Adesivador
import com.rastros.domain.Agendamento
import com.rastros.domain.SetorNome
import com.rastros.domain.TipoAgendamento
import com.rastros.repository.AdesivadorRepository
import com.rastros.repository.AgendamentoRepository
import com.rastros.repository.OrdemServicoRepository
import com.rastros.repository.SetorRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

/** A busca do relatorio: todos os meses, pelo numero da OS ou pelo carro/servico. */
class BuscaRelatorioTest : TesteIntegracao() {

    @Autowired lateinit var produtividade: ProdutividadeService
    @Autowired lateinit var fluxos: FluxoService
    @Autowired lateinit var adesivadores: AdesivadorRepository
    @Autowired lateinit var agendamentos: AgendamentoRepository
    @Autowired lateinit var ordens: OrdemServicoRepository
    @Autowired lateinit var setores: SetorRepository

    private lateinit var coluna: Adesivador

    @BeforeEach
    fun preparar() {
        coluna = adesivadores.save(Adesivador(nome = "BUSCA", ordem = 930))
    }

    private fun servico(dia: LocalDate, descricao: String, os: String? = null, tipo: TipoAgendamento = TipoAgendamento.SERVICO) {
        os?.let {
            fluxos.criarOrdem(
                NovaOrdemRequest(
                    numeroOsErp = it, cliente = "Cliente",
                    fluxos = listOf(NovoFluxoRequest(setorInicialId = setores.findByNome(SetorNome.FROTA)!!.id!!))
                ),
                autor("vendedor@rastros.cloud")
            )
        }
        agendamentos.save(
            Agendamento(
                data = dia, adesivador = coluna, slotInicio = 1, horasEstimadas = BigDecimal("2"),
                descricao = descricao, tipo = tipo,
                ordemServico = os?.let { ordens.findByNumeroOsErpIgnoreCase(it).single() }
            )
        )
    }

    @Test
    fun `acha pelo carro em qualquer mes, do mais recente para o mais antigo`() {
        servico(LocalDate.of(2031, 1, 6), "TAXI XPTO JANEIRO")
        servico(LocalDate.of(2031, 5, 5), "taxi xpto maio")
        servico(LocalDate.of(2031, 3, 3), "OUTRO CARRO")

        val achados = produtividade.buscarServicos("Xpto").servicos

        assertThat(achados.map { it.servico.descricao }).containsExactly("taxi xpto maio", "TAXI XPTO JANEIRO")
        assertThat(achados.map { it.adesivador }).containsOnly("BUSCA")
    }

    @Test
    fun `acha pelo numero da OS`() {
        servico(LocalDate.of(2031, 2, 3), "VAN DA OS", os = "887766")
        servico(LocalDate.of(2031, 2, 4), "SEM OS")

        val achados = produtividade.buscarServicos("8877").servicos

        assertThat(achados.map { it.servico.descricao }).containsExactly("VAN DA OS")
        assertThat(achados.single().servico.numeroOsErp).isEqualTo("887766")
    }

    @Test
    fun `bloqueio da agenda nao aparece, e termo curto nao busca`() {
        servico(LocalDate.of(2031, 2, 5), "FERIAS XPTO", tipo = TipoAgendamento.INDISPONIVEL)

        assertThat(produtividade.buscarServicos("ferias xpto").servicos).isEmpty()
        assertThat(produtividade.buscarServicos("x").servicos).isEmpty()
    }
}
