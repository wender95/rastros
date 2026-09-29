package com.rastros.service

import com.rastros.domain.Adesivador
import com.rastros.repository.AdesivadorRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * O aviso de tempo real sai **depois do commit**: e o que faz o painel ver o adesivador
 * concluir sem F5. Estes testes gravam de verdade (sem a transacao desfeita da base) e
 * apagam o que gravaram.
 */
@SpringBootTest
@ActiveProfiles("test")
class MudancasTest {

    @Autowired lateinit var canal: CanalDeMudancas
    @Autowired lateinit var adesivadores: AdesivadorRepository
    @Autowired lateinit var transacoes: PlatformTransactionManager

    private fun recadosDurante(acao: () -> Unit): List<String> {
        // Deixa sair o que outro teste ainda tinha a avisar (os avisos saem 250 ms depois).
        Thread.sleep(600)
        val recados = CopyOnWriteArrayList<String>()
        canal.observar { recados += it }.use {
            acao()
            // Os avisos sao juntados por um quarto de segundo antes de sair.
            val limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (recados.isEmpty() && System.nanoTime() < limite) Thread.sleep(50)
        }
        return recados
    }

    @Test
    fun `gravar na agenda avisa as telas abertas, depois do commit`() {
        val tx = TransactionTemplate(transacoes)
        var id: Int? = null

        val recados = recadosDurante {
            id = tx.execute { adesivadores.save(Adesivador(nome = "TEMPO REAL", ordem = 990)).id }
        }

        assertThat(recados.joinToString(",")).contains("agenda")
        tx.executeWithoutResult { adesivadores.deleteById(id!!) }
    }

    @Test
    fun `transacao desfeita nao avisa ninguem`() {
        val tx = TransactionTemplate(transacoes)

        val recados = recadosDurante {
            tx.executeWithoutResult { status ->
                adesivadores.save(Adesivador(nome = "NUNCA GRAVADO", ordem = 991))
                adesivadores.flush()
                status.setRollbackOnly()
            }
        }

        assertThat(recados).isEmpty()
    }
}
