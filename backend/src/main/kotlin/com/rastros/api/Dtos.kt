package com.rastros.api

import com.rastros.domain.*
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant

// ---------- Autenticacao ----------

data class LoginRequest(
    @field:NotBlank(message = "Informe o usuario") val usuario: String = "",
    @field:NotBlank(message = "Informe a senha") val senha: String = ""
)

/** Um setor da pessoa, para o cadastro e para escolher em Meu setor. */
data class SetorDoUsuarioResponse(val id: Int, val nome: SetorNome)

data class UsuarioResponse(
    val id: Int,
    val nome: String,
    val login: String,
    val email: String?,
    val perfil: PerfilNome,
    val setor: SetorNome?,
    val setorId: Int?,
    /** Todos os setores da pessoa: o principal primeiro, depois os outros. */
    val setores: List<SetorDoUsuarioResponse> = emptyList(),
    val ativo: Boolean,
    /** Senha provisoria: a tela de troca de senha aparece antes de qualquer outra. */
    val trocarSenha: Boolean = false,
    /** Tem uma coluna na agenda dos adesivadores: ve a aba "Minha agenda". */
    val temAgenda: Boolean = false,
    /** Nome do administrador que esta agindo como esta pessoa (so em teste). */
    val agindoPor: String? = null,
    /** As secoes que a pessoa acessa: as do perfil, com os ajustes do cadastro. */
    val secoes: List<com.rastros.domain.SecaoDoSistema> = emptyList(),
    /** As secoes que diferem do padrao do perfil: verdadeiro liberada, falso tirada. */
    val ajustesDeSecao: Map<com.rastros.domain.SecaoDoSistema, Boolean> = emptyMap()
)

data class TrocarSenhaRequest(
    @field:NotBlank(message = "Informe a senha atual") val senhaAtual: String = "",
    @field:NotBlank(message = "Informe a nova senha") val novaSenha: String = ""
)

data class LoginResponse(
    val token: String,
    val expiraEmSegundos: Long,
    val usuario: UsuarioResponse
)

// ---------- Setores ----------

data class SetorResponse(val id: Int, val nome: SetorNome, val ativo: Boolean, val localFisico: Boolean)

// ---------- Ordens de servico / fluxos ----------

data class NovoFluxoRequest(
    @field:NotBlank(message = "Informe o identificador do fluxo")
    @field:Size(max = 50)
    val identificador: String = "Principal",

    @field:NotNull(message = "Selecione o setor inicial")
    val setorInicialId: Int? = null,

    val observacao: String? = null
)

data class NovaOrdemRequest(
    @field:NotBlank(message = "Informe o numero da OS no ERP")
    @field:Size(max = 50)
    val numeroOsErp: String = "",

    @field:Size(max = 120)
    val cliente: String? = null,

    /** O que a OS manda fazer, como o ERP escreve. */
    @field:Size(max = 200)
    val servico: String? = null,

    @field:NotEmpty(message = "Crie ao menos um fluxo")
    @field:Valid
    val fluxos: List<NovoFluxoRequest> = emptyList()
)

data class AdicionarFluxoRequest(
    @field:Valid val fluxo: NovoFluxoRequest = NovoFluxoRequest()
)

data class DespacharRequest(
    @field:NotNull(message = "Selecione o setor de destino")
    val setorDestinoId: Int? = null,
    val observacao: String? = null
)

data class MotivoRequest(
    @field:NotBlank(message = "O motivo do cancelamento e obrigatorio")
    val motivo: String = ""
)

data class ReceberRequest(val observacao: String? = null)

data class EventoResponse(
    val id: Long,
    val tipoEvento: TipoEvento,
    val setorOrigem: SetorNome?,
    val setorDestino: SetorNome,
    val usuario: String,
    val perfilUsuario: PerfilNome,
    val dataHora: Instant,
    val observacao: String?
)

data class DestinoPermitidoResponse(
    val setorId: Int,
    val setor: SetorNome,
    val retorno: Boolean
)

data class FluxoResponse(
    val id: Int,
    val osId: Int,
    val numeroOsErp: String,
    val cliente: String?,
    val servico: String?,
    val identificadorFluxo: String,
    val status: StatusFluxo,
    val setorAtualId: Int,
    val setorAtual: SetorNome,
    val setorAnterior: SetorNome?,
    val recebidoPor: String?,
    val recebidoEm: Instant?,
    val entrouNoSetorEm: Instant,
    val encerrado: Boolean,
    val encerradoEm: Instant?,
    val motivoCancelamento: String?,
    val criadoEm: Instant,
    val criadoPor: String,
    /** Segundos de horario comercial desde a entrada no setor atual - o "parado ha" das telas. */
    val segundosNoSetor: Long
)

data class FluxoDetalheResponse(
    val fluxo: FluxoResponse,
    val eventos: List<EventoResponse>,
    val acoes: AcoesDisponiveis
)

