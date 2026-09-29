package com.rastros.api

import com.rastros.TesteIntegracao
import com.rastros.service.ConfiguracaoAgendaService
import com.rastros.service.PermissaoNegadaException
import com.rastros.service.RegraDeNegocioException
import com.rastros.service.Remocao
import com.rastros.service.AgendaService
import com.rastros.domain.Adesivador
import com.rastros.repository.AdesivadorRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

/** Os vendedores da agenda: cadastrados na propria agenda, pela Diretoria e o Administrador. */
class VendedoresTest : TesteIntegracao() {

    @Autowired lateinit var vendedores: VendedoresController
    @Autowired lateinit var configuracao: ConfiguracaoAgendaService
    @Autowired lateinit var agenda: AgendaService
    @Autowired lateinit var adesivadores: AdesivadorRepository

    @Test
    fun `a demonstracao comeca com os vendedores do comercial`() {
        val porCodigo = vendedores.listar().associate { it.codigo to it.nome }
        assertThat(porCodigo["M"]).isEqualTo("Marina Vendas")
        assertThat(porCodigo["C"]).isEqualTo("Caio Comercial")
    }

    @Test
    fun `sem codigo vale a primeira letra, sem acento, e letra repetida pede outro codigo`() {
        val novo = configuracao.adicionarVendedor("  Érica   Souza ", null, autor())
        assertThat(novo.codigo).isEqualTo("E")
        assertThat(novo.nome).isEqualTo("Érica Souza")

        assertThatThrownBy { configuracao.adicionarVendedor("Eduardo", null, autor()) }
            .isInstanceOf(RegraDeNegocioException::class.java)
            .hasMessageContaining("Érica Souza")
        assertThat(configuracao.adicionarVendedor("Eduardo", "ed", autor()).codigo).isEqualTo("ED")
    }

    @Test
    fun `renomear muda so o nome, e o codigo gravado nos cards fica`() {
        val v = configuracao.adicionarVendedor("Karina", "K", autor())
        val renomeado = configuracao.renomearVendedor(v.id, "Karina Lima", autor())
        assertThat(renomeado.codigo).isEqualTo("K")
        assertThat(renomeado.nome).isEqualTo("Karina Lima")
    }

    @Test
    fun `sem card sai de vez, com card sai da lista mas o nome continua para os cards antigos`() {
        val semCard = configuracao.adicionarVendedor("Paulo", null, autor())
        assertThat(configuracao.removerVendedor(semCard.id, autor()).resultado).isEqualTo(Remocao.APAGADO)
        assertThat(vendedores.listar().map { it.codigo }).doesNotContain("P")

        val comCard = configuracao.adicionarVendedor("Quiteria", null, autor())
        val coluna = adesivadores.save(Adesivador(nome = "VEND-TESTE", ordem = 970))
        agenda.criar(
            NovoAgendamentoRequest(
                data = LocalDate.of(2030, 3, 4), adesivadorId = coluna.id, slotInicio = 1,
                horasEstimadas = BigDecimal.ONE, descricao = "CARRO Q", vendedorCodigo = "Q"
            ),
            autor()
        )
        assertThat(configuracao.removerVendedor(comCard.id, autor()).resultado).isEqualTo(Remocao.RETIRADO_DA_AGENDA)
        assertThat(vendedores.listar().first { it.codigo == "Q" }.ativo).isFalse()

        assertThat(configuracao.restaurarVendedor(comCard.id, autor()).ativo).isTrue()
    }

    @Test
    fun `so a diretoria e o administrador editam`() {
        assertThatThrownBy { configuracao.adicionarVendedor("Rita", null, autor("vendedor@rastros.cloud")) }
            .isInstanceOf(PermissaoNegadaException::class.java)
    }
}
