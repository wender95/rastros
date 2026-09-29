package com.rastros.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.sql.DataSource
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

data class BackupResponse(val arquivo: String, val tamanhoBytes: Long, val criadoEm: Instant)

/**
 * Copia de seguranca do banco H2.
 *
 * Usa `SCRIPT TO` - um dump SQL compactado - e nao a copia do arquivo binario: o dump
 * restaura ate numa versao futura do H2 e pode ser feito com a aplicacao no ar. Guarda
 * os [manter] mais recentes.
 *
 * No PostgreSQL o backup e do servidor de banco (pg_dump), nao da aplicacao.
 */
@Service
class BackupService(
    private val dataSource: DataSource,
    @Value("\${rastros.backup.pasta}") pasta: String,
    @Value("\${rastros.backup.manter}") private val manter: Int
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val pasta: Path = Paths.get(pasta).toAbsolutePath().normalize()

    val disponivel: Boolean by lazy {
        dataSource.connection.use { it.metaData.databaseProductName.equals("H2", ignoreCase = true) }
    }

    /** Todo dia util na hora do almoco, quando ninguem esta mexendo na agenda. */
    @Scheduled(cron = "\${rastros.backup.cron}")
    fun backupAgendado() {
        if (disponivel) runCatching { fazer("diario") }.onFailure { log.error("Backup diario falhou.", it) }
    }

    fun fazer(motivo: String): BackupResponse {
        if (!disponivel) {
            throw RegraDeNegocioException(
                "O backup pela aplicacao e do banco H2. No PostgreSQL use o backup do servidor (pg_dump)."
            )
        }
        Files.createDirectories(pasta)
        // Data com milissegundos no comeco do nome: unico e em ordem cronologica.
        val carimbo = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
        val sufixo = motivo.filter { it.isLetterOrDigit() || it == '-' }
        val arquivo = pasta.resolve("rastros-$carimbo-$sufixo.zip")
        // O caminho vai dentro do SQL: aspas simples dobradas, como manda o SQL.
        val destino = arquivo.toString().replace('\\', '/').replace("'", "''")
        JdbcTemplate(dataSource).execute("SCRIPT TO '$destino' COMPRESSION ZIP")
        log.info("Backup gravado em {}", arquivo)
        podar()
        return arquivo.paraResponse()
    }

    fun listar(): List<BackupResponse> =
        backups().map { it.paraResponse() }

    private fun backups(): List<Path> =
        if (!Files.isDirectory(pasta)) emptyList()
        // Os de antes da troca de nome comecam com "ostracker-": continuam na lista.
        else pasta.listDirectoryEntries("*.zip")
            .filter { it.name.startsWith("rastros-") || it.name.startsWith("ostracker-") }
            .sortedByDescending { it.name.substringAfter('-') } // depois do prefixo, o nome comeca pela data

    private fun podar() {
        backups().drop(manter).forEach {
            Files.deleteIfExists(it)
            log.info("Backup antigo removido: {}", it.name)
        }
    }

    private fun Path.paraResponse() =
        BackupResponse(name, fileSize(), getLastModifiedTime().toInstant())
}
