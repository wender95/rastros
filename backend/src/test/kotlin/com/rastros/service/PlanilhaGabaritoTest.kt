package com.rastros.service

import com.rastros.TesteIntegracao
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * A planilha como gabarito.
 *
 * Importa as abas reais de agosto e setembro de 2026 e confere, semana a semana e
 * adesivador a adesivador, a soma de score que o sistema calcula contra a linha
 * `TOTAL SCORE` que a propria planilha trazia. Se o importador ou o calculo de
 * produtividade mudarem de comportamento, e aqui que aparece.
 */
class PlanilhaGabaritoTest : TesteIntegracao() {

    @Autowired lateinit var importador: ImportadorAgendaService
    @Autowired lateinit var produtividade: ProdutividadeService

    @Test
    fun `o score de cada semana bate com o TOTAL SCORE da planilha`() {
        val abas = listOf("Agosto" to "agosto-2026.csv", "Setembro" to "setembro-2026.csv")
        abas.forEach { (aba, arquivo) -> importador.importar(aba, 2026, ler(arquivo)) }

        val conferidos = mutableListOf<String>()
        val soft = SoftAssertions()
        abas.forEach { (_, arquivo) ->
            totaisDaPlanilha(ler(arquivo)).forEach { total ->
                val calculado = produtividade.calcular(total.inicio, total.fim).adesivadores
                    .associate { it.adesivador to (it.scoreAgendado ?: BigDecimal.ZERO) }

                total.porAdesivador.forEach { (nome, esperado) ->
                    soft.assertThat(calculado[nome] ?: BigDecimal.ZERO)
                        .describedAs("$nome na semana de ${total.inicio}")
                        .isEqualByComparingTo(esperado)
                    conferidos += "$nome ${total.inicio}"
                }
            }
        }
        soft.assertAll()
        // Garante que o teste conferiu de verdade, e nao passou por nao achar nada.
        assertThat(conferidos).hasSizeGreaterThan(50)
    }

    // ---------------------------------------------------------- leitura da planilha

    private class TotalDaSemana(
        val inicio: LocalDate,
        val fim: LocalDate,
        val porAdesivador: Map<String, BigDecimal>
    )

    private fun ler(arquivo: String): String =
        javaClass.getResource("/planilha/$arquivo")!!.readText(Charsets.UTF_8)

    /**
     * Cada semana da aba comeca com "DIA dd/mm" e termina numa linha TOTAL SCORE, com o
     * valor na mesma coluna do nome do adesivador ("01 ANDRE", "02 BRUNO", ...).
     */
    private fun totaisDaPlanilha(csv: String): List<TotalDaSemana> {
        val totais = mutableListOf<TotalDaSemana>()
        var inicio: LocalDate? = null
        var nomes: Map<Int, String> = emptyMap()

        csv.lineSequence().map(::campos).forEach { linha ->
            val dia = linha.firstNotNullOfOrNull { Regex("""DIA\s+(\d{2})/(\d{2})""").find(it) }
            if (dia != null) {
                val (d, m) = dia.destructured
                inicio = LocalDate.of(2026, m.toInt(), d.toInt())
            }
            if (linha.any { Regex("""^\d{2} \S""").containsMatchIn(it.trim()) }) {
                nomes = linha.withIndex()
                    .filter { Regex("""^\d{2} \S""").containsMatchIn(it.value.trim()) }
                    .associate { it.index to it.value.trim().substringAfter(' ').trim() }
            }
            if (linha.firstOrNull()?.trim() == "TOTAL SCORE" && inicio != null) {
                val comeco = inicio!!
                val fim = minOf(
                    comeco.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY)),
                    comeco.with(TemporalAdjusters.lastDayOfMonth())
                )
                val valores = nomes.mapValues { (indice, _) ->
                    linha.getOrNull(indice)?.trim()?.replace(',', '.')?.toBigDecimalOrNull()
                        ?: BigDecimal.ZERO
                }
                totais += TotalDaSemana(comeco, fim, valores.mapKeys { nomes[it.key]!! })
            }
        }
        return totais
    }

    /** CSV do Google Sheets: todo campo entre aspas, aspas internas dobradas. */
    private fun campos(linha: String): List<String> {
        val campos = mutableListOf<String>()
        val atual = StringBuilder()
        var entreAspas = false
        var i = 0
        while (i < linha.length) {
            val c = linha[i]
            when {
                c == '"' && entreAspas && linha.getOrNull(i + 1) == '"' -> { atual.append('"'); i++ }
                c == '"' -> entreAspas = !entreAspas
                c == ',' && !entreAspas -> { campos += atual.toString(); atual.clear() }
                else -> atual.append(c)
            }
            i++
        }
        campos += atual.toString()
        return campos
    }
}
