package com.rastros.service

import com.rastros.domain.FaixasDoDia
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Os dias da agenda: segunda a sexta. Fim de semana nao entra na conta.
 *
 * Feriado conta como dia util aqui, de proposito: a agenda mostra o feriado e deixa
 * marcar servico nele (ha quem trabalhe). Quem desconta feriado e o HorarioComercial,
 * que mede tempo de trabalho para a produtividade.
 */
internal object DiasUteisDaAgenda {

    fun ehDiaUtil(dia: LocalDate) =
        dia.dayOfWeek != DayOfWeek.SATURDAY && dia.dayOfWeek != DayOfWeek.SUNDAY

    /** `quantidade` dias uteis, comecando em `inicio` (inclusive, se for dia util). */
    fun aPartirDe(inicio: LocalDate, quantidade: Int): List<LocalDate> {
        val dias = mutableListOf<LocalDate>()
        var dia = inicio
        while (dias.size < quantidade) {
            if (ehDiaUtil(dia)) dias += dia
            dia = dia.plusDays(1)
        }
        return dias
    }

    /** Os `quantidade` dias uteis imediatamente antes de `inicio`, do mais antigo ao mais novo. */
    fun antesDe(inicio: LocalDate, quantidade: Int): List<LocalDate> {
        val dias = ArrayDeque<LocalDate>()
        var dia = inicio.minusDays(1)
        while (dias.size < quantidade) {
            if (ehDiaUtil(dia)) dias.addFirst(dia)
            dia = dia.minusDays(1)
        }
        return dias.toList()
    }

    /** Dias uteis de um dia ate outro (0 no mesmo dia), como a regua da agenda conta. */
    fun entre(de: LocalDate, ate: LocalDate): Int {
        var dias = 0
        var dia = de
        while (dia.isBefore(ate)) {
            dia = FaixasDoDia.diaUtilAFrente(dia, 1)
            dias++
        }
        return dias
    }
}
