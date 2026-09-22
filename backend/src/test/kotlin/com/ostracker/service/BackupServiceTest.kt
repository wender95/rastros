package com.ostracker.service

import com.ostracker.TesteIntegracao
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import java.nio.file.Paths
import java.sql.DriverManager

class BackupServiceTest : TesteIntegracao() {

    @Autowired lateinit var backup: BackupService
    @Value("\${ostracker.backup.pasta}") lateinit var pasta: String

    @Test
    fun `o backup restaura num banco vazio com os mesmos dados`() {
        val feito = backup.fazer("teste")
        val arquivo = Paths.get(pasta).toAbsolutePath().resolve(feito.arquivo)
        assertThat(feito.tamanhoBytes).isGreaterThan(0)

        DriverManager.getConnection("jdbc:h2:mem:restauro;MODE=PostgreSQL", "sa", "").use { conexao ->
            conexao.createStatement().use { sql ->
                val origem = arquivo.toString().replace('\\', '/')
                sql.execute("RUNSCRIPT FROM '$origem' COMPRESSION ZIP")
                sql.executeQuery("select count(*) from usuarios").use {
                    it.next()
                    assertThat(it.getLong(1)).isEqualTo(usuarioRepository.count())
                }
            }
        }
    }

    @Test
    fun `guarda so os backups mais recentes`() {
        repeat(5) { backup.fazer("poda") }

        assertThat(backup.listar()).hasSize(3) // manter: 3 no perfil de teste
    }
}
