package com.ostracker.service

import com.ostracker.domain.OrdemServico
import com.ostracker.domain.StatusAgendamento
import com.ostracker.domain.TipoAgendamento
import com.ostracker.repository.AgendamentoRepository
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
class AgendaDaFrotaService(private val agendamentoRepository: AgendamentoRepository) {

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

    private fun mudar(os: OrdemServico, de: Set<StatusAgendamento>, para: StatusAgendamento) {
        val carros = agendamentoRepository.findByOrdemServicoIdOrderByDataAsc(os.id!!)
            .filter { (it.tipo ?: TipoAgendamento.SERVICO) == TipoAgendamento.SERVICO && it.status in de }
        carros.forEach {
            it.status = para
            it.atualizadoEm = Instant.now()
        }
        agendamentoRepository.saveAll(carros)
        if (carros.isNotEmpty()) log.info("OS {}: {} carro(s) da agenda agora {}.", os.numeroOsErp, carros.size, para)
    }
}
