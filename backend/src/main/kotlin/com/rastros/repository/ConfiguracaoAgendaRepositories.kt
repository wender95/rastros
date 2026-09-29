package com.rastros.repository

import com.rastros.domain.StatusAgenda
import com.rastros.domain.VendedorAgenda
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface StatusAgendaRepository : JpaRepository<StatusAgenda, Long> {
    fun findAllByOrderByOrdemAsc(): List<StatusAgenda>
    fun findByNomeIgnoreCase(nome: String): StatusAgenda?
}

interface VendedorAgendaRepository : JpaRepository<VendedorAgenda, Int> {
    fun findAllByOrderByNomeAsc(): List<VendedorAgenda>
    fun findByCodigo(codigo: String): VendedorAgenda?
    fun findByNomeIgnoreCase(nome: String): VendedorAgenda?
}

/** O que a configuracao da agenda precisa saber dos cards. */
interface UsoNaAgendaRepository : JpaRepository<com.rastros.domain.Agendamento, Long> {
    fun countByVendedorCodigo(codigo: String): Long
    fun countByEtiquetaId(etiquetaId: Long): Long

    /** Um status removido: os cards que o usavam voltam para Programado. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Agendamento a set a.etiquetaId = null where a.etiquetaId = :id")
    fun tirarEtiqueta(@Param("id") id: Long): Int
}
