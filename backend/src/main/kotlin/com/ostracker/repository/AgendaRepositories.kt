package com.ostracker.repository

import com.ostracker.domain.Adesivador
import com.ostracker.domain.Agendamento
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

interface AdesivadorRepository : JpaRepository<Adesivador, Int> {
    fun findAllByOrderByOrdemAsc(): List<Adesivador>
    fun findByAtivoTrueOrderByOrdemAsc(): List<Adesivador>
    fun findByNomeIgnoreCase(nome: String): Adesivador?
}

interface AgendamentoRepository : JpaRepository<Agendamento, Long> {

    @Query(
        """
        select a from Agendamento a
        where a.data between :inicio and :fim
        order by a.data asc, a.adesivador.ordem asc, a.slotInicio asc
        """
    )
    fun doPeriodo(@Param("inicio") inicio: LocalDate, @Param("fim") fim: LocalDate): List<Agendamento>

    /**
     * Lista, e nao um unico registro: quando o dia tem mais servicos do que faixas, os
     * excedentes dividem a ultima faixa.
     */
    fun findByDataAndAdesivadorIdAndSlotInicioOrderByIdAsc(
        data: LocalDate,
        adesivadorId: Int,
        slotInicio: Int
    ): List<Agendamento>

    /** Coluna do dia (um adesivador), usada para remanejar as faixas. */
    fun findByDataAndAdesivadorIdOrderBySlotInicioAsc(
        data: LocalDate,
        adesivadorId: Int
    ): List<Agendamento>

    /** Agenda de um adesivador num intervalo - base do reempilhamento entre dias. */
    @Query(
        """
        select a from Agendamento a
        where a.adesivador.id = :adesivadorId and a.data between :inicio and :fim
        order by a.data asc, a.slotInicio asc
        """
    )
    fun doPeriodoDoAdesivador(
        @Param("inicio") inicio: LocalDate,
        @Param("fim") fim: LocalDate,
        @Param("adesivadorId") adesivadorId: Int
    ): List<Agendamento>

    fun findByOrdemServicoIdOrderByDataAsc(osId: Int): List<Agendamento>

    fun findByOrdemServicoIdIn(osIds: Collection<Int>): List<Agendamento>

    /** Carros de um adesivador de um dia em diante - quem tem, nao pode sair da agenda. */
    fun countByAdesivadorIdAndDataGreaterThanEqual(adesivadorId: Int, data: LocalDate): Long

    fun countByAdesivadorId(adesivadorId: Int): Long

    /** Quantos agendamentos ha no periodo - a importacao so grava em periodo vazio. */
    fun countByDataBetween(inicio: LocalDate, fim: LocalDate): Long

    @Query(
        """
        select a from Agendamento a
        where (:somenteSemOs = false or a.ordemServico is null)
          and (
            lower(a.descricao) like lower(concat('%', :termo, '%'))
            or lower(coalesce(a.vendedorCodigo, '')) = lower(:termo)
          )
        order by a.data desc
        """
    )
    fun buscar(
        @Param("termo") termo: String,
        @Param("somenteSemOs") somenteSemOs: Boolean
    ): List<Agendamento>

    fun countByOrdemServicoIsNotNull(): Long
}

interface RetratoAgendaRepository : JpaRepository<com.ostracker.domain.RetratoAgenda, Long> {

    fun findFirstByUsuarioIdOrderByIdDesc(usuarioId: Int): com.ostracker.domain.RetratoAgenda?

    /** So os ids, do mais novo ao mais velho - para podar a pilha sem carregar os retratos. */
    @Query("select r.id from RetratoAgenda r where r.usuarioId = :usuarioId order by r.id desc")
    fun idsDoUsuario(@Param("usuarioId") usuarioId: Int): List<Long>
}
