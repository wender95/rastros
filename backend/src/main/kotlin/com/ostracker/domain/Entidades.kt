package com.ostracker.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "perfis")
class Perfil(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Int? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 50)
    var nome: PerfilNome
)

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

    @Column(nullable = false)
    var ativo: Boolean = true,

    /** Senha provisoria: enquanto for verdadeiro, o usuario so consegue trocar a senha. */
    @Column(name = "trocar_senha", nullable = false)
    var trocarSenha: Boolean = false
)

@Entity
@Table(name = "ordens_servico", indexes = [Index(name = "idx_os_numero", columnList = "numero_os_erp")])
class OrdemServico(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Int? = null,

    /** Numero gerado externamente pelo ERP (o OS Tracker nao emite OS). */
    @Column(name = "numero_os_erp", nullable = false, length = 50)
    var numeroOsErp: String,

    @Column(name = "cliente", length = 120)
    var cliente: String? = null,

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
)

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
    var motivoCancelamento: String? = null
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
