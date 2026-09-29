package com.rastros.domain

import jakarta.persistence.*
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Dia em que a empresa nao abre. */
@Entity
@Table(name = "feriados")
class Feriado(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Int? = null,

    @Column(nullable = false, unique = true)
    var data: LocalDate,

    @Column(nullable = false, length = 80)
    var descricao: String
)

/**
 * O relogio das OS: so conta o tempo em que a empresa esta aberta - segunda a sexta,
 * das 07:30 as 12:00 e das 13:30 as 18:00 (na sexta ate as 17:00), como a agenda, fora os
 * feriados cadastrados. Uma OS que chega sexta as 17:00 e e recebida segunda as 08:00
 * esperou 1h30 (1h na sexta e 30min na segunda), e nao 63 horas.
 *
 * Vale para todos os tempos de OS: espera e processamento na produtividade e o "parado
 * ha" das telas. Os feriados ficam aqui em memoria; o FeriadoService os recarrega a cada
 * mudanca.
 */
object HorarioComercial {

    /** Os dois turnos do dia; o almoco fica de fora. */
    val TURNOS: List<Pair<LocalTime, LocalTime>> = listOf(
        LocalTime.of(7, 30) to LocalTime.of(12, 0),
        LocalTime.of(13, 30) to LocalTime.of(18, 0)
    )

    /** Na sexta a empresa fecha as 17:00. */
    val TURNOS_DA_SEXTA: List<Pair<LocalTime, LocalTime>> = listOf(
        LocalTime.of(7, 30) to LocalTime.of(12, 0),
        LocalTime.of(13, 30) to LocalTime.of(17, 0)
    )

    @Volatile
    var feriados: Set<LocalDate> = emptySet()

    var zona: ZoneId = ZoneId.systemDefault()

    fun ehDiaUtil(dia: LocalDate, feriados: Set<LocalDate> = this.feriados) =
        dia.dayOfWeek != DayOfWeek.SATURDAY && dia.dayOfWeek != DayOfWeek.SUNDAY && dia !in feriados

    /** Tempo com a empresa aberta entre dois instantes (zero se `ate` nao vem depois). */
    fun entre(de: Instant, ate: Instant, feriados: Set<LocalDate> = this.feriados): Duration {
        if (!ate.isAfter(de)) return Duration.ZERO
        val inicio = LocalDateTime.ofInstant(de, zona)
        val fim = LocalDateTime.ofInstant(ate, zona)
        var segundos = 0L
        var dia = inicio.toLocalDate()
        while (!dia.isAfter(fim.toLocalDate())) {
            if (ehDiaUtil(dia, feriados)) {
                for ((abre, fecha) in if (dia.dayOfWeek == DayOfWeek.FRIDAY) TURNOS_DA_SEXTA else TURNOS) {
                    val a = maxOf(inicio, dia.atTime(abre))
                    val b = minOf(fim, dia.atTime(fecha))
                    if (b.isAfter(a)) segundos += Duration.between(a, b).seconds
                }
            }
            dia = dia.plusDays(1)
        }
        return Duration.ofSeconds(segundos)
    }
}
