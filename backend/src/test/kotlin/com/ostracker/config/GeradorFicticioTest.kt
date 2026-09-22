package com.ostracker.config

import com.ostracker.TesteIntegracao
import com.ostracker.domain.Adesivador
import com.ostracker.domain.Agendamento
import com.ostracker.domain.TipoAgendamento
import com.ostracker.domain.FaixasDoDia
import com.ostracker.domain.StatusFluxo
import com.ostracker.domain.TipoColunaAgenda
import com.ostracker.domain.TipoEvento
import com.ostracker.repository.AdesivadorRepository
import com.ostracker.repository.AgendamentoRepository
import com.ostracker.repository.EventoMovimentacaoRepository
import com.ostracker.repository.FluxoOsRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class GeradorFicticioTest : TesteIntegracao() {

    @Autowired lateinit var gerador: GeradorFicticio
    @Autowired lateinit var adesivadores: AdesivadorRepository
    @Autowired lateinit var agendamentos: AgendamentoRepository
    @Autowired lateinit var fluxos: FluxoOsRepository
    @Autowired lateinit var eventos: EventoMovimentacaoRepository

    private fun ficticios() = agendamentos.findAll().filter { it.observacao?.contains(GeradorFicticio.MARCA) == true }

    @Test
    fun `gera agenda sem carro em cima de carro e OS com trajetoria coerente, e a limpeza tira tudo`() {
        val um = adesivadores.save(Adesivador(nome = "UM", ordem = 1))
        // Um carro de verdade, lancado antes: o gerador contorna e a limpeza nao toca.
        val real = agendamentos.save(
            Agendamento(
                data = FaixasDoDia.diaUtilAFrente(LocalDate.now().minusDays(10), 1), adesivador = um, slotInicio = 1,
                tipo = TipoAgendamento.SERVICO, horasEstimadas = BigDecimal("20"), descricao = "CARRO REAL"
            )
        )
        adesivadores.save(Adesivador(nome = "DOIS", ordem = 2))
        adesivadores.save(Adesivador(nome = "ENCAIXE F", ordem = 90, tipo = TipoColunaAgenda.ENCAIXE))
        val fluxosAntes = fluxos.count()

        gerador.gerarTudo()

        val carros = ficticios()
        assertThat(carros).hasSizeGreaterThan(30)
        // Nenhuma faixa de nenhum dia ocupada duas vezes na mesma coluna.
        val celulas = (carros + real).flatMap { a ->
            a.posicoesOcupadas.map { p ->
                Triple(a.adesivador.id, FaixasDoDia.diaUtilAFrente(a.data, p / FaixasDoDia.QUANTIDADE), p % FaixasDoDia.QUANTIDADE)
            }
        }
        assertThat(celulas).doesNotHaveDuplicates()

        val novos = fluxos.findAll().drop(fluxosAntes.toInt())
        assertThat(novos).isNotEmpty()
        val agora = Instant.now()
        novos.forEach { f ->
            val linha = eventos.findByFluxoIdOrderByDataHoraAscIdAsc(f.id!!)
            assertThat(linha.first().tipoEvento).isEqualTo(TipoEvento.CRIACAO)
            assertThat(linha.map { it.dataHora }).isSorted().allMatch { !it.isAfter(agora) }
            assertThat(f.encerrado).isEqualTo(linha.last().tipoEvento == TipoEvento.CONCLUSAO)
            if (f.encerrado) assertThat(f.statusAtual).isEqualTo(StatusFluxo.ENCERRADA)
        }
        assertThat(novos.count { it.encerrado }).isGreaterThan(0)
        assertThat(novos.count { !it.encerrado }).isGreaterThan(0)

        gerador.gerarTudo() // segunda vez nao duplica
        assertThat(ficticios()).hasSize(carros.size)

        gerador.apagarTudo()
        assertThat(ficticios()).isEmpty()
        assertThat(fluxos.count()).isEqualTo(fluxosAntes)
        assertThat(agendamentos.findById(real.id!!)).isPresent
    }
}
