package com.rastros.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Colunas da agenda dos adesivadores, na mesma ordem em que aparecem no cronograma:
 * uma coluna por adesivador, mais as colunas fixas de Encaixe e Noturno.
 */
enum class TipoColunaAgenda {
    ADESIVADOR,
    ENCAIXE,
    NOTURNO
}

/** Legenda de cores do cronograma. */
/**
 * O que a faixa representa. Um bloco INDISPONIVEL nao e servico: e falta, ferias ou
 * feriado. Ele ocupa a agenda do adesivador e empurra os servicos, mas nao tem OS,
 * vendedor nem andamento.
 */
enum class TipoAgendamento {
    SERVICO,
    INDISPONIVEL
}

enum class StatusAgendamento {
    PROGRAMADO,
    EM_PATIO,
    EXECUTANDO,
    CONCLUIDO,
    NAO_VEIO,
    EXTERNO
}

@Entity
@Table(name = "adesivadores")
class Adesivador(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Int? = null,

    @Column(nullable = false, length = 60)
    var nome: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var tipo: TipoColunaAgenda = TipoColunaAgenda.ADESIVADOR,

    /** Posicao da coluna na grade (01 ANDRE, 02 BRUNO, ...). */
    @Column(nullable = false)
    var ordem: Int,

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "usuario_id")
    var usuario: Usuario? = null,

    @Column(nullable = false)
    var ativo: Boolean = true
)

/**
 * Um carro/servico agendado para um adesivador em um dia.
 *
 * O dia tem 5 faixas de trabalho. Um servico que ocupa varias faixas e um unico
 * agendamento com `duracaoSlots` maior que 1, e um projeto longo pode passar do fim
 * do dia e continuar nos dias seguintes.
 */
