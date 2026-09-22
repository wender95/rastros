package com.ostracker.service

import com.ostracker.TesteIntegracao
import com.ostracker.api.DespacharRequest
import com.ostracker.api.NovaOrdemRequest
import com.ostracker.api.NovoFluxoRequest
import com.ostracker.api.ReceberRequest
import com.ostracker.domain.SetorNome
import com.ostracker.repository.SetorRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate

/** O painel: a fotografia de agora e o que aconteceu no dia, semana, mes ou ano. */
class PainelTest : TesteIntegracao() {

    @Autowired lateinit var fluxos: FluxoService
    @Autowired lateinit var setores: SetorRepository

    private val hoje = LocalDate.now()
    private fun painel(de: LocalDate, ate: LocalDate) = fluxos.painel(autor(), de, ate)
    private fun saidas(p: com.ostracker.api.PainelResponse, setor: SetorNome) =
        p.porSetor.firstOrNull { it.setor == setor }?.saidasNoPeriodo ?: 0

    @Test
    fun `o que acontece hoje entra no dia, no mes e no ano, e nao num periodo passado`() {
        val antesDia = painel(hoje, hoje)
        val antesAno = painel(hoje.withDayOfYear(1), hoje.withDayOfYear(hoje.lengthOfYear()))

        val criacao = autor("criacao@ostracker.com")
        val id = fluxos.criarOrdem(
            NovaOrdemRequest(
                numeroOsErp = "PAINEL-1",
                fluxos = listOf(NovoFluxoRequest(setorInicialId = setores.findByNome(SetorNome.CRIACAO)!!.id!!))
            ),
            autor("vendedor@ostracker.com")
        ).fluxos.single().id
        fluxos.receber(id, ReceberRequest(), criacao)
        fluxos.despachar(id, DespacharRequest(setorDestinoId = setores.findByNome(SetorNome.IMPRESSAO)!!.id), criacao)

        val dia = painel(hoje, hoje)
        assertThat(dia.osAbertas).isEqualTo(antesDia.osAbertas + 1)
        assertThat(saidas(dia, SetorNome.CRIACAO)).isEqualTo(saidas(antesDia, SetorNome.CRIACAO) + 1)
        assertThat(dia.porSetor.single { it.setor == SetorNome.IMPRESSAO }.aguardando).isGreaterThan(0)

        val ano = painel(hoje.withDayOfYear(1), hoje.withDayOfYear(hoje.lengthOfYear()))
        assertThat(ano.osAbertas).isEqualTo(antesAno.osAbertas + 1)

        // Um periodo antigo nao tem nada disso - mas a fotografia de agora e a mesma.
        val passado = painel(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31))
        assertThat(passado.osAbertas).isZero()
        assertThat(passado.porSetor.sumOf { it.saidasNoPeriodo }).isZero()
        assertThat(passado.fluxosAtivos).isEqualTo(dia.fluxosAtivos)
    }
}
