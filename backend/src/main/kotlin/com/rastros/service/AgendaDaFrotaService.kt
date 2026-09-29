package com.rastros.service

import com.rastros.domain.Agendamento
import com.rastros.domain.OrdemServico
import com.rastros.domain.SetorNome
import com.rastros.domain.StatusAgendamento
import com.rastros.domain.StatusFluxo
import com.rastros.domain.TipoAgendamento
import com.rastros.repository.AgendamentoRepository
import com.rastros.repository.FluxoOsRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * A agenda acompanha a Frota sozinha. Os carros da agenda ligados a uma OS mudam de
 * status quando a Frota mexe nessa OS:
 *
 * - a Frota **recebe** a OS -> o carro passa a **Executando**;
 * - a Frota **despacha para o Patio** -> **Concluido**;
 * - a Frota **devolve** a OS a quem mandou -> volta a **Programado** (o servico parou).
 *
 * So mexe em carro que ainda esta no caminho (Programado, Em patio, Executando): quem ja
 * foi marcado como Nao veio, Externo ou Concluido na mao fica como esta. Nao entra no
 * Desfazer da agenda - quem desfaz um recebimento e a propria tela do setor.
 */
@Service
class AgendaDaFrotaService(
    private val agendamentoRepository: AgendamentoRepository,
    private val fluxoRepository: FluxoOsRepository,
    private val pausas: PausasDosProjetos
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun frotaRecebeu(os: OrdemServico) =
        mudar(os, setOf(StatusAgendamento.PROGRAMADO, StatusAgendamento.EM_PATIO), StatusAgendamento.EXECUTANDO)

    fun frotaEntregouNoPatio(os: OrdemServico) =
        mudar(
            os,
            setOf(StatusAgendamento.PROGRAMADO, StatusAgendamento.EM_PATIO, StatusAgendamento.EXECUTANDO),
            StatusAgendamento.CONCLUIDO
        )

    fun frotaDevolveu(os: OrdemServico) =
        mudar(os, setOf(StatusAgendamento.EXECUTANDO), StatusAgendamento.PROGRAMADO)

    /**
     * Ligar a OS a um carro que **ja andou** deixa o carro no estado em que a OS esta.
     *
     * Sem isto, um carro ligado a OS depois que o material ja foi para o Patio ficava
     * Programado para sempre: as regras acima valem no momento do movimento e nao olham
     * para tras. O que foi marcado na mao (Nao veio, Externo, Concluido) fica como esta.
     */
    fun ajustarAoVincular(agendamento: Agendamento) {
        val os = agendamento.ordemServico ?: return
        if ((agendamento.tipo ?: TipoAgendamento.SERVICO) != TipoAgendamento.SERVICO) return
        if (agendamento.status !in setOf(StatusAgendamento.PROGRAMADO, StatusAgendamento.EM_PATIO)) return

        val ativos = fluxoRepository.findByOrdemServicoIdOrderByIdAsc(os.id!!)
            .filter { it.statusAtual != StatusFluxo.CANCELADA }
        if (ativos.isEmpty()) return

        val novo = when {
            // Todo o material ja saiu da producao (Patio, Prateleira, Financeiro) ou encerrou.
            ativos.all { it.encerrado || it.setorAtual.nome.saiuDaProducao } -> StatusAgendamento.CONCLUIDO
            // Esta na mao da Frota agora.
            ativos.any {
                it.setorAtual.nome == SetorNome.FROTA && it.statusAtual == StatusFluxo.EM_PROCESSAMENTO
            } -> StatusAgendamento.EXECUTANDO
            else -> return
        }
        if (novo == agendamento.status) return
        agendamento.status = novo
        agendamento.etiquetaId = null
        agendamento.atualizadoEm = Instant.now()
        if (novo != StatusAgendamento.EXECUTANDO) pausas.fecharAbertas(agendamento)
        agendamentoRepository.save(agendamento)
        log.info("Carro \"{}\" ligado a OS {}: agora {}.", agendamento.descricao, os.numeroOsErp, novo)
    }

    private fun mudar(os: OrdemServico, de: Set<StatusAgendamento>, para: StatusAgendamento) {
        val carros = agendamentoRepository.findByOrdemServicoIdOrderByDataAsc(os.id!!)
            .filter { (it.tipo ?: TipoAgendamento.SERVICO) == TipoAgendamento.SERVICO && it.status in de }
        carros.forEach {
            it.status = para
            it.etiquetaId = null
            it.atualizadoEm = Instant.now()
            if (para != StatusAgendamento.EXECUTANDO) pausas.fecharAbertas(it)
        }
        agendamentoRepository.saveAll(carros)
        if (carros.isNotEmpty()) log.info("OS {}: {} carro(s) da agenda agora {}.", os.numeroOsErp, carros.size, para)
    }
}