@Entity
@Table(
    name = "agendamentos",
    indexes = [
        Index(name = "idx_agendamento_data", columnList = "data,adesivador_id,slot_inicio"),
        Index(name = "idx_agendamento_os", columnList = "os_id")
    ]
)
class Agendamento(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false)
    var data: LocalDate,

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "adesivador_id", nullable = false)
    var adesivador: Adesivador,

    /** Faixa de horario em que o servico comeca (1 a 9 - ver FaixasDoDia). */
    @Column(name = "slot_inicio", nullable = false)
    var slotInicio: Int,

    /** Coluna da versao antiga (faixas genericas); so alimenta a migracao. */
    @Column(name = "duracao_slots", nullable = false)
    var duracaoSlots: Int = 1,

    /** Horas estimadas de trabalho. Define quantas faixas de horario o servico ocupa. */
    @Column(name = "horas_estimadas", precision = 5, scale = 2)
    var horasEstimadas: java.math.BigDecimal? = null,

    /** Nulo nos registros anteriores a esta coluna; a migracao preenche como SERVICO. */
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", length = 20)
    var tipo: TipoAgendamento? = null,

    @Column(nullable = false, length = 200)
    var descricao: String,

    /** Inicial do vendedor responsavel, como aparece entre parenteses no cronograma. */
    @Column(name = "vendedor_codigo", length = 5)
    var vendedorCodigo: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: StatusAgendamento = StatusAgendamento.PROGRAMADO,

    /** Pontuacao de carga do servico, somada no TOTAL SCORE da semana. */
    @Column(precision = 6, scale = 2)
    var score: BigDecimal? = null,

    /** OS do ERP que produz o material deste carro. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "os_id")
    var ordemServico: OrdemServico? = null,

    @Column(columnDefinition = "TEXT")
    var observacao: String? = null,

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "criado_por")
    var criadoPor: Usuario? = null,

    @Column(name = "criado_em", nullable = false)
    var criadoEm: Instant = Instant.now(),

    @Column(name = "atualizado_em")
    var atualizadoEm: Instant? = null,

    /**
     * Partes do mesmo servico: aponta para a parte mais antiga do conjunto. Nulo quando o
     * servico e de uma parte so. As partes dividem nome, vendedor, OS, observacao e estado;
     * cada uma tem o seu lugar e o seu tamanho na grade.
     */
    @Column(name = "grupo_id")
    var grupoId: Long? = null,

    /** Quando o adesivador marcou "executando" - e a entrada real no servico. */
    @Column(name = "iniciado_em")
    var iniciadoEm: Instant? = null,

    /** Quando marcou "concluido" - a saida real. Com os dois, sai a hora medida. */
    @Column(name = "concluido_em")
    var concluidoEm: Instant? = null,

    /**
     * Quem iniciou o projeto pela propria agenda. Nulo quando o estado foi mudado no
     * escritorio: so o projeto iniciado pelo adesivador move a OS dentro da Frota.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "iniciado_por")
    var iniciadoPor: Usuario? = null,

    /** Quem registrou o inicio: o adesivador, ou quem agiu por ele pelo painel (V19). */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "inicio_registrado_por")
    var inicioRegistradoPor: Usuario? = null,

    /** Quem registrou a conclusao. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "conclusao_registrada_por")
    var conclusaoRegistradaPor: Usuario? = null,

    /**
     * Status criado na agenda (ver [StatusAgenda]) que o card mostra agora. Vale so com o
     * card ainda nao comecado: qualquer status do sistema (executando, concluido...) o tira.
     */
    @Column(name = "etiqueta_id")
    var etiquetaId: Long? = null,

    /**
     * Coluna Noturno: os adesivadores que vao fazer o servico a noite. As partes do mesmo
     * servico dividem a lista.
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @org.hibernate.annotations.BatchSize(size = 200)
    @JoinTable(
        name = "agendamento_atribuidos",
        joinColumns = [JoinColumn(name = "agendamento_id")],
        inverseJoinColumns = [JoinColumn(name = "adesivador_id")]
    )
    var atribuidos: MutableSet<Adesivador> = mutableSetOf()
) {
    /** Horas do servico, caindo para a conversao da grade antiga enquanto houver dado velho. */
    val horas: java.math.BigDecimal
        get() = horasEstimadas ?: converterFaixasAntigas(duracaoSlots)

    val ehServico: Boolean
        get() = (tipo ?: TipoAgendamento.SERVICO) == TipoAgendamento.SERVICO

    /**
     * Posicoes que o servico ocupa a partir da sua faixa inicial, numa regua continua de
     * 9 faixas por dia. O almoco e 17:00-18:00 da sexta nunca aparecem aqui.
     */
    val posicoesOcupadas: List<Int>
        get() = FaixasDoDia.posicoesOcupadas(data, slotInicio, horas)

    /** Quantas faixas de horario o servico cobre. */
    val faixasOcupadas: Int
        get() = posicoesOcupadas.size

    /** Ultimo dia util em que o servico ainda ocupa alguma faixa (passa do fim do dia). */
    val ultimoDia: LocalDate
        get() = FaixasDoDia.diaUtilAFrente(data, posicoesOcupadas.last() / FaixasDoDia.QUANTIDADE)

    companion object {

        /** A grade antiga tinha 5 faixas genericas por dia; o dia tem 9 horas uteis. */
        fun converterFaixasAntigas(faixas: Int): java.math.BigDecimal {
            val bruto = FaixasDoDia.HORAS_UTEIS_DO_DIA
                .multiply(java.math.BigDecimal(faixas))
                .divide(java.math.BigDecimal(5), 2, java.math.RoundingMode.HALF_UP)
            // Arredonda para a meia hora mais proxima, minimo de 1 hora.
            val meias = bruto.multiply(java.math.BigDecimal(2))
                .setScale(0, java.math.RoundingMode.HALF_UP)
            return meias.divide(java.math.BigDecimal(2)).max(java.math.BigDecimal("1.0"))
        }
    }
}

/** Um passo da pilha de desfazer: o trecho da agenda antes de uma alteracao. */
@Entity
@Table(name = "historico_agenda")
class RetratoAgenda(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "usuario_id", nullable = false)
    var usuarioId: Int,

    @Column(nullable = false, length = 250)
    var descricao: String,

    /** Colunas da grade retratadas, separadas por virgula. */
    @Column(name = "adesivador_ids", nullable = false, length = 200)
    var adesivadorIds: String,

    @Column(nullable = false)
    var inicio: LocalDate,

    @Column(nullable = false)
    var fim: LocalDate,

    /** As linhas do retrato, em JSON. */
    @Column(nullable = false, columnDefinition = "TEXT")
    var linhas: String,

    @Column(name = "criado_em", nullable = false)
    var criadoEm: Instant = Instant.now()
)

/**
 * Uma pausa de um projeto: o adesivador parou o servico (material, almoco fora de hora,
 * outra urgencia...) e depois retomou. A pausa e do servico inteiro: [servicoId] e a chave
 * do conjunto de partes (o grupo das copias, ou o proprio card).
 */
@Entity
@Table(name = "pausas_projeto")
class PausaProjeto(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "servico_id", nullable = false)
    var servicoId: Long,

    @Column(nullable = false)
    var inicio: Instant,

    /** Nulo enquanto o servico esta pausado. */
    var fim: Instant? = null,

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "pausado_por")
    var pausadoPor: Usuario? = null,

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "retomado_por")
    var retomadoPor: Usuario? = null
)
