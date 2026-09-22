package com.ostracker.service

import com.ostracker.domain.*
import com.ostracker.repository.AdesivadorRepository
import com.ostracker.repository.AgendamentoRepository
import com.ostracker.repository.FluxoOsRepository
import com.ostracker.repository.UsuarioRepository
import com.ostracker.security.UsuarioAutenticado
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate

/** A OS de um carro da agenda, do ponto de vista de quem vai adesivar. */
data class OsDoCarroResponse(
    val osId: Int,
    val numeroOsErp: String,
    val cliente: String?,
    /** O fluxo que interessa a Frota (o que esta nela, ou o que ainda esta a caminho). */
    val fluxoId: Int?,
    val setorAtual: SetorNome?,
    val statusFluxo: StatusFluxo?,
    val recebidoPor: String?,
    /** A OS esta na Frota esperando e quem esta vendo e da Frota: o botao Receber aparece. */
    val podeReceber: Boolean,
    /** Recebida na Frota: o carro ficou pronto e pode ir para o Patio. */
    val podeEntregar: Boolean = false,
    /** Setor do Patio, destino do Entregar. */
    val patioId: Int? = null,
    /** Recebida na Frota: pode voltar para o setor de onde veio (ex.: material com defeito). */
    val devolverPara: SetorNome? = null
)

data class CarroDoDiaResponse(
    val agendamentoId: Long,
    val descricao: String,
    val tipo: TipoAgendamento,
    val status: StatusAgendamento,
    /** Horario deste carro NESTE dia (um servico longo aparece em cada dia que ocupa). */
    val horarioInicio: String,
    val horarioFim: String,
    val comecaEm: LocalDate,
    val terminaEm: LocalDate,
    val horasEstimadas: BigDecimal,
    val vendedorCodigo: String?,
    val observacao: String?,
    val os: OsDoCarroResponse?
)

data class MinhaAgendaResponse(
    val adesivadorId: Int,
    val adesivador: String,
    val data: LocalDate,
    val carros: List<CarroDoDiaResponse>
)

/**
 * "Minha agenda": o adesivador ve so a coluna dele, dia a dia, com o botao de receber a
 * OS de cada carro. A coluna e dele pelo vinculo explicito com a conta ou, sem ele, pelo
 * nome (a coluna ANDRE e do usuario Andre).
 */
@Service
class MinhaAgendaService(
    private val adesivadorRepository: AdesivadorRepository,
    private val agendamentoRepository: AgendamentoRepository,
    private val fluxoRepository: FluxoOsRepository,
    private val usuarioRepository: UsuarioRepository,
    private val setorRepository: com.ostracker.repository.SetorRepository
) {

    /** A coluna da agenda desta pessoa, ou nulo se ela nao adesiva. */
    fun colunaDe(usuario: Usuario): Adesivador? {
        val colunas = adesivadorRepository.findByAtivoTrueOrderByOrdemAsc().filter { it.tipo == TipoColunaAgenda.ADESIVADOR }
        return colunas.firstOrNull { it.usuario?.id == usuario.id }
            ?: colunas.firstOrNull { it.usuario == null && it.nome.trim().equals(usuario.nome.trim(), ignoreCase = true) }
    }

    @Transactional(readOnly = true)
    fun doDia(dia: LocalDate, autor: UsuarioAutenticado): MinhaAgendaResponse {
        val usuario = usuarioRepository.findById(autor.id).orElseThrow { NaoEncontradoException("Usuario nao encontrado.") }
        val coluna = colunaDe(usuario)
            ?: throw NaoEncontradoException("Voce nao tem uma coluna na agenda dos adesivadores.")
        val daFrota = autor.perfil == PerfilNome.OPERACIONAL && autor.setor == SetorNome.FROTA

        // Quem comecou antes e ainda ocupa este dia tambem aparece.
        val carros = agendamentoRepository
            .doPeriodoDoAdesivador(dia.minusWeeks(4), dia, coluna.id!!)
            .mapNotNull { a ->
                val hoje = a.posicoesOcupadas
                    .filter { FaixasDoDia.diaUtilAFrente(a.data, it / FaixasDoDia.QUANTIDADE) == dia }
                    .map { it % FaixasDoDia.QUANTIDADE + 1 }
                if (hoje.isEmpty()) null else a to hoje
            }
            .sortedBy { (_, faixas) -> faixas.first() }

        val osIds = carros.mapNotNull { it.first.ordemServico?.id }.toSet()
        val patioId = setorRepository.findByNome(SetorNome.PATIO)?.id
        val fluxosPorOs = if (osIds.isEmpty()) emptyMap()
        else fluxoRepository.findByOrdemServicoIdInOrderByIdAsc(osIds).groupBy { it.ordemServico.id!! }

        return MinhaAgendaResponse(
            adesivadorId = coluna.id!!,
            adesivador = coluna.nome,
            data = dia,
            carros = carros.map { (a, faixas) ->
                CarroDoDiaResponse(
                    agendamentoId = a.id!!,
                    descricao = a.descricao,
                    tipo = a.tipo ?: TipoAgendamento.SERVICO,
                    status = a.status,
                    horarioInicio = FaixasDoDia.de(faixas.first()).inicio,
                    horarioFim = FaixasDoDia.de(faixas.last()).fim,
                    comecaEm = a.data,
                    terminaEm = a.ultimoDia,
                    horasEstimadas = a.horas,
                    vendedorCodigo = a.vendedorCodigo,
                    observacao = a.observacao,
                    os = a.ordemServico?.let { os -> osDoCarro(os, fluxosPorOs[os.id].orEmpty(), daFrota, patioId) }
                )
            }
        )
    }

    private fun osDoCarro(os: OrdemServico, fluxos: List<FluxoOs>, daFrota: Boolean, patioId: Int?): OsDoCarroResponse {
        val ativos = fluxos.filter { !it.encerrado }
        val fluxo = ativos.firstOrNull { it.setorAtual.nome == SetorNome.FROTA } ?: ativos.firstOrNull() ?: fluxos.lastOrNull()
        val podeReceber = daFrota && fluxo != null && !fluxo.encerrado &&
            fluxo.setorAtual.nome == SetorNome.FROTA && fluxo.statusAtual == StatusFluxo.AGUARDANDO_RECEBIMENTO
        // A Frota so despacha para o Patio; devolver volta a quem mandou (a Frota nao e destino normal de ninguem na volta).
        val naFrotaRecebida = daFrota && fluxo != null && !fluxo.encerrado &&
            fluxo.setorAtual.nome == SetorNome.FROTA && fluxo.statusAtual == StatusFluxo.EM_PROCESSAMENTO
        return OsDoCarroResponse(
            osId = os.id!!,
            numeroOsErp = os.numeroOsErp,
            cliente = os.cliente,
            fluxoId = fluxo?.id,
            setorAtual = fluxo?.setorAtual?.nome,
            statusFluxo = fluxo?.statusAtual,
            recebidoPor = fluxo?.recebidoPor?.nome,
            podeReceber = podeReceber,
            podeEntregar = naFrotaRecebida && patioId != null,
            patioId = patioId,
            devolverPara = if (naFrotaRecebida) fluxo?.setorAnterior?.nome else null
        )
    }
}
