package db.migration

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import java.text.Normalizer

/**
 * A lista inicial dos vendedores da agenda, montada como a agenda fazia ate aqui: dos
 * usuarios ativos do Comercial e da Diretoria, com a primeira letra do nome como codigo
 * ("(K)" e a Karina). Na disputa pela mesma letra, o Comercial vem antes da Diretoria.
 * Daqui em diante a lista e editada na propria agenda.
 */
@Suppress("ClassName")
class V14__vendedores_dos_usuarios : BaseJavaMigration() {

    override fun migrate(context: Context) {
        val conexao = context.connection
        val candidatos = mutableListOf<Pair<Int, String>>()
        conexao.createStatement().use { st ->
            st.executeQuery(
                "SELECT u.nome, p.nome FROM usuarios u JOIN perfis p ON p.id = u.perfil_id " +
                    "WHERE u.ativo = TRUE AND p.nome IN ('VENDEDOR', 'DIRETORIA') ORDER BY u.nome"
            ).use { rs ->
                while (rs.next()) candidatos += (if (rs.getString(2) == "VENDEDOR") 0 else 1) to rs.getString(1).trim()
            }
        }
        val porCodigo = linkedMapOf<String, String>()
        candidatos.sortedBy { it.first }.forEach { (_, nome) ->
            val codigo = Normalizer.normalize(nome, Normalizer.Form.NFD).firstOrNull { it.isLetter() }?.uppercase()
                ?: return@forEach
            porCodigo.putIfAbsent(codigo, nome.take(60))
        }
        conexao.prepareStatement("INSERT INTO vendedores_agenda (codigo, nome, ativo) VALUES (?, ?, TRUE)").use { ps ->
            porCodigo.forEach { (codigo, nome) ->
                ps.setString(1, codigo)
                ps.setString(2, nome)
                ps.executeUpdate()
            }
        }
    }
}
