package com.rastros.domain

/** Perfis de acesso descritos em docs/03-stakeholders-e-permissoes.md */
enum class PerfilNome {
    OPERACIONAL,
    VENDEDOR,
    DIRETORIA,
    ADMIN,
    /** Painel e cadastro de usuarios (inclusive redefinir senha); nao movimenta OS. */
    FINANCEIRO,
    /** Comeca sem nenhuma secao: so as marcadas no cadastro (ex.: uma TV da fabrica). */
    PERSONALIZADO
}

/**
 * As secoes do sistema que se liberam por pessoa. Cada perfil traz as suas prontas
 * ([padraoDo]); no cadastro, o administrador acrescenta ou tira secoes de cada um (um
 * vendedor que nao abre OS, alguem da Criacao que precisa ver a agenda...).
 *
 * Ficam de fora o "Meu setor" (vem do setor da pessoa), a "Minha agenda" (vem de ter coluna
 * na agenda) e o cadastro de usuarios e a administracao (so dos perfis Administrador e
 * Financeiro: liberar isso a qualquer um abriria a porta para dar a si mesmo tudo).
 */
enum class SecaoDoSistema {
    /** O painel operacional completo. */
    PAINEL,
    /** A agenda do dia de cada adesivador, em tela cheia (TV). */
    PAINEL_ADESIVADORES,
    /** As OS disponiveis para o Acabamento, em tela cheia (TV). */
    PAINEL_ACABAMENTO,
    /** Ver a agenda dos adesivadores. */
    AGENDA_VER,
    /** Mexer na agenda: criar, mover, mudar status, vendedor, OS... */
    AGENDA_EDITAR,
    /** Editar os adesivadores, os status e os vendedores da agenda. */
    AGENDA_LEGENDA,
    /** Patio e prateleira: mandar a OS para o Financeiro (RN05). */
    PATIO_PRATELEIRA,
    /** Consultar OS e fluxos de todos os setores. */
    CONSULTA,
    /** Abrir OS manual, acrescentar fluxo e cancelar. */
    CRIAR_OS,
    PRODUTIVIDADE,
    /** O relatorio dos adesivadores, com o lancamento do score. */
    RELATORIO,
    /** Botao direito no card do painel: iniciar, pausar, retomar e concluir por um adesivador. */
    PAINEL_ACOES;

    /** Secao de TV: a tela fica ligada o dia todo. */
    val deTela: Boolean get() = this == PAINEL_ADESIVADORES || this == PAINEL_ACABAMENTO

    companion object {
        /**
         * O que cada perfil traz de fabrica - o que cada um ja podia antes das secoes. Vale
         * para um banco novo; depois, quem manda e o perfil guardado (Administracao ->
         * Permissoes).
         */
        fun padraoDo(perfil: PerfilNome): Set<SecaoDoSistema> = when (perfil) {
            PerfilNome.ADMIN -> entries.toSet()
            // Agir pelo painel no lugar do adesivador e liberado a parte, por quem administra.
            PerfilNome.DIRETORIA -> entries.toSet() - PAINEL_ACOES
            PerfilNome.VENDEDOR -> setOf(
                PAINEL, PAINEL_ADESIVADORES, PAINEL_ACABAMENTO, AGENDA_VER, AGENDA_EDITAR, PATIO_PRATELEIRA, CONSULTA, CRIAR_OS
            )
            PerfilNome.FINANCEIRO -> setOf(PAINEL, PAINEL_ADESIVADORES, PAINEL_ACABAMENTO)
            PerfilNome.OPERACIONAL, PerfilNome.PERSONALIZADO -> emptySet()
        }

        /** Quem mexe na agenda ou na legenda dela tambem a ve. */
        fun completar(secoes: Set<SecaoDoSistema>): Set<SecaoDoSistema> =
            if (AGENDA_EDITAR in secoes || AGENDA_LEGENDA in secoes) secoes + AGENDA_VER else secoes
    }
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
