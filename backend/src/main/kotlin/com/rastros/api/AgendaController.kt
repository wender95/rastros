package com.rastros.api

import com.rastros.security.UsuarioAutenticado
import com.rastros.service.AgendaService
import com.rastros.service.ColunasAgendaService
import com.rastros.service.RemocaoResponse
import com.rastros.service.ImportadorAgendaService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

@RestController
@RequestMapping("/api/agenda")
class AgendaController(
    private val agendaService: AgendaService,
    private val colunas: ColunasAgendaService
) {

    /** Todas as colunas, inclusive as removidas (ativo = false), na ordem da grade. */
    @GetMapping("/colunas")
    fun colunas(): List<AdesivadorResponse> = agendaService.colunas()

    // Modo edicao dos adesivadores: so Diretoria e Administrador (SecurityConfig e servico).

    @PostMapping("/colunas")
    @ResponseStatus(HttpStatus.CREATED)
    fun adicionarColuna(
        @Valid @RequestBody req: NomeColunaRequest,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): AdesivadorResponse = colunas.adicionar(req.nome, autor)

    @PutMapping("/colunas/{id}")
    fun renomearColuna(
        @PathVariable id: Int,
        @Valid @RequestBody req: NomeColunaRequest,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): AdesivadorResponse = colunas.renomear(id, req.nome, autor)

    /** Ids das colunas da agenda na nova ordem, da esquerda para a direita. */
    @PutMapping("/colunas/ordem")
    fun reordenarColunas(
        @RequestBody ids: List<Int>,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): List<AdesivadorResponse> = colunas.reordenar(ids, autor)

    @DeleteMapping("/colunas/{id}")
    fun removerColuna(@PathVariable id: Int, @AuthenticationPrincipal autor: UsuarioAutenticado): RemocaoResponse =
        colunas.remover(id, autor)

    @PostMapping("/colunas/{id}/restaurar")
    fun restaurarColuna(@PathVariable id: Int, @AuthenticationPrincipal autor: UsuarioAutenticado): AdesivadorResponse =
        colunas.restaurar(id, autor)

    /** Bloco semanal contendo a data informada (padrao: semana de hoje). */
    @GetMapping("/semana")
    fun semana(
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        data: LocalDate?,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): SemanaAgendaResponse = agendaService.semana(data ?: LocalDate.now(), autor)

    /** Desfaz a ultima alteracao de agenda feita por este usuario nesta sessao. */
    @PostMapping("/desfazer")
    fun desfazer(@AuthenticationPrincipal autor: UsuarioAutenticado): Map<String, String> =
        mapOf("desfeito" to agendaService.desfazer(autor))

    @GetMapping("/busca")
    fun buscar(
        @RequestParam termo: String,
        @RequestParam(required = false, defaultValue = "false") somenteSemOs: Boolean
    ): List<AgendamentoResponse> = agendaService.buscar(termo, somenteSemOs)

    @GetMapping("/por-os/{osId}")
    fun porOrdem(@PathVariable osId: Int): List<AgendamentoResponse> =
        agendaService.porOrdemServico(osId)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun criar(
        @Valid @RequestBody req: NovoAgendamentoRequest,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): AgendamentoResponse = agendaService.criar(req, autor)

    @PutMapping("/{id}")
    fun atualizar(
        @PathVariable id: Long,
        @Valid @RequestBody req: NovoAgendamentoRequest,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): AgendamentoResponse = agendaService.atualizar(id, req, autor)

    /** Arrastar na grade: move para a faixa de destino ou troca de lugar com quem estiver lá. */
    /**
     * Mais uma parte do mesmo servico, noutro lugar da grade: e o "colar" da agenda
     * simplificada. Nao e uma copia — as partes dividem nome, vendedor, OS e estado.
     */
    @PostMapping("/{id}/partes")
    @ResponseStatus(HttpStatus.CREATED)
    fun dividir(
        @PathVariable id: Long,
        @Valid @RequestBody req: NovaParteRequest,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): AgendamentoResponse = agendaService.dividirEmParte(id, req, autor)

    @PatchMapping("/{id}/mover")
    fun mover(
        @PathVariable id: Long,
        @Valid @RequestBody req: MoverAgendamentoRequest,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): SemanaAgendaResponse = agendaService.mover(id, req, autor)

    /** Redimensionar arrastando a borda de baixo do card. */
    @PatchMapping("/{id}/horas")
    fun alterarHoras(
        @PathVariable id: Long,
        @Valid @RequestBody req: HorasAgendamentoRequest,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): SemanaAgendaResponse = agendaService.alterarHoras(id, req.horasEstimadas!!, autor)

    /** Coluna Noturno: os adesivadores que vao fazer o servico (lista vazia tira todos). */
    @PutMapping("/{id}/atribuidos")
    fun atribuir(
        @PathVariable id: Long,
        @RequestBody req: AtribuidosRequest,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): AgendamentoResponse = agendaService.atribuir(id, req.adesivadorIds, autor)

    @PatchMapping("/{id}/status")
    fun alterarStatus(
        @PathVariable id: Long,
        @Valid @RequestBody req: StatusAgendamentoRequest,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): AgendamentoResponse {
        if (req.status == null && req.etiquetaId == null) throw com.rastros.service.RegraDeNegocioException("Informe o status.")
        return agendaService.alterarStatus(id, req.status ?: com.rastros.domain.StatusAgendamento.PROGRAMADO, autor, req.etiquetaId)
    }

    /** Liga o carro da agenda a uma OS do ERP; corpo vazio desfaz o vinculo. */
    @PatchMapping("/{id}/os")
    fun vincularOs(
        @PathVariable id: Long,
        @RequestBody req: VinculoOsRequest,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): AgendamentoResponse = agendaService.vincularOs(id, req, autor)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun excluir(@PathVariable id: Long, @AuthenticationPrincipal autor: UsuarioAutenticado) =
        agendaService.excluir(id, autor)

    /** Varios cards de uma vez: muda o estado de todos, com um desfazer so. */
    @PostMapping("/lote/status")
    fun alterarStatusDeVarios(
        @Valid @RequestBody req: LoteAgendaRequest,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): LoteAgendaResponse {
        if (req.status == null && req.etiquetaId == null) throw com.rastros.service.RegraDeNegocioException("Informe o status.")
        val status = req.status ?: com.rastros.domain.StatusAgendamento.PROGRAMADO
        return LoteAgendaResponse(agendaService.alterarStatusDeVarios(req.ids!!, status, autor, req.etiquetaId))
    }

    /** A alca do canto: replica o card nos espacos de destino, com um desfazer so. */
    @PostMapping("/{id}/replicas")
    fun replicar(
        @PathVariable id: Long,
        @Valid @RequestBody req: ReplicarRequest,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): LoteAgendaResponse = LoteAgendaResponse(agendaService.replicar(id, req.destinos!!, autor))

    /** O N sobre varios espacos: cada um vira um bloco INDISPONIVEL, com um desfazer so. */
    @PostMapping("/indisponiveis")
    fun marcarIndisponivel(
        @Valid @RequestBody req: ReplicarRequest,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): LoteAgendaResponse = LoteAgendaResponse(agendaService.marcarIndisponivel(req.destinos!!, autor))

    /** Varios cards arrastados juntos; se algum cair em cima de outro card, nada se move. */
    @PostMapping("/lote/mover")
    fun moverVarios(
        @Valid @RequestBody req: MoverLoteRequest,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): LoteAgendaResponse = LoteAgendaResponse(agendaService.moverVarios(req.itens!!, autor))

    /** Varios cards de uma vez: exclui todos, e um Ctrl+Z traz todos de volta. */
    @PostMapping("/lote/excluir")
    fun excluirVarios(
        @Valid @RequestBody req: LoteAgendaRequest,
        @AuthenticationPrincipal autor: UsuarioAutenticado
    ): LoteAgendaResponse = LoteAgendaResponse(agendaService.excluirVarios(req.ids!!, autor))
}

