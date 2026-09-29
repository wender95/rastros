package com.rastros

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** O banco com o nome antigo (ostracker) vira rastros na primeira partida, sem perder nada. */
class RenomearBancoTest {

    @Test
    fun `o banco antigo ganha o nome novo, com o conteudo`(@TempDir pasta: Path) {
        Files.writeString(pasta.resolve("ostracker.mv.db"), "dados da producao")

        assertThat(renomearBancoAntigo(pasta)).isTrue()

        assertThat(Files.exists(pasta.resolve("ostracker.mv.db"))).isFalse()
        assertThat(Files.readString(pasta.resolve("rastros.mv.db"))).isEqualTo("dados da producao")
    }

    @Test
    fun `ja existindo o banco novo, nada muda`(@TempDir pasta: Path) {
        Files.writeString(pasta.resolve("ostracker.mv.db"), "antigo")
        Files.writeString(pasta.resolve("rastros.mv.db"), "novo")

        assertThat(renomearBancoAntigo(pasta)).isFalse()

        assertThat(Files.readString(pasta.resolve("rastros.mv.db"))).isEqualTo("novo")
        assertThat(Files.exists(pasta.resolve("ostracker.mv.db"))).isTrue()
    }

    @Test
    fun `sem banco antigo, nada acontece`(@TempDir pasta: Path) {
        assertThat(renomearBancoAntigo(pasta)).isFalse()
    }
}
