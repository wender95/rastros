package com.rastros

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import java.nio.file.Files
import java.nio.file.Path

@SpringBootApplication
class RastrosApplication

fun main(args: Array<String>) {
    // Banco no lugar padrao (sem endereco configurado): pode estar ainda com o nome antigo.
    if (System.getenv("DB_URL") == null && System.getenv("SPRING_DATASOURCE_URL") == null) {
        renomearBancoAntigo(Path.of("data"))
    }
    runApplication<RastrosApplication>(*args)
}

/**
 * O sistema se chamava OS Tracker e o banco, `ostracker.mv.db`. Na primeira partida com o
 * nome novo, o arquivo passa a se chamar `rastros.mv.db` - senao o sistema abriria um banco
 * vazio. Ja existindo o novo, nada muda.
 */
fun renomearBancoAntigo(pasta: Path): Boolean {
    val antigo = pasta.resolve("ostracker.mv.db")
    val novo = pasta.resolve("rastros.mv.db")
    if (!Files.exists(antigo) || Files.exists(novo)) return false
    Files.move(antigo, novo)
    pasta.resolve("ostracker.trace.db").takeIf { Files.exists(it) }
        ?.let { Files.move(it, pasta.resolve("rastros.trace.db")) }
    println("Banco renomeado: ${antigo.toAbsolutePath()} -> ${novo.fileName}")
    return true
}
