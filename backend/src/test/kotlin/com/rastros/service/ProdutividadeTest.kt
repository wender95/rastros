package com.rastros.service

import com.rastros.TesteIntegracao
import com.rastros.api.DespacharRequest
import com.rastros.api.NovaOrdemRequest
import com.rastros.api.NovoFluxoRequest
import com.rastros.api.ProdutividadeResponse
import com.rastros.api.ReceberRequest
import com.rastros.domain.SetorNome
import com.rastros.domain.SetorNome.*
import com.rastros.repository.SetorRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate

/**
 * Produtividade por setor e, dentro dele, por pessoa. O banco de teste ja tem a massa de
 * demonstracao, entao cada teste compara o antes e o depois das acoes que ele faz.
 */
class ProdutividadeTest : TesteIntegracao() {

    @Autowired lateinit var fluxos: FluxoService
    @Autowired lateinit var produtividade: ProdutividadeService
    @Autowired lateinit var setores: SetorRepository

    private fun setor(nome: SetorNome) = setores.findByNome(nome)!!.id!!
    private fun hoje(): ProdutividadeResponse = produtividade.calcular(LocalDate.now().minusDays(1), LocalDate.now())

    private fun ProdutividadeResponse.processadas(setor: SetorNome) =
        setores.firstOrNull { it.setor == setor }?.processadas ?: 0

    private fun ProdutividadeResponse.daPessoa(setor: SetorNome, nome: String) =
        setores.firstOrNull { it.setor == setor }?.pessoas?.firstOrNull { it.nome == nome }?.quantidade ?: 0

    private fun abrir(numero: String, quem: String, inicio: SetorNome = CRIACAO): Int =
        fluxos.criarOrdem(
            NovaOrdemRequest(numeroOsErp = numero, fluxos = listOf(NovoFluxoRequest(setorInicialId = setor(inicio)))),
            autor(quem)
        ).fluxos.single().id

    @Test
    fun `comercial conta as OS abertas por quem abriu`() {
        val antes = hoje().comercial
        abrir("P-1", "vendedor@rastros.cloud")
        abrir("P-2", "vendedor@rastros.cloud")
        abrir("P-3", "diretoria@rastros.cloud")

        val depois = hoje().comercial
        fun de(r: com.rastros.api.ProdutividadeComercialResponse, nome: String) =
            r.pessoas.firstOrNull { it.nome == nome }?.quantidade ?: 0

        assertThat(depois.abertas - antes.abertas).isEqualTo(3)
        assertThat(de(depois, "Marina Vendas") - de(antes, "Marina Vendas")).isEqualTo(2)
        assertThat(de(depois, "Ricardo Diretoria") - de(antes, "Ricardo Diretoria")).isEqualTo(1)
        assertThat(depois.pessoas).allMatch { it.horasMediaNoSetor == null }
    }

    @Test
    fun `no setor, a OS processada conta para o setor e para quem a recebeu, com o tempo no setor`() {
        val antes = hoje()
        val id = abrir("P-4", "vendedor@rastros.cloud")
        fluxos.receber(id, ReceberRequest(), autor("criacao@rastros.cloud"))
        fluxos.despachar(id, DespacharRequest(setorDestinoId = setor(IMPRESSAO)), autor("criacao@rastros.cloud"))

        val depois = hoje()
        val criacao = depois.setores.single { it.setor == CRIACAO }

        assertThat(depois.processadas(CRIACAO) - antes.processadas(CRIACAO)).isEqualTo(1)
        assertThat(depois.daPessoa(CRIACAO, "Bruno Criacao") - antes.daPessoa(CRIACAO, "Bruno Criacao")).isEqualTo(1)
        assertThat(criacao.horasMediaNoSetor).isNotNull()
        assertThat(criacao.pessoas.single { it.nome == "Bruno Criacao" }.horasMediaNoSetor).isNotNull()
        // Chegou na Impressao mas ninguem recebeu nem despachou: ainda nao foi processada la.
        assertThat(depois.processadas(IMPRESSAO)).isEqualTo(antes.processadas(IMPRESSAO))
    }

    @Test
    fun `devolver conta como processada e como retrabalho`() {
        val id = abrir("P-5", "vendedor@rastros.cloud")
        fluxos.receber(id, ReceberRequest(), autor("criacao@rastros.cloud"))
        fluxos.despachar(id, DespacharRequest(setorDestinoId = setor(IMPRESSAO)), autor("criacao@rastros.cloud"))
        fluxos.receber(id, ReceberRequest(), autor("impressao@rastros.cloud"))
        val antes = hoje().setores.first { it.setor == IMPRESSAO }

        fluxos.devolver(id, ReceberRequest(), autor("impressao@rastros.cloud"))

        val depois = hoje().setores.first { it.setor == IMPRESSAO }
        assertThat(depois.processadas - antes.processadas).isEqualTo(1)
        assertThat(depois.retornos - antes.retornos).isEqualTo(1)
    }

    @Test
    fun `no Financeiro, a processada e a OS concluida, e conta para quem concluiu`() {
        val id = abrir("P-6", "vendedor@rastros.cloud", ACABAMENTO)
        fluxos.receber(id, ReceberRequest(), autor("acabamento@rastros.cloud"))
        fluxos.despachar(id, DespacharRequest(setorDestinoId = setor(PRATELEIRA)), autor("acabamento@rastros.cloud"))
        fluxos.despachar(id, DespacharRequest(setorDestinoId = setor(FINANCEIRO)), autor("vendedor@rastros.cloud"))
        fluxos.receber(id, ReceberRequest(), autor("financeiro@rastros.cloud"))
        val antes = hoje()

        fluxos.concluir(id, ReceberRequest(), autor("financeiro@rastros.cloud"))

        val depois = hoje()
        assertThat(depois.processadas(FINANCEIRO) - antes.processadas(FINANCEIRO)).isEqualTo(1)
        assertThat(depois.daPessoa(FINANCEIRO, "Fernanda Financeiro") - antes.daPessoa(FINANCEIRO, "Fernanda Financeiro"))
            .isEqualTo(1)
        // A Prateleira e lugar, nao pessoa: conta a OS, mas nao tem quem recebeu.
        assertThat(depois.setores.single { it.setor == PRATELEIRA }.pessoas).isEmpty()
    }

    @Test
    fun `os setores vem na ordem da producao`() {
        val ordem = hoje().setores.map { it.setor.ordinal }
        assertThat(ordem).isSorted()
    }
}
