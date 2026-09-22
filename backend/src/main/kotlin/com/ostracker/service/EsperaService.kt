package com.ostracker.service

import com.ostracker.api.FluxoResponse
import com.ostracker.domain.SetorNome
import com.ostracker.repository.AgendamentoRepository
import com.ostracker.repository.FluxoOsRepository
import com.ostracker.repository.SetorRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Uma OS parada na Prateleira ou no Patio, com os carros da agenda ligados a ela. */
data class OsEmEsperaResponse(
    val fluxo: FluxoResponse,
    /** Descricao dos carros da agenda desta OS (ex.: "TAXI 888 SPIN COMPLETO"). */
    val servicos: List<String>
)

/**
 * A tela "Patio e prateleira": o que espera nos dois locais para o comercial liberar ao
 * Financeiro. Traz junto o servico da agenda, para achar a OS pelo carro.
 */
@Service
class EsperaService(
    private val fluxoRepository: FluxoOsRepository,
    private val setorRepository: SetorRepository,
    private val agendamentoRepository: AgendamentoRepository
) {

    @Transactional(readOnly = true)
    fun emEspera(): List<OsEmEsperaResponse> {
        val fluxos = listOf(SetorNome.PRATELEIRA, SetorNome.PATIO)
            .mapNotNull { setorRepository.findByNome(it)?.id }
            .flatMap { fluxoRepository.findBySetorAtualIdAndEncerradoFalseOrderByEntrouNoSetorEmAsc(it) }
        val osIds = fluxos.mapNotNull { it.ordemServico.id }.toSet()
        // Os carros de todas as OS numa consulta so.
        val servicosPorOs = if (osIds.isEmpty()) emptyMap()
        else agendamentoRepository.findByOrdemServicoIdIn(osIds)
            .groupBy({ it.ordemServico!!.id!! }, { it.descricao })
        return fluxos.map { f ->
            OsEmEsperaResponse(f.paraResponse(), servicosPorOs[f.ordemServico.id].orEmpty().distinct())
        }
    }
}
