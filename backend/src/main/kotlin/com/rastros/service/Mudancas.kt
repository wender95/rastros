package com.rastros.service

import com.rastros.domain.Adesivador
import com.rastros.domain.Agendamento
import com.rastros.domain.EventoMovimentacao
import com.rastros.domain.FluxoOs
import com.rastros.domain.OrdemServico
import jakarta.annotation.PreDestroy
import jakarta.persistence.EntityManagerFactory
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.event.service.spi.EventListenerRegistry
import org.hibernate.event.spi.EventType
import org.hibernate.event.spi.PostCommitDeleteEventListener
import org.hibernate.event.spi.PostCommitInsertEventListener
import org.hibernate.event.spi.PostCommitUpdateEventListener
import org.hibernate.event.spi.PostDeleteEvent
import org.hibernate.event.spi.PostInsertEvent
import org.hibernate.event.spi.PostUpdateEvent
import org.hibernate.persister.entity.EntityPersister
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Avisa as telas abertas que algo mudou, para elas buscarem de novo **na hora** - o painel
 * ve o adesivador concluir o projeto sem ninguem apertar F5.
 *
 * Cada tela aberta mantem uma conexao (Server-Sent Events) e recebe so um recado curto:
 * "mudou: agenda" ou "mudou: os". Quem busca os dados e a propria tela, com as permissoes
 * de sempre - o aviso nao carrega dado nenhum.
 */
@Component
class CanalDeMudancas {

    private val log = LoggerFactory.getLogger(javaClass)
    private val telas = CopyOnWriteArrayList<SseEmitter>()
    private val pendentes = ConcurrentHashMap.newKeySet<String>()
    private val agendado = AtomicBoolean(false)
    private val relogio = Executors.newSingleThreadScheduledExecutor { Thread(it, "avisos-de-mudanca").apply { isDaemon = true } }

    init {
        // Um recado a cada 25s mantem a conexao viva e descobre quem ja fechou a tela.
        relogio.scheduleAtFixedRate({ telas.forEach { enviar(it, "batida", "") } }, 25, 25, TimeUnit.SECONDS)
    }

    /** Uma tela nova passa a receber os avisos, enquanto estiver aberta. */
    fun abrir(): SseEmitter {
        val tela = SseEmitter(0L) // sem prazo: fica aberta enquanto a tela estiver aberta
        telas += tela
        tela.onCompletion { telas -= tela }
        tela.onTimeout { telas -= tela }
        tela.onError { telas -= tela }
        enviar(tela, "pronto", "")
        return tela
    }

    /**
     * Anota o que mudou. Uma acao costuma gravar varias linhas (mover um card reempilha a
     * coluna): os avisos de um quarto de segundo viram um so.
     */
    fun avisar(assunto: String) {
        pendentes += assunto
        if (agendado.compareAndSet(false, true)) relogio.schedule(::despachar, 250, TimeUnit.MILLISECONDS)
    }

    val telasAbertas: Int get() = telas.size

    private val observadores = CopyOnWriteArrayList<(String) -> Unit>()

    /** Recebe cada recado despachado (usado nos testes). Fechar para de observar. */
    fun observar(observador: (String) -> Unit): AutoCloseable {
        observadores += observador
        return AutoCloseable { observadores -= observador }
    }

    private fun despachar() {
        agendado.set(false)
        val assuntos = pendentes.toList()
        pendentes.removeAll(assuntos.toSet())
        if (assuntos.isEmpty()) return
        val recado = assuntos.sorted().joinToString(",")
        telas.forEach { enviar(it, "mudou", recado) }
        observadores.forEach { runCatching { it(recado) } }
    }

    private fun enviar(tela: SseEmitter, nome: String, dados: String) {
        try {
            // O mesmo emissor pode ser usado pelo relogio e por quem abre a tela.
            synchronized(tela) { tela.send(SseEmitter.event().name(nome).data(dados)) }
        } catch (e: Exception) {
            telas -= tela // tela fechada: a conexao caiu
            runCatching { tela.complete() }
            log.debug("Tela fechada: {}", e.message)
        }
    }

    @PreDestroy
    fun fechar() {
        relogio.shutdownNow()
        telas.forEach { runCatching { it.complete() } }
    }
}

/**
 * Escuta **toda gravacao confirmada** no banco (depois do commit) e avisa o canal. Pegar no
 * banco, e nao em cada servico, garante que nada fica de fora: tela da agenda, Minha
 * agenda, setores, desfazer, importacao - qualquer caminho que grave avisa.
 */
@Component
class OuvinteDeGravacoes(
    entityManagerFactory: EntityManagerFactory,
    private val canal: CanalDeMudancas
) : PostCommitInsertEventListener, PostCommitUpdateEventListener, PostCommitDeleteEventListener {

    init {
        val registro = entityManagerFactory.unwrap(SessionFactoryImplementor::class.java)
            .serviceRegistry.getService(EventListenerRegistry::class.java)!!
        registro.appendListeners(EventType.POST_COMMIT_INSERT, this)
        registro.appendListeners(EventType.POST_COMMIT_UPDATE, this)
        registro.appendListeners(EventType.POST_COMMIT_DELETE, this)
    }

    override fun onPostInsert(event: PostInsertEvent) = avisar(event.entity)
    override fun onPostUpdate(event: PostUpdateEvent) = avisar(event.entity)
    override fun onPostDelete(event: PostDeleteEvent) = avisar(event.entity)

    override fun onPostInsertCommitFailed(event: PostInsertEvent) = Unit
    override fun onPostUpdateCommitFailed(event: PostUpdateEvent) = Unit
    override fun onPostDeleteCommitFailed(event: PostDeleteEvent) = Unit

    override fun requiresPostCommitHandling(persister: EntityPersister) = true

    private fun avisar(entidade: Any) {
        val assunto = when (entidade) {
            is Agendamento, is Adesivador, is com.rastros.domain.PausaProjeto -> "agenda"
            // A legenda de status e os vendedores: as telas buscam os nomes e as cores de novo.
            is com.rastros.domain.StatusAgenda, is com.rastros.domain.VendedorAgenda -> "legenda"
            is FluxoOs, is OrdemServico, is EventoMovimentacao -> "os"
            else -> return
        }
        canal.avisar(assunto)
    }
}