data class AcoesDisponiveis(
    val podeReceber: Boolean,
    val podeDespachar: Boolean,
    val podeCancelar: Boolean,
    val destinos: List<DestinoPermitidoResponse>
)

data class OrdemResponse(
    val id: Int,
    val numeroOsErp: String,
    val cliente: String?,
    val servico: String?,
    val criadoPor: String,
    val criadoEm: Instant,
    val cancelada: Boolean,
    val motivoCancelamento: String?,
    val fluxos: List<FluxoResponse>
)

// ---------- Painel ----------

data class ContagemSetor(
    val setor: SetorNome,
    /** Agora: esperando ser recebidas no setor. */
    val aguardando: Int,
    /** Agora: recebidas e em trabalho no setor. */
    val emProcessamento: Int,
    /** No periodo: OS que sairam do setor (despachadas, devolvidas, entregues ou concluidas). */
    val saidasNoPeriodo: Int = 0
)

/**
 * O painel tem duas partes: a fotografia de agora (ativos, na fila, em curso) e o que
 * aconteceu no periodo escolhido - dia, semana, mes ou ano.
 */
data class PainelResponse(
    val fluxosAtivos: Int,
    val aguardandoRecebimento: Int,
    val emProcessamento: Int,
    val inicio: java.time.LocalDate,
    val fim: java.time.LocalDate,
    val osAbertas: Int,
    val concluidas: Int,
    val canceladas: Int,
    val porSetor: List<ContagemSetor>
)

// ---------- Admin ----------

data class UsuarioRequest(
    @field:NotBlank(message = "Informe o nome") val nome: String = "",
    @field:NotBlank(message = "Informe o usuario")
    @field:Pattern(
        regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{2,29}$",
        message = "Usuario: de 3 a 30 letras ou numeros (pode ter ponto, hifen e sublinhado), sem espaco nem acento"
    )
    val login: String = "",
    @field:Email(message = "E-mail invalido") val email: String? = null,
    val senha: String? = null,
    @field:NotNull(message = "Selecione o perfil") val perfil: PerfilNome? = null,
    val setorId: Int? = null,
    /** Setores a mais, alem do principal (so Operacional). Nulo mantem os que ja havia. */
    val outrosSetoresIds: Set<Int>? = null,
    val ativo: Boolean = true,
    /** As secoes que a pessoa acessa; nulo mantem o que ja havia (o padrao do perfil). */
    val secoes: Set<com.rastros.domain.SecaoDoSistema>? = null
)

data class TransicaoRequest(
    val setorOrigem: SetorNome? = null,
    @field:NotNull(message = "Informe o setor de destino") val setorDestino: SetorNome? = null
)

data class TransicaoResponse(val id: Int, val setorOrigem: SetorNome?, val setorDestino: SetorNome)

data class SetorRequest(
    @field:NotNull val nome: SetorNome? = null,
    val ativo: Boolean = true
)

data class ErroResponse(val erro: String, val detalhes: Map<String, String>? = null)

// ---------- Movimentacao (tela do setor) ----------

/** Uma OS na tela do setor: so o que o operador precisa para receber, devolver ou despachar. */
data class FeriadoRequest(
    @field:jakarta.validation.constraints.NotNull(message = "Informe a data") val data: java.time.LocalDate? = null,
    @field:jakarta.validation.constraints.NotBlank(message = "Informe o nome do feriado") val descricao: String = ""
)

data class ItemMovimentacaoResponse(
    val fluxoId: Int,
    val numeroOsErp: String,
    val cliente: String?,
    /** Nome do fluxo (Lona, Adesivo...), quando a OS tem mais de um. */
    val identificador: String,
    val veioDe: SetorNome?,
    /** Desde quando esta esperando (para receber) ou no setor (recebida). */
    val desde: java.time.Instant,
    /** Tempo de desde ate agora, contando so o horario comercial. */
    val segundosDesde: Long = 0,
    val recebidoPor: String?,
    /** Para onde pode ser despachada, pela matriz de transicao. */
    val destinos: List<DestinoPermitidoResponse>,
    /** Para onde o botao Devolver manda, ou nulo quando nao ha setor anterior. */
    val devolverPara: SetorNome?,
    /** So no Financeiro: a OS recebida pode ser concluida. */
    val podeConcluir: Boolean = false
)

data class MovimentacaoResponse(
    val setor: SetorNome,
    val paraReceber: List<ItemMovimentacaoResponse>,
    val noSetor: List<ItemMovimentacaoResponse>
)

/** A OS que ja foi enviada ao sistema: a extensao do ERP mostra isto em vez de enviar de novo. */
data class OsJaEnviadaResponse(
    val osId: Int,
    val numeroOsErp: String,
    val cliente: String?,
    val enviadaEm: java.time.Instant,
    val enviadaPor: String,
    val fluxos: List<String>
)
