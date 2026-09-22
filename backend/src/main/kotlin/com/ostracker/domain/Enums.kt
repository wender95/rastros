package com.ostracker.domain

/** Perfis de acesso descritos em docs/03-stakeholders-e-permissoes.md */
enum class PerfilNome {
    OPERACIONAL,
    VENDEDOR,
    DIRETORIA,
    ADMIN,
    /** Painel e cadastro de usuarios (inclusive redefinir senha); nao movimenta OS. */
    FINANCEIRO
}

/** Setores do fluxo produtivo. PRATELEIRA e PATIO sao locais fisicos, nao possuem operadores. */
enum class SetorNome {
    CRIACAO,
    IMPRESSAO,
    RECORTE,
    PREPARACAO,
    ACABAMENTO,
    FROTA,
    PRATELEIRA,
    PATIO,
    FINANCEIRO;

    /** Locais fisicos intermediarios: a saida deles e restrita a Vendedor/Diretoria (RN05). */
    val localFisico: Boolean get() = this == PRATELEIRA || this == PATIO

    /** O material ja esta pronto: esperando retirada (Prateleira/Patio) ou no Financeiro. */
    val saiuDaProducao: Boolean get() = localFisico || this == FINANCEIRO
}

enum class StatusFluxo {
    AGUARDANDO_RECEBIMENTO,
    EM_PROCESSAMENTO,
    ENCERRADA,
    CANCELADA
}

enum class TipoEvento {
    CRIACAO,
    RECEBIMENTO,
    DESPACHO,
    RETORNO,
    CANCELAMENTO,
    ENTREGA,
    /** O Financeiro conclui o fluxo - o unico jeito de uma OS terminar dentro do sistema. */
    CONCLUSAO
}
