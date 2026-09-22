package com.ostracker.domain

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Tira as sobreposicoes que a importacao antiga deixou na agenda.
 *
 * A planilha tinha linhas genericas e a primeira conversao para horas arredondava para
 * cima, entao o ultimo servico do dia costuma "vazar" por cima do seguinte. A correcao
 * encurta as horas estimadas desse servico ate onde comeca o proximo - **sem mudar data,
 * faixa de inicio nem score**, que sao o que o historico da planilha registrou.
 *
 * So servicos importados (sem autor) sao encurtados: horas digitadas por alguem no
 * sistema sao decisao de gente, nao artefato de conversao.
 */
object NormalizacaoAgenda {

    data class Item(
        val id: Long,
        val adesivadorId: Int,
        val data: LocalDate,
        val slotInicio: Int,
        val horas: BigDecimal,
        val importado: Boolean
    )

    /** Novas horas para quem precisa encolher; quem ja cabe nao aparece no resultado. */
    fun horasSemSobreposicao(itens: List<Item>): Map<Long, BigDecimal> {
        val ajustes = mutableMapOf<Long, BigDecimal>()

        itens.groupBy { it.adesivadorId }.values.forEach { coluna ->
            val ordenados = coluna.sortedWith(compareBy({ inicio(it) }, { it.id }))
            ordenados.forEachIndexed { indice, item ->
                if (!item.importado) return@forEachIndexed
                val comeco = inicio(item)
                // O proximo que comeca depois deste. Dois que comecam na mesma faixa (o
                // excedente de um dia lotado) nao tem como ser separados so por horas.
                val proximo = ordenados.drop(indice + 1).firstOrNull { inicio(it) > comeco }
                    ?: return@forEachIndexed

                val relativas = FaixasDoDia.posicoesOcupadas(item.slotInicio, item.horas)
                val base = comeco - (item.slotInicio - 1)
                val cabem = relativas.filter { base + it < inicio(proximo) }
                if (cabem.isEmpty() || cabem.size == relativas.size) return@forEachIndexed

                ajustes[item.id] = FaixasDoDia.horasDasPosicoes(cabem)
            }
        }
        return ajustes
    }

    /** Posicao absoluta na regua de dias uteis (9 faixas por dia). */
    fun inicio(item: Item): Long = indiceDiaUtil(item.data) * FaixasDoDia.QUANTIDADE + (item.slotInicio - 1)

    /** Dias uteis desde uma segunda-feira fixa; sabado e domingo nao contam. */
    fun indiceDiaUtil(dia: LocalDate): Long {
        val dias = ChronoUnit.DAYS.between(SEGUNDA_DE_REFERENCIA, dia)
        val semanas = Math.floorDiv(dias, 7L)
        val resto = Math.floorMod(dias, 7L)
        return semanas * 5 + minOf(resto, 5L)
    }

    private val SEGUNDA_DE_REFERENCIA: LocalDate = LocalDate.of(2000, 1, 3).also {
        check(it.dayOfWeek == DayOfWeek.MONDAY)
    }
}