data class NomeColunaRequest(
    @field:NotBlank(message = "Informe o nome") val nome: String = ""
)

data class ImportacaoAgendaRequest(
    @field:NotBlank(message = "Informe o nome da aba") val aba: String = "",
    val ano: Int = LocalDate.now().year,
    /** So importa deste dia em diante (opcional). */
    val aPartirDe: LocalDate? = null,
    @field:NotBlank(message = "Envie o conteudo CSV da aba") val csv: String = ""
)

@RestController
@RequestMapping("/api/admin/agenda")
class ImportacaoAgendaController(private val importador: ImportadorAgendaService) {

    /** Importa uma aba mensal do cronograma exportada em CSV, so em periodo vazio. */
    @PostMapping("/importar")
    fun importar(@Valid @RequestBody req: ImportacaoAgendaRequest): ResultadoImportacaoResponse =
        importador.importar(req.aba.trim(), req.ano, req.csv, req.aPartirDe)

    /**
     * Grava so os dias que nunca chegaram ao sistema e lista as diferencas de score nos
     * demais, sem alterar nada que ja existe.
     */
    @PostMapping("/completar")
    fun completar(@Valid @RequestBody req: ImportacaoAgendaRequest): ResultadoImportacaoResponse =
        importador.completarDiasAusentes(req.aba.trim(), req.ano, req.csv)
}
