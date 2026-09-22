package com.ostracker.domain

import java.math.BigDecimal
import java.math.RoundingMode

/** Uma faixa de horario do dia do adesivador. */
data class FaixaHoraria(
    val indice: Int,
    val inicio: String,
    val fim: String,
    val horas: BigDecimal,
    val almoco: Boolean = false
) {
    val rotulo: String get() = "$inicio às $fim"
}

/**
 * Grade de horarios do dia. O almoco e uma faixa de verdade (ocupa espaco na grade e um
 * servico longo atravessa ela), mas nao conta como hora trabalhada.
 */
object FaixasDoDia {

    val TODAS: List<FaixaHoraria> = listOf(
        FaixaHoraria(1, "07:30", "09:00", BigDecimal("1.5")),
        FaixaHoraria(2, "09:00", "10:00", BigDecimal("1.0")),
        FaixaHoraria(3, "10:00", "11:00", BigDecimal("1.0")),
        FaixaHoraria(4, "11:00", "12:00", BigDecimal("1.0")),
        FaixaHoraria(5, "12:00", "13:30", BigDecimal("1.5"), almoco = true),
        FaixaHoraria(6, "13:30", "15:00", BigDecimal("1.5")),
        FaixaHoraria(7, "15:00", "16:00", BigDecimal("1.0")),
        FaixaHoraria(8, "16:00", "17:00", BigDecimal("1.0")),
        FaixaHoraria(9, "17:00", "18:00", BigDecimal("1.0"))
    )

    val QUANTIDADE = TODAS.size

    /** 9 horas de trabalho por dia, fora o almoco. */
    val HORAS_UTEIS_DO_DIA: BigDecimal =
        TODAS.filterNot { it.almoco }.fold(BigDecimal.ZERO) { soma, f -> soma + f.horas }

    fun de(indice: Int): FaixaHoraria = TODAS[(indice - 1).coerceIn(0, QUANTIDADE - 1)]

    fun ehAlmoco(indice: Int): Boolean = de(indice).almoco

    /** Faixas de trabalho, na ordem - as unicas onde um servico pode comecar. */
    val UTEIS: List<FaixaHoraria> get() = TODAS.filterNot { it.almoco }

    /** Faixa da regua continua (9 por dia) a que uma posicao corresponde. */
    fun faixaDaPosicao(posicao: Int): Int = (posicao % QUANTIDADE) + 1

    fun almocoNaPosicao(posicao: Int): Boolean = ehAlmoco(faixaDaPosicao(posicao))

    // ------------------------------------------------ sexta-feira ate as 17:00

    /** Na sexta a empresa fecha as 17:00: a ultima faixa (17:00-18:00) nao existe. */
    fun fechadaNoDia(dia: java.time.LocalDate, faixa: Int): Boolean =
        faixa == QUANTIDADE && dia.dayOfWeek == java.time.DayOfWeek.FRIDAY

    /** Horas de trabalho de um dia: 9, e 8 na sexta. A semana tem 44. */
    fun horasUteisNoDia(dia: java.time.LocalDate): BigDecimal =
        if (dia.dayOfWeek == java.time.DayOfWeek.FRIDAY) HORAS_UTEIS_DO_DIA - de(QUANTIDADE).horas else HORAS_UTEIS_DO_DIA

    /**
     * A posicao da regua (contada a partir de `base`) nao recebe servico: e o almoco, ou e
     * 17:00-18:00 de uma sexta. Um servico longo pula as duas, como pula a noite.
     */
    fun bloqueada(base: java.time.LocalDate, posicao: Int): Boolean {
        val faixa = faixaDaPosicao(posicao)
        if (ehAlmoco(faixa)) return true
        return faixa == QUANTIDADE && fechadaNoDia(diaUtilAFrente(base, posicao / QUANTIDADE), faixa)
    }

    /** Posicoes que um servico ocupa a partir do dia em que comeca, respeitando a sexta. */
    fun posicoesOcupadas(inicio: java.time.LocalDate, slotInicio: Int, horas: BigDecimal): List<Int> =
        posicoesAPartirDe(inicio, slotInicio - 1, horas)

