package com.rastros.domain

import jakarta.persistence.*

/**
 * Um status da legenda da agenda: nome e cor editaveis na propria agenda.
 *
 * Os do sistema tem [chave] (o [StatusAgendamento] correspondente) e regra por tras:
 * executando e concluido marcam o horario real, o painel destaca o projeto atual etc. Esses
 * so mudam de nome e cor. Os criados na agenda nao tem chave: sao etiquetas de um servico
 * que ainda nao comecou ("Aguardando material", "Reagendar"...) e podem ser removidos.
 */
@Entity
@Table(name = "status_agenda")
class StatusAgenda(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(length = 20, unique = true)
    var chave: StatusAgendamento? = null,

    @Column(nullable = false, length = 40)
    var nome: String,

    /** Cor forte do status (#rrggbb): a tela clareia para o fundo do card. */
    @Column(nullable = false, length = 7)
    var cor: String,

    @Column(nullable = false)
    var ordem: Int
) {
    val doSistema: Boolean get() = chave != null
}

/**
 * Um vendedor da agenda. O [codigo] (a letra do cronograma: "(K)" e a Karina) e o que fica
 * gravado no card; o nome e so a exibicao. Removido com cards na agenda, fica inativo: os
 * cards antigos continuam mostrando o nome, mas ele nao aparece mais para escolher.
 */
@Entity
@Table(name = "vendedores_agenda")
class VendedorAgenda(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Int? = null,

    @Column(nullable = false, length = 5, unique = true)
    var codigo: String,

    @Column(nullable = false, length = 60)
    var nome: String,

    @Column(nullable = false)
    var ativo: Boolean = true
)
