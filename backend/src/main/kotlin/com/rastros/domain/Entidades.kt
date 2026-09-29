package com.rastros.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "perfis")
class Perfil(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Int? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 50)
    var nome: PerfilNome,

    /** As secoes que o perfil traz prontas, editadas pelo Administrador (V17). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "perfil_secoes", joinColumns = [JoinColumn(name = "perfil_id")])
    @Enumerated(EnumType.STRING)
    @Column(name = "secao", length = 40)
    var secoes: MutableSet<SecaoDoSistema> = mutableSetOf()
) {
    /** O Administrador tem todas, sempre: nao da para ele se trancar para fora. */
    val secoesPadrao: Set<SecaoDoSistema>
        get() = if (nome == PerfilNome.ADMIN) SecaoDoSistema.entries.toSet() else SecaoDoSistema.completar(secoes)
}

@Entity
@Table(name = "setores")
class Setor(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Int? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 50)
    var nome: SetorNome,

    @Column(nullable = false)
    var ativo: Boolean = true
)

@Entity
@Table(name = "usuarios")
class Usuario(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Int? = null,

    @Column(nullable = false, length = 100)
    var nome: String,

    /** O que a pessoa digita para entrar: minusculas, sem espaco nem acento (V7). */
    @Column(nullable = false, unique = true, length = 50)
    var login: String,

    /** Contato, opcional. O login nao e mais pelo e-mail. */
    @Column(unique = true, length = 100)
    var email: String? = null,

    @Column(name = "senha_hash", nullable = false, length = 255)
    var senhaHash: String,

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "perfil_id", nullable = false)
    var perfil: Perfil,

    /** NULL para Vendedor / Diretoria / Admin. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "setor_id")
    var setor: Setor? = null,

    /**
     * Setores a mais, alem do principal (V20): quem e do Recorte e tambem da Frota, por
     * exemplo. Em "Meu setor" a pessoa escolhe em qual esta trabalhando.
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "usuario_setores",
        joinColumns = [JoinColumn(name = "usuario_id")],
        inverseJoinColumns = [JoinColumn(name = "setor_id")]
    )
    var outrosSetores: MutableSet<Setor> = mutableSetOf(),

    @Column(nullable = false)
    var ativo: Boolean = true,

    /** Senha provisoria: enquanto for verdadeiro, o usuario so consegue trocar a senha. */
    @Column(name = "trocar_senha", nullable = false)
    var trocarSenha: Boolean = false,

    /**
     * Vai no token de cada login. Trocar ou redefinir a senha incrementa: os tokens antigos
     * deixam de valer na hora, em qualquer aparelho (V15).
     */
    @Column(name = "versao_sessao", nullable = false)
    var versaoSessao: Int = 0,

    /**
     * O que foi mudado em cima das secoes que o perfil traz (V16): verdadeiro e uma secao a
     * mais, falso e uma secao tirada. Sem nada aqui, vale o padrao do perfil.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "usuario_secoes", joinColumns = [JoinColumn(name = "usuario_id")])
    @MapKeyEnumerated(EnumType.STRING)
    @MapKeyColumn(name = "secao", length = 40)
    @Column(name = "liberada", nullable = false)
    var ajustesDeSecao: MutableMap<SecaoDoSistema, Boolean> = mutableMapOf()
) {
    /** Todos os setores da pessoa: o principal primeiro, depois os outros. */
    val setores: List<Setor>
        get() = listOfNotNull(setor) + outrosSetores.filter { it.id != setor?.id }.sortedBy { it.nome.ordinal }

    /** As secoes que a pessoa acessa: as do perfil, mais as liberadas, menos as tiradas. */
    val secoes: Set<SecaoDoSistema>
        get() = SecaoDoSistema.completar(
            perfil.secoesPadrao + ajustesDeSecao.filterValues { it }.keys -
                ajustesDeSecao.filterValues { !it }.keys
        )

    /** Guarda so a diferenca para o padrao do perfil: trocar de perfil leva os ajustes junto. */
    fun definirSecoes(desejadas: Set<SecaoDoSistema>) {
        val padrao = perfil.secoesPadrao
        ajustesDeSecao.clear()
        (desejadas - padrao).forEach { ajustesDeSecao[it] = true }
        (padrao - desejadas).forEach { ajustesDeSecao[it] = false }
    }

    /** Derruba todas as sessoes abertas desta pessoa. */
    fun encerrarSessoes() {
        versaoSessao++
    }
}

