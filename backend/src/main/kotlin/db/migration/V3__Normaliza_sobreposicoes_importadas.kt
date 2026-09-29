package db.migration

import com.rastros.domain.Agendamento
import com.rastros.domain.NormalizacaoAgenda
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.LoggerFactory

/**
 * Roda uma vez: encurta os servicos importados que invadem o seguinte (ver
 * [NormalizacaoAgenda]). Sem isso, o primeiro salvamento em cada trecho da agenda
 * herdava uma sobreposicao antiga.
 */
@Suppress("ClassName")
class V3__Normaliza_sobreposicoes_importadas : BaseJavaMigration() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun migrate(context: Context) {
        val conexao = context.connection
        val itens = mutableListOf<NormalizacaoAgenda.Item>()

        conexao.createStatement().use { consulta ->
            consulta.executeQuery(
                "SELECT id, adesivador_id, data, slot_inicio, horas_estimadas, duracao_slots, criado_por " +
                    "FROM agendamentos"
            ).use { linhas ->
                while (linhas.next()) {
                    itens += NormalizacaoAgenda.Item(
                        id = linhas.getLong("id"),
                        adesivadorId = linhas.getInt("adesivador_id"),
                        data = linhas.getDate("data").toLocalDate(),
                        slotInicio = linhas.getInt("slot_inicio"),
                        horas = linhas.getBigDecimal("horas_estimadas")
                            ?: Agendamento.converterFaixasAntigas(linhas.getInt("duracao_slots")),
                        importado = linhas.getObject("criado_por") == null
                    )
                }
            }
        }

        val ajustes = NormalizacaoAgenda.horasSemSobreposicao(itens)
        conexao.prepareStatement("UPDATE agendamentos SET horas_estimadas = ? WHERE id = ?").use { update ->
            ajustes.forEach { (id, horas) ->
                update.setBigDecimal(1, horas)
                update.setLong(2, id)
                update.addBatch()
            }
            if (ajustes.isNotEmpty()) update.executeBatch()
        }
        log.info("Agenda: {} servico(s) importado(s) encurtado(s) para nao invadir o seguinte.", ajustes.size)
    }
}
