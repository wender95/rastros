package com.rastros.service

import com.rastros.domain.Agendamento
import com.rastros.domain.PausaProjeto
import com.rastros.domain.Usuario
import com.rastros.repository.PausaProjetoRepository
import org.springframework.stereotype.Component
import java.time.Instant

/** Uma pausa, como a tela mostra: de quando a quando (sem fim: pausado agora). */
data class PausaResponse(
    val inicio: Instant,
    val fim: Instant?,
    /** Quem pausou e quem retomou (o relatorio mostra ao passar o mouse). */
    val pausadoPor: String? = null,
    val retomadoPor: String? = null
)

/**
 * As pausas dos projetos. A pausa e do servico inteiro, nao de uma parte: fica guardada na
 * chave do conjunto (o grupo das copias, ou o proprio card).
 */
@Component
class PausasDosProjetos(private val repositorio: PausaProjetoRepository) {

    fun chave(agendamento: Agendamento): Long = agendamento.grupoId ?: agendamento.id!!

    /** A pausa em andamento, se o servico esta pausado agora. */
    fun aberta(agendamento: Agendamento): PausaProjeto? =
        repositorio.findByServicoIdAndFimIsNull(chave(agendamento)).firstOrNull()

    fun pausar(agendamento: Agendamento, por: Usuario?, quando: Instant = Instant.now()): PausaProjeto {
        if (aberta(agendamento) != null) throw RegraDeNegocioException("\"${agendamento.descricao}\" ja esta pausado.")
        return repositorio.save(PausaProjeto(servicoId = chave(agendamento), inicio = quando, pausadoPor = por))
    }

    fun retomar(agendamento: Agendamento, por: Usuario?, quando: Instant = Instant.now()) {
        val pausa = aberta(agendamento)
            ?: throw RegraDeNegocioException("\"${agendamento.descricao}\" nao esta pausado.")
        pausa.fim = quando
        pausa.retomadoPor = por
        repositorio.save(pausa)
    }

    /** O servico saiu de "executando" (concluiu, voltou...): a pausa que estava aberta termina ali. */
    fun fecharAbertas(agendamento: Agendamento, quando: Instant = Instant.now()) {
        val abertas = repositorio.findByServicoIdAndFimIsNull(chave(agendamento))
        abertas.forEach { it.fim = quando }
        if (abertas.isNotEmpty()) repositorio.saveAll(abertas)
    }

    /** As pausas de varios servicos numa consulta so, por chave, da mais antiga para a mais nova. */
    fun dosServicos(chaves: Collection<Long>): Map<Long, List<PausaResponse>> =
        if (chaves.isEmpty()) emptyMap()
        else repositorio.findByServicoIdInOrderByInicioAsc(chaves.toSet())
            .groupBy({ it.servicoId }, { PausaResponse(it.inicio, it.fim, it.pausadoPor?.nome, it.retomadoPor?.nome) })
}
