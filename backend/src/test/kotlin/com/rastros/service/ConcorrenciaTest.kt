package com.rastros.service

import com.rastros.TesteIntegracao
import com.rastros.api.NovaOrdemRequest
import com.rastros.api.NovoFluxoRequest
import com.rastros.api.ReceberRequest
import com.rastros.domain.SetorNome
import com.rastros.domain.StatusFluxo
import com.rastros.domain.TipoEvento
import com.rastros.repository.EventoMovimentacaoRepository
import com.rastros.repository.FluxoOsRepository
import com.rastros.repository.SetorRepository
import com.rastros.security.UsuarioAutenticado
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Duas pessoas clicando ao mesmo tempo. Aqui cada acao grava de verdade, em threads
 * separadas - por isso o teste sai da transacao desfeita da base e limpa o que criou.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConcorrenciaTest : TesteIntegracao() {

    @Autowired lateinit var fluxos: FluxoService
    @Autowired lateinit var fluxoRepository: FluxoOsRepository
    @Autowired lateinit var eventos: EventoMovimentacaoRepository
    @Autowired lateinit var setores: SetorRepository
    @Autowired lateinit var transacoes: PlatformTransactionManager
    @Autowired lateinit var jdbc: JdbcTemplate

    private val numeros = mutableListOf<String>()

    @AfterEach
    fun limpar() {
        numeros.forEach { numero ->
            val filtro = "select f.id from fluxos_os f join ordens_servico o on o.id = f.os_id where o.numero_os_erp = ?"
            jdbc.update("delete from eventos_movimentacao where fluxo_id in ($filtro)", numero)
            jdbc.update("delete from fluxos_os where id in ($filtro)", numero)
            jdbc.update("delete from ordens_servico where numero_os_erp = ?", numero)
        }
    }

    private fun pedido(numero: String) = NovaOrdemRequest(
        numeroOsErp = numero, cliente = "Cliente teste",
        fluxos = listOf(NovoFluxoRequest(identificador = "Adesivo", setorInicialId = setores.findByNome(SetorNome.CRIACAO)!!.id))
    )

    private fun abrirOs(numero: String): Int {
        numeros += numero
        return fluxos.criarOrdem(pedido(numero), autor("vendedor@rastros.cloud")).fluxos.single().id
    }

    /** Roda as duas acoes ao mesmo tempo; devolve o que cada uma deu (resultado ou excecao). */
    private fun aoMesmoTempo(vararg acoes: (CyclicBarrier) -> Unit): List<Throwable?> {
        val largada = CyclicBarrier(acoes.size)
        val pool = Executors.newFixedThreadPool(acoes.size)
        try {
            val futuros = acoes.map { acao -> pool.submit<Throwable?> { runCatching { acao(largada) }.exceptionOrNull() } }
            return futuros.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `duas pessoas recebendo a mesma OS ao mesmo tempo - so uma recebe`() {
        val id = abrirOs("CONC-RECEBER")
        val pessoas: List<UsuarioAutenticado> = List(2) { autor("criacao@rastros.cloud") } // o mesmo clique duas vezes, ou dois aparelhos

        val resultados = aoMesmoTempo(*pessoas.map { pessoa ->
            { largada: CyclicBarrier ->
                TransactionTemplate(transacoes).executeWithoutResult {
                    // As duas leem a OS ainda esperando recebimento antes de qualquer uma gravar:
                    // e exatamente a janela em que, sem a trava, as duas passavam na checagem.
                    fluxoRepository.findById(id).orElseThrow()
                    largada.await(10, TimeUnit.SECONDS)
                    fluxos.receber(id, ReceberRequest(), pessoa)
                }
            }
        }.toTypedArray())

        assertThat(resultados.count { it == null }).isEqualTo(1)
        assertThat(resultados.filterNotNull().single()).isInstanceOf(ConcurrencyFailureException::class.java)

        val fluxo = fluxoRepository.findById(id).orElseThrow()
        assertThat(fluxo.statusAtual).isEqualTo(StatusFluxo.EM_PROCESSAMENTO)
        assertThat(eventos.findByFluxoIdOrderByDataHoraAscIdAsc(id).count { it.tipoEvento == TipoEvento.RECEBIMENTO })
            .isEqualTo(1)
    }

    @Test
    fun `duas pessoas abrindo a mesma OS ao mesmo tempo - so uma entra`() {
        numeros += "CONC-ABRIR"
        val vendedor = autor("vendedor@rastros.cloud")

        val resultados = aoMesmoTempo(*Array(2) {
            { largada: CyclicBarrier ->
                TransactionTemplate(transacoes).executeWithoutResult {
                    largada.await(10, TimeUnit.SECONDS)
                    fluxos.criarOrdem(pedido("CONC-ABRIR"), vendedor)
                }
            }
        })

        assertThat(resultados.count { it == null }).isEqualTo(1)
        // Quem chega depois da primeira gravar ve o aviso; quem chega junto, o UNIQUE do banco.
        assertThat(resultados.filterNotNull().single())
            .isInstanceOfAny(RegraDeNegocioException::class.java, DataIntegrityViolationException::class.java)
        assertThat(jdbc.queryForObject("select count(*) from ordens_servico where numero_os_erp = 'CONC-ABRIR'", Int::class.java))
            .isEqualTo(1)
    }
}