    /** Idem, a partir de uma posicao da regua contada desde `base`. */
    fun posicoesAPartirDe(base: java.time.LocalDate, posicaoInicial: Int, horas: BigDecimal): List<Int> {
        val posicoes = mutableListOf<Int>()
        var restante = horas.max(BigDecimal("0.5"))
        var posicao = posicaoInicial
        var guarda = 0
        while (restante > BigDecimal.ZERO && guarda++ < QUANTIDADE * 60) {
            if (!bloqueada(base, posicao)) {
                posicoes += posicao
                restante -= de(faixaDaPosicao(posicao)).horas
            }
            posicao++
        }
        return posicoes
    }

    /**
     * A semana de uma data, cortada no mes dela (como as abas da planilha): de segunda (ou
     * do dia 1) ate sexta (ou o ultimo dia do mes). Sabado e domingo contam como a
     * segunda seguinte.
     */
    fun semanaNoMes(data: java.time.LocalDate): Pair<java.time.LocalDate, java.time.LocalDate> {
        var dia = data
        while (dia.dayOfWeek == java.time.DayOfWeek.SATURDAY || dia.dayOfWeek == java.time.DayOfWeek.SUNDAY) dia = dia.plusDays(1)
        val segunda = dia.with(java.time.DayOfWeek.MONDAY)
        val inicio = maxOf(segunda, dia.withDayOfMonth(1))
        val fim = minOf(segunda.plusDays(4), dia.with(java.time.temporal.TemporalAdjusters.lastDayOfMonth()))
        return inicio to fim
    }

    /** A primeira posicao a partir desta que pode receber inicio de servico. */
    fun livreAPartirDe(base: java.time.LocalDate, posicao: Int): Int {
        var p = posicao
        while (bloqueada(base, p)) p++
        return p
    }

    /** Fins de semana nao entram na agenda: avanca apenas por dias uteis. */
    fun diaUtilAFrente(inicio: java.time.LocalDate, quantidade: Int): java.time.LocalDate {
        var dia = inicio
        var restantes = quantidade
        while (restantes > 0) {
            dia = dia.plusDays(1)
            if (dia.dayOfWeek != java.time.DayOfWeek.SATURDAY && dia.dayOfWeek != java.time.DayOfWeek.SUNDAY) restantes--
        }
        return dia
    }

    // Regua antiga, sem a regra da sexta (so o almoco e pulado). Fica para as migracoes
    // V3 e V9, que precisam enxergar a agenda como ela era; o resto usa as versoes com data.

    /**
     * Posicoes que um servico ocupa numa regua continua de dias (9 faixas por dia), a
     * partir da faixa inicial do primeiro dia.
     *
     * O almoco **nunca entra**: ele e pulado, nao conta hora e nao e ocupado. Um servico
     * que atravessa o meio-dia aparece em dois pedacos, com a faixa do almoco livre.
     */
    fun posicoesOcupadas(slotInicio: Int, horas: BigDecimal): List<Int> =
        posicoesAPartirDe(slotInicio - 1, horas)

    /** Idem, a partir de uma posicao absoluta da regua. */
    fun posicoesAPartirDe(posicaoInicial: Int, horas: BigDecimal): List<Int> {
        val posicoes = mutableListOf<Int>()
        var restante = horas.max(BigDecimal("0.5"))
        var posicao = posicaoInicial
        var guarda = 0

        while (restante > BigDecimal.ZERO && guarda++ < QUANTIDADE * 60) {
            if (!almocoNaPosicao(posicao)) {
                posicoes += posicao
                restante -= de(faixaDaPosicao(posicao)).horas
            }
            posicao++
        }
        return posicoes
    }

    /** Horas somadas das faixas de trabalho de uma lista de posicoes. */
    fun horasDasPosicoes(posicoes: List<Int>): BigDecimal =
        posicoes.fold(BigDecimal.ZERO) { soma, p -> soma + de(faixaDaPosicao(p)).horas }
            .setScale(2, RoundingMode.HALF_UP)
}
