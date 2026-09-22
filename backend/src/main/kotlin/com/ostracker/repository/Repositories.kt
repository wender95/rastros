package com.ostracker.repository

import com.ostracker.domain.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface FeriadoRepository : JpaRepository<Feriado, Int> {
    fun findAllByOrderByDataAsc(): List<Feriado>
    fun findByData(data: java.time.LocalDate): Feriado?
}

interface PerfilRepository : JpaRepository<Perfil, Int> {
    fun findByNome(nome: PerfilNome): Perfil?
}

interface SetorRepository : JpaRepository<Setor, Int> {
    fun findByNome(nome: SetorNome): Setor?
    fun findAllByOrderByNomeAsc(): List<Setor>
}

interface UsuarioRepository : JpaRepository<Usuario, Int> {
    fun findByEmailIgnoreCase(email: String): Usuario?

    fun findByLoginIgnoreCase(login: String): Usuario?
    fun findAllByOrderByNomeAsc(): List<Usuario>
}

interface OrdemServicoRepository : JpaRepository<OrdemServico, Int> {

    /** OS abertas num intervalo - a produtividade do comercial. */
    fun findByCriadoEmGreaterThanEqualAndCriadoEmLessThan(de: java.time.Instant, ate: java.time.Instant): List<OrdemServico>
    fun findByNumeroOsErpIgnoreCase(numero: String): List<OrdemServico>
}

interface FluxoOsRepository : JpaRepository<FluxoOs, Int> {

    fun findAllByOrderByIdDesc(): List<FluxoOs>

    fun findByOrdemServicoIdOrderByIdAsc(osId: Int): List<FluxoOs>

    /** Os fluxos de varias OS de uma vez - a agenda da semana pergunta por todas juntas. */
    fun findByOrdemServicoIdInOrderByIdAsc(osIds: Collection<Int>): List<FluxoOs>

    fun findBySetorAtualIdAndEncerradoFalseOrderByEntrouNoSetorEmAsc(setorId: Int): List<FluxoOs>

    @Query(
        """
        select f from FluxoOs f
        where lower(f.ordemServico.numeroOsErp) like lower(concat('%', :termo, '%'))
           or lower(coalesce(f.ordemServico.cliente, '')) like lower(concat('%', :termo, '%'))
           or lower(f.identificadorFluxo) like lower(concat('%', :termo, '%'))
        order by f.id desc
        """
    )
    fun buscarPorTermo(@Param("termo") termo: String): List<FluxoOs>

    /** Fluxos que passaram pelo setor informado (visibilidade do perfil Operacional - RF05). */
    @Query(
        """
        select distinct f from FluxoOs f
        where f.setorAtual.id = :setorId
           or exists (
                select e from EventoMovimentacao e
                where e.fluxo = f and (e.setorDestino.id = :setorId or e.setorOrigem.id = :setorId)
           )
        order by f.id desc
        """
    )
    fun findVisiveisParaSetor(@Param("setorId") setorId: Int): List<FluxoOs>
}

interface EventoMovimentacaoRepository : JpaRepository<EventoMovimentacao, Long> {
    fun findByFluxoIdOrderByDataHoraAscIdAsc(fluxoId: Int): List<EventoMovimentacao>
    fun findTop200ByOrderByDataHoraDescIdDesc(): List<EventoMovimentacao>

    fun findByDataHoraGreaterThanEqualAndDataHoraLessThan(de: java.time.Instant, ate: java.time.Instant): List<EventoMovimentacao>

    /** Fluxos que tiveram alguma movimentacao no periodo - base do calculo de produtividade. */
    @Query(
        """
        select distinct e.fluxo.id from EventoMovimentacao e
        where e.dataHora >= :de and e.dataHora < :ate
        """
    )
    fun fluxosComEventoNoPeriodo(
        @Param("de") de: java.time.Instant,
        @Param("ate") ate: java.time.Instant
    ): List<Int>

    /**
     * Trilha completa dos fluxos informados. Precisa ser completa, e nao so o recorte do
     * periodo: um recebimento anterior ao inicio ainda fecha um par de processamento.
     */
    fun findByFluxoIdInOrderByFluxoIdAscDataHoraAsc(fluxoIds: List<Int>): List<EventoMovimentacao>
}

interface TransicaoPermitidaRepository : JpaRepository<TransicaoPermitida, Int> {
    fun findBySetorOrigem(setorOrigem: SetorNome?): List<TransicaoPermitida>
    fun findBySetorOrigemIsNull(): List<TransicaoPermitida>
    fun existsBySetorOrigemAndSetorDestino(setorOrigem: SetorNome?, setorDestino: SetorNome): Boolean
}