@Entity
@Table(name = "ordens_servico", indexes = [Index(name = "idx_os_numero", columnList = "numero_os_erp")])
class OrdemServico(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Int? = null,

    /** Numero gerado externamente pelo ERP (o RastrOS nao emite OS). */
    @Column(name = "numero_os_erp", nullable = false, length = 50)
    var numeroOsErp: String,

    @Column(name = "cliente", length = 120)
    var cliente: String? = null,

    /** O que a OS manda fazer, como o ERP escreve. Vem da extensao; nas antigas e nulo. */
    @Column(name = "servico", length = 200)
    var servico: String? = null,

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "criado_por", nullable = false)
    var criadoPor: Usuario,

    @Column(name = "criado_em", nullable = false)
    var criadoEm: Instant = Instant.now(),

    @Column(nullable = false)
    var cancelada: Boolean = false,

    @Column(name = "motivo_cancelamento", columnDefinition = "TEXT")
    var motivoCancelamento: String? = null,

    @OneToMany(mappedBy = "ordemServico", cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
    var fluxos: MutableList<FluxoOs> = mutableListOf()
) {
    /**
     * O numero normalizado enquanto a OS vale; nulo depois de cancelada. E unico no banco
     * (V15): duas OS abertas com o mesmo numero nao entram nem se chegarem ao mesmo tempo,
     * e uma OS cancelada pode ser aberta de novo.
     */
    @Column(name = "numero_ativo", length = 50, unique = true)
    var numeroAtivo: String? = null
        protected set

    @PrePersist
    private fun aoCriar() {
        numeroAtivo = if (cancelada) null else normalizarNumeroOs(numeroOsErp)
    }

    /** O numero nunca muda e a OS nao volta de cancelada: basta soltar o numero ao cancelar. */
    @PreUpdate
    private fun aoAlterar() {
        if (cancelada) numeroAtivo = null
    }
}

fun normalizarNumeroOs(numero: String) = numero.trim().lowercase()

@Entity
@Table(name = "fluxos_os", indexes = [Index(name = "idx_fluxo_setor", columnList = "setor_atual_id,encerrado")])
class FluxoOs(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Int? = null,

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "os_id", nullable = false)
    var ordemServico: OrdemServico,

    /** Ex.: Lona, Adesivo, Frota. Fluxos paralelos sao 100% independentes (RN03). */
    @Column(name = "identificador_fluxo", nullable = false, length = 50)
    var identificadorFluxo: String = "Principal",

    @Enumerated(EnumType.STRING)
    @Column(name = "status_atual", nullable = false, length = 50)
    var statusAtual: StatusFluxo = StatusFluxo.AGUARDANDO_RECEBIMENTO,

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "setor_atual_id", nullable = false)
    var setorAtual: Setor,

    /** Setor que despachou o fluxo para o setor atual. Unico destino valido de retorno (RF03). */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "setor_anterior_id")
    var setorAnterior: Setor? = null,

    /** Primeiro usuario que recebeu o fluxo no setor atual (RN01). */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "recebido_por_id")
    var recebidoPor: Usuario? = null,

    @Column(name = "recebido_em")
    var recebidoEm: Instant? = null,

    @Column(name = "entrou_no_setor_em", nullable = false)
    var entrouNoSetorEm: Instant = Instant.now(),

    @Column(nullable = false)
    var encerrado: Boolean = false,

    @Column(name = "encerrado_em")
    var encerradoEm: Instant? = null,

    @Column(name = "motivo_cancelamento", columnDefinition = "TEXT")
    var motivoCancelamento: String? = null,

    /**
     * Trava otimista: duas pessoas movimentando o mesmo fluxo ao mesmo tempo, so a primeira
     * grava. A segunda recebe 409 e ve a OS como ficou (V15).
     */
    @Version
    @Column(nullable = false)
    var versao: Long = 0
)

@Entity
@Table(name = "eventos_movimentacao", indexes = [Index(name = "idx_eventos_fluxo", columnList = "fluxo_id,data_hora")])
class EventoMovimentacao(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "fluxo_id", nullable = false)
    var fluxo: FluxoOs,

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "setor_origem_id")
    var setorOrigem: Setor? = null,

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "setor_destino_id", nullable = false)
    var setorDestino: Setor,

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_evento", nullable = false, length = 30)
    var tipoEvento: TipoEvento,

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    var usuario: Usuario,

    @Column(name = "data_hora", nullable = false)
    var dataHora: Instant = Instant.now(),

    @Column(columnDefinition = "TEXT")
    var observacao: String? = null
)

/**
 * Matriz de transicao parametrizavel (docs/06). Uma linha por par origem -> destino permitido.
 * `origem` nulo representa a entrada comercial (Vendas), usada na criacao do fluxo.
 */
@Entity
@Table(name = "transicoes_permitidas")
class TransicaoPermitida(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Int? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "setor_origem", length = 50)
    var setorOrigem: SetorNome? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "setor_destino", nullable = false, length = 50)
    var setorDestino: SetorNome
)
