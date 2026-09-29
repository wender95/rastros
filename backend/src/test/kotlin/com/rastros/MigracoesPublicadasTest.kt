package com.rastros

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Migracao que ja rodou no servidor nao se altera nem num comentario: o Flyway compara a
 * assinatura e o sistema nao sobe ("checksum mismatch"). Os testes usam banco novo e nao
 * pegariam isso; esta lista pega. Migracao nova: acrescente a linha dela no arquivo
 * migracoes-publicadas.sha256 (e dali em diante ela tambem fica congelada).
 */
class MigracoesPublicadasTest {

    private val pasta = Path.of("src/main/resources/db/migration")

    /** A assinatura do conteudo, sem depender de a copia do git estar com CRLF ou LF. */
    private fun assinatura(arquivo: Path): String =
        MessageDigest.getInstance("SHA-256")
            .digest(Files.readString(arquivo).replace("\r", "").toByteArray())
            .joinToString("") { "%02x".format(it) }

    private val publicadas: Map<String, String> =
        javaClass.getResource("/migracoes-publicadas.sha256")!!.readText().lines()
            .filter { it.isNotBlank() }
            .associate { linha -> linha.substringAfter(' ').trim() to linha.substringBefore(' ') }

    @Test
    fun `nenhuma migracao ja publicada foi alterada`() {
        val alteradas = publicadas.filter { (nome, esperada) ->
            val arquivo = pasta.resolve(nome)
            !Files.exists(arquivo) || assinatura(arquivo) != esperada
        }.keys
        assertThat(alteradas)
            .withFailMessage("Migracoes ja publicadas foram alteradas (o servidor nao subiria): %s", alteradas)
            .isEmpty()
    }

    @Test
    fun `toda migracao esta na lista`() {
        val naPasta = Files.list(pasta).use { lista -> lista.map { it.fileName.toString() }.filter { it.endsWith(".sql") }.toList() }
        assertThat(publicadas.keys).containsAll(naPasta)
    }
}
