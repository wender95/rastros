package db.migration

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.LoggerFactory
import java.text.Normalizer

/**
 * O login passa a ser um nome de usuario, e o e-mail vira contato opcional.
 *
 * Cada conta que ja existe ganha como usuario a parte do e-mail antes do @
 * (admin@ostracker.com -> admin). Se dois e-mails derem o mesmo nome, o segundo ganha um
 * numero (financeiro, financeiro2). As senhas nao mudam.
 */
@Suppress("ClassName")
class V7__Login_por_nome_de_usuario : BaseJavaMigration() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun migrate(context: Context) {
        val conexao = context.connection
        conexao.createStatement().use { it.execute("ALTER TABLE usuarios ADD COLUMN login VARCHAR(50)") }

        val usados = mutableSetOf<String>()
        val novos = mutableListOf<Pair<Int, String>>()
        conexao.createStatement().use { consulta ->
            consulta.executeQuery("SELECT id, email FROM usuarios ORDER BY id").use { linhas ->
                while (linhas.next()) {
                    val base = loginDoEmail(linhas.getString("email"))
                    val login = generateSequence(1) { it + 1 }
                        .map { n -> if (n == 1) base else "$base$n" }
                        .first { it !in usados }
                    usados += login
                    novos += linhas.getInt("id") to login
                }
            }
        }
        conexao.prepareStatement("UPDATE usuarios SET login = ? WHERE id = ?").use { update ->
            novos.forEach { (id, login) ->
                update.setString(1, login)
                update.setInt(2, id)
                update.addBatch()
            }
            if (novos.isNotEmpty()) update.executeBatch()
        }

        conexao.createStatement().use { sql ->
            sql.execute("ALTER TABLE usuarios ALTER COLUMN login SET NOT NULL")
            sql.execute("ALTER TABLE usuarios ADD CONSTRAINT uk_usuarios_login UNIQUE (login)")
            sql.execute("ALTER TABLE usuarios ALTER COLUMN email DROP NOT NULL")
        }
        novos.forEach { (id, login) -> log.info("Usuario {}: login '{}'", id, login) }
    }

    /** A parte antes do @, em minusculas, sem acento e so com letras, numeros, ponto, hifen e sublinhado. */
    private fun loginDoEmail(email: String?): String {
        val local = email?.substringBefore('@').orEmpty().lowercase()
        val semAcento = Normalizer.normalize(local, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        val limpo = semAcento.replace(Regex("[^a-z0-9._-]"), "").take(30)
        return limpo.ifEmpty { "usuario" }
    }
}
