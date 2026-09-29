package com.rastros.repository

import com.rastros.domain.Adesivador
import com.rastros.domain.Agendamento
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
    /** As partes de um mesmo servico, na ordem em que acontecem. */
    fun findByGrupoIdOrderByDataAscSlotInicioAsc(grupoId: Long): List<Agendamento>
    fun findByGrupoIdIn(grupoIds: Collection<Long>): List<Agendamento>

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

    /**
     * Servicos de qualquer mes cujo carro/servico ou numero da OS contem o termo, do mais
     * recente para o mais antigo. Bloqueio de agenda (falta, ferias) nao entra.
     */
    @Query(
        """
        select a from Agendamento a left join a.ordemServico os
        where (a.tipo is null or a.tipo = :servico)
          and (lower(a.descricao) like lower(concat('%', :termo, '%'))
               or lower(os.numeroOsErp) like lower(concat('%', :termo, '%')))
        order by a.data desc, a.slotInicio asc
        """
    )
    fun buscarServicos(
        @Param("termo") termo: String,
        @Param("servico") servico: com.rastros.domain.TipoAgendamento,
        pagina: org.springframework.data.domain.Pageable
    ): List<Agendamento>


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

interface RetratoAgendaRepository : JpaRepository<com.rastros.domain.RetratoAgenda, Long> {

    fun findFirstByUsuarioIdOrderByIdDesc(usuarioId: Int): com.rastros.domain.RetratoAgenda?

    /** So os ids, do mais novo ao mais velho - para podar a pilha sem carregar os retratos. */
    @Query("select r.id from RetratoAgenda r where r.usuarioId = :usuarioId order by r.id desc")
    fun idsDoUsuario(@Param("usuarioId") usuarioId: Int): List<Long>
}

interface PausaProjetoRepository : JpaRepository<com.rastros.domain.PausaProjeto, Long> {
    fun findByServicoIdAndFimIsNull(servicoId: Long): List<com.rastros.domain.PausaProjeto>
    fun findByServicoIdInOrderByInicioAsc(servicoIds: Collection<Long>): List<com.rastros.domain.PausaProjeto>
}
