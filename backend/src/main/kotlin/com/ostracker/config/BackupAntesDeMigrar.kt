package com.ostracker.config

import com.ostracker.service.BackupService
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import javax.sql.DataSource

/**
 * Antes de o Flyway alterar um banco que ja tem dados, tira uma copia. Uma migracao que
 * de errado no banco da empresa tem volta.
 */
@Configuration
@EnableScheduling
class BackupAntesDeMigrar {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun estrategiaDeMigracao(backup: BackupService, dataSource: DataSource) = FlywayMigrationStrategy { flyway: Flyway ->
        val pendentes = flyway.info().pending()
        if (pendentes.isNotEmpty() && temDados(dataSource) && backup.disponivel) {
            log.info("{} migracao(oes) pendente(s): fazendo backup antes de aplicar.", pendentes.size)
            backup.fazer("antes-da-migracao-v${pendentes.last().version}")
        }
        flyway.migrate()
    }

    private fun temDados(dataSource: DataSource): Boolean = dataSource.connection.use { conexao ->
        conexao.metaData.getTables(null, null, "%", arrayOf("TABLE")).use { tabelas ->
            generateSequence { if (tabelas.next()) tabelas.getString("TABLE_NAME") else null }
                .any { it.equals("usuarios", ignoreCase = true) }
        }
    }
}
