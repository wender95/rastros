package com.rastros.config

import com.rastros.TesteIntegracao
import com.rastros.domain.Adesivador
import com.rastros.domain.Agendamento
import com.rastros.domain.FaixasDoDia
import com.rastros.domain.PerfilNome
import com.rastros.domain.SetorNome
import com.rastros.domain.StatusAgendamento
import com.rastros.domain.StatusFluxo
import com.rastros.domain.TipoAgendamento
import com.rastros.domain.TipoColunaAgenda
import com.rastros.domain.TipoEvento
import com.rastros.domain.Usuario
import com.rastros.repository.AdesivadorRepository
import com.rastros.repository.AgendamentoRepository
import com.rastros.repository.EventoMovimentacaoRepository
import com.rastros.repository.FluxoOsRepository
import com.rastros.repository.PerfilRepository
import com.rastros.repository.SetorRepository
import com.rastros.repository.TransicaoPermitidaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * A demonstracao tem de contar uma historia coerente: um projeto por dia para cada
 * adesivador, com inicio e conclusao registrados por ele; a OS do projeto passa pela Frota
 * com ele; nenhuma OS anda fora da matriz nem tem historico fora de ordem; e o que ja
 * estava na agenda (de verdade) nao e coberto nem apagado.
 */
class GeradorFicticioTest : TesteIntegracao() {

    @Autowired lateinit var gerador: GeradorFicticio
    @Autowired lateinit var adesivadores: AdesivadorRepository
    @Autowired lateinit var agendamentos: AgendamentoRepository
    @Autowired lateinit var fluxos: FluxoOsRepository
    @Autowired lateinit var eventos: EventoMovimentacaoRepository
    @Autowired lateinit var matriz: TransicaoPermitidaRepository
    @Autowired lateinit var perfis: PerfilRepository
    @Autowired lateinit var setores: SetorRepository

    private fun ficticios() = agendamentos.findAll().filter { it.observacao?.contains(GeradorFicticio.MARCA) == true }

    private fun adesivadorDaFrota(login: String) = usuarioRepository.save(
        Usuario(
            nome = login.uppercase(), login = login, senhaHash = "x",
            perfil = perfis.findByNome(PerfilNome.OPERACIONAL)!!, setor = setores.findByNome(SetorNome.FROTA)
        )
    )

    @Test
    fun `um projeto por dia para cada adesivador, com inicio e conclusao dele, e a OS passa pela Frota com ele`() {
        val heitor = usuarioRepository.findByLoginIgnoreCase("frota")!!
        val coluna = adesivadores.save(Adesivador(nome = "HEITOR", ordem = 1, usuario = heitor))

        gerador.gerarTudo()

        val cards = agendamentos.findAll().filter { it.adesivador.id == coluna.id && it.ehServico }
        assertThat(cards).isNotEmpty()
        // Como a alca que replica deixa: cada card ocupa um espaco so do dia (no maximo 2h30)...
        assertThat(cards).allSatisfy { assertThat(it.horasEstimadas).isLessThanOrEqualTo(BigDecimal("2.5")) }
        // ...as copias de um servico andam juntas (mesma OS, mesmo estado, mesmo inicio e conclusao)...
        val servicos = cards.groupBy { it.grupoId ?: it.id }.values
        assertThat(servicos).allSatisfy { copias ->
            assertThat(copias.map { listOf(it.status, it.ordemServico?.id, it.iniciadoEm, it.concluidoEm) }.distinct()).hasSize(1)
        }
        // ...e o dia tem varios servicos, nao um so.
        val servicosPorDia = servicos.flatMap { copias -> copias.map { it.data }.distinct() }.groupingBy { it }.eachCount()
        assertThat(servicosPorDia.values.count { it >= 2 }).isGreaterThan(servicosPorDia.size / 2)
        // Um servico depois do outro: nenhum comeca antes do anterior acabar.
        val execucoes = servicos.map { it.first() }.filter { it.iniciadoEm != null && it.concluidoEm != null }.sortedBy { it.iniciadoEm }
        execucoes.zipWithNext().forEach { (antes, depois) -> assertThat(depois.iniciadoEm).isAfterOrEqualTo(antes.concluidoEm) }

        val concluidos = cards.filter { it.status == StatusAgendamento.CONCLUIDO }
        assertThat(concluidos).isNotEmpty().allSatisfy { card ->
            assertThat(card.iniciadoPor?.id).isEqualTo(heitor.id)
            assertThat(card.conclusaoRegistradaPor?.id).isEqualTo(heitor.id)
            assertThat(card.concluidoEm).isAfter(card.iniciadoEm)
            assertThat(card.concluidoEm).isBefore(Instant.now())
            assertThat(card.ordemServico).isNotNull()
        }

        // A OS de cada projeto concluido entrou na Frota com ele e saiu com ele para o Patio.
        concluidos.map { it.ordemServico!! }.distinctBy { it.id }.forEach { os ->
            val daOs = fluxos.findByOrdemServicoIdOrderByIdAsc(os.id!!).flatMap { eventos.findByFluxoIdOrderByDataHoraAscIdAsc(it.id!!) }
            assertThat(daOs).anySatisfy {
                assertThat(it.tipoEvento).isEqualTo(TipoEvento.RECEBIMENTO)
                assertThat(it.setorDestino.nome).isEqualTo(SetorNome.FROTA)
                assertThat(it.usuario.id).isEqualTo(heitor.id)
            }
            assertThat(daOs).anySatisfy {
                assertThat(it.setorOrigem?.nome).isEqualTo(SetorNome.FROTA)
                assertThat(it.setorDestino.nome).isEqualTo(SetorNome.PATIO)
                assertThat(it.usuario.id).isEqualTo(heitor.id)
            }
        }
    }

