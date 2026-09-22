package com.ostracker.service

import com.ostracker.domain.Feriado
import com.ostracker.domain.HorarioComercial
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import com.ostracker.repository.FeriadoRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.LocalDate

data class FeriadoResponse(val id: Int, val data: LocalDate, val descricao: String)

/** Cadastro de feriados (Administracao) - alimenta o relogio de horario comercial das OS. */
@Service
class FeriadoService(private val feriadoRepository: FeriadoRepository) {

    @EventListener(ApplicationReadyEvent::class)
    fun recarregar() {
        HorarioComercial.feriados = feriadoRepository.findAll().map { it.data }.toSet()
    }

    fun listar(): List<FeriadoResponse> = feriadoRepository.findAllByOrderByDataAsc().map { it.paraResponse() }

    @Transactional
    fun adicionar(data: LocalDate, descricao: String): FeriadoResponse {
        val texto = descricao.trim().replace(Regex("\\s+"), " ")
        if (texto.isEmpty()) throw RegraDeNegocioException("Informe o nome do feriado.")
        if (texto.length > 80) throw RegraDeNegocioException("O nome pode ter no maximo 80 caracteres.")
        feriadoRepository.findByData(data)?.let {
            throw RegraDeNegocioException("$data ja esta cadastrado como ${it.descricao}.")
        }
        val salvo = feriadoRepository.save(Feriado(data = data, descricao = texto))
        recarregarAoConfirmar()
        return salvo.paraResponse()
    }

    @Transactional
    fun remover(id: Int) {
        val feriado = feriadoRepository.findById(id).orElseThrow { NaoEncontradoException("Feriado $id nao encontrado.") }
        feriadoRepository.delete(feriado)
        recarregarAoConfirmar()
    }

    /** O relogio so enxerga a mudanca depois que ela esta gravada de vez. */
    private fun recarregarAoConfirmar() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return recarregar()
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = recarregar()
        })
    }

    private fun Feriado.paraResponse() = FeriadoResponse(id!!, data, descricao)
}
