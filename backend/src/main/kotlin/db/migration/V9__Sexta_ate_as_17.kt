package db.migration

import com.ostracker.domain.Agendamento
import com.ostracker.domain.FaixasDoDia
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.DayOfWeek

/**
 * A sexta passou a ir ate as 17:00. Os carros que ja ocupavam 17:00-18:00 de uma sexta
 * perdem essa hora e terminam onde terminavam - encurtar, em vez de empurrar, nao move
 * nenhum outro carro nem cria sobreposicao. Quem comecava exatamente as 17:00 de sexta
 * passa a comecar no proximo horario livre (segunda 07:30).
 */
@Suppress("ClassName")
class V9__Sexta_ate_as_17 : BaseJavaMigration() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun migrate(context: Context) {
        val conexao = context.connection
        data class Ajuste(val id: Long, val data: java.time.LocalDate, val slot: Int, val horas: BigDecimal)
        val ajustes = mutableListOf<Ajuste>()

        conexao.createStatement().use { consulta ->
            consulta.executeQuery("SELECT id, data, slot_inicio, horas_estimadas, duracao_slots FROM agendamentos").use { l ->
                while (l.next()) {
                    val data = l.getDate("data").toLocalDate()
                    val slot = l.getInt("slot_inicio")
                    val horas = l.getBigDecimal("horas_estimadas") ?: Agendamento.converterFaixasAntigas(l.getInt("duracao_slots"))
                    // A regua antiga: so o almoco era pulado.
                    val antigas = FaixasDoDia.posicoesOcupadas(slot, horas)
                    val naSextaDepoisDas17 = antigas.filter {
                        it % FaixasDoDia.QUANTIDADE == FaixasDoDia.QUANTIDADE - 1 &&
                            FaixasDoDia.diaUtilAFrente(data, it / FaixasDoDia.QUANTIDADE).dayOfWeek == DayOfWeek.FRIDAY
                    }.toSet()
                    if (naSextaDepoisDas17.isEmpty()) continue

                    val ficam = antigas.filterNot { it in naSextaDepoisDas17 }
                    if (ficam.isEmpty()) {
                        // Era so a hora das 17:00 de sexta: vai para o proximo horario livre.
                        val proxima = FaixasDoDia.livreAPartirDe(data, antigas.first())
                        ajustes += Ajuste(
                            l.getLong("id"), FaixasDoDia.diaUtilAFrente(data, proxima / FaixasDoDia.QUANTIDADE),
                            proxima % FaixasDoDia.QUANTIDADE + 1, horas
                        )
                        continue
                    }
                    val primeira = ficam.first()
                    ajustes += Ajuste(
                        l.getLong("id"),
                        FaixasDoDia.diaUtilAFrente(data, primeira / FaixasDoDia.QUANTIDADE),
                        primeira % FaixasDoDia.QUANTIDADE + 1,
                        FaixasDoDia.horasDasPosicoes(ficam)
                    )
                }
            }
        }

        conexao.prepareStatement("UPDATE agendamentos SET data = ?, slot_inicio = ?, horas_estimadas = ? WHERE id = ?").use { u ->
            ajustes.forEach {
                u.setDate(1, java.sql.Date.valueOf(it.data))
                u.setInt(2, it.slot)
                u.setBigDecimal(3, it.horas)
                u.setLong(4, it.id)
                u.addBatch()
            }
            if (ajustes.isNotEmpty()) u.executeBatch()
        }
        log.info("Agenda: {} carro(s) ajustado(s) para a sexta terminar as 17:00.", ajustes.size)
    }
}