    @Test
    fun `contorna a agenda de verdade, cada OS anda pela matriz em ordem, nao duplica e a limpeza tira tudo`() {
        val um = adesivadores.save(Adesivador(nome = "UM", ordem = 1, usuario = adesivadorDaFrota("um.frota")))
        // Um carro de verdade, lancado antes: o gerador contorna e a limpeza nao toca.
        val real = agendamentos.save(
            Agendamento(
                data = FaixasDoDia.diaUtilAFrente(LocalDate.now().minusDays(10), 1), adesivador = um, slotInicio = 1,
                tipo = TipoAgendamento.SERVICO, horasEstimadas = BigDecimal("20"), descricao = "CARRO REAL"
            )
        )
        adesivadores.save(Adesivador(nome = "DOIS", ordem = 2, usuario = adesivadorDaFrota("dois.frota")))
        // Coluna sem ninguem (e o Encaixe antigo): nao ganha projeto, pois ninguem iniciaria.
        val semNinguem = adesivadores.save(Adesivador(nome = "SEM CONTA", ordem = 3))
        adesivadores.save(Adesivador(nome = "ENCAIXE F", ordem = 90, tipo = TipoColunaAgenda.ENCAIXE))
        val fluxosAntes = fluxos.count()

        gerador.gerarTudo()

        val carros = ficticios()
        assertThat(carros).hasSizeGreaterThan(30)
        assertThat(carros).noneMatch { it.adesivador.id == semNinguem.id }
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
            assertThat(f.encerrado).isEqualTo(linha.last().tipoEvento in setOf(TipoEvento.CONCLUSAO, TipoEvento.CANCELAMENTO))
            if (linha.last().tipoEvento == TipoEvento.CONCLUSAO) assertThat(f.statusAtual).isEqualTo(StatusFluxo.ENCERRADA)
            // Todo despacho respeita a matriz de transicao.
            linha.filter { it.tipoEvento == TipoEvento.DESPACHO || it.tipoEvento == TipoEvento.ENTREGA }.forEach {
                assertThat(matriz.existsBySetorOrigemAndSetorDestino(it.setorOrigem!!.nome, it.setorDestino.nome)).isTrue()
            }
            // Como a extensao cria: o fluxo leva o nome do servico da OS.
            assertThat(f.identificadorFluxo).isEqualTo(f.ordemServico.servico!!.take(50))
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
