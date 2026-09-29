package com.rastros.security

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.ResponseStatus
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Tentativas demais: o login fica fechado um tempo (HTTP 429). */
@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
class MuitasTentativasException(message: String) : RuntimeException(message)

/**
 * Freio contra adivinhar senha, agora que o sistema fica na internet: depois de
 * [LIMITE_POR_USUARIO] senhas erradas seguidas no mesmo usuario - ou [LIMITE_POR_ENDERECO]
 * vindas do mesmo endereco -, o login fica fechado por [JANELA]. Entrar certo zera o
 * contador do usuario.
 *
 * Fica na memoria: reiniciar o sistema zera tudo, o que e aceitavel para um freio.
 */
@Component
class ProtecaoDeLogin {

    private val falhas = ConcurrentHashMap<String, MutableList<Instant>>()

    /** Recusa antes de conferir a senha, se ja passou do limite. */
    fun conferir(usuario: String, endereco: String) {
        if (recentes("u:${usuario.lowercase()}") >= LIMITE_POR_USUARIO || recentes("e:$endereco") >= LIMITE_POR_ENDERECO) {
            throw MuitasTentativasException(
                "Muitas tentativas de senha. Espere ${JANELA.toMinutes()} minutos e tente de novo."
            )
        }
    }

    fun falhou(usuario: String, endereco: String) {
        anotar("u:${usuario.lowercase()}")
        anotar("e:$endereco")
    }

    fun entrou(usuario: String) {
        falhas.remove("u:${usuario.lowercase()}")
    }

    /** Zera os contadores (usado nos testes). */
    fun esquecerTudo() = falhas.clear()

    private fun anotar(chave: String) {
        falhas.compute(chave) { _, lista -> (lista ?: mutableListOf()).apply { add(Instant.now()) } }
    }

    private fun recentes(chave: String): Int {
        val limite = Instant.now().minus(JANELA)
        val lista = falhas.computeIfPresent(chave) { _, l -> l.apply { removeIf { it.isBefore(limite) } }.takeIf { it.isNotEmpty() } }
        return lista?.size ?: 0
    }

    companion object {
        const val LIMITE_POR_USUARIO = 5
        const val LIMITE_POR_ENDERECO = 20
        val JANELA: Duration = Duration.ofMinutes(15)

        /**
         * O endereco de quem chama. Atras do Cloudflare, o endereco da conexao e o do tunel;
         * o do visitante vem no cabecalho CF-Connecting-IP.
         */
        fun enderecoDe(request: jakarta.servlet.http.HttpServletRequest): String =
            request.getHeader("CF-Connecting-IP")?.takeIf { it.isNotBlank() } ?: request.remoteAddr
    }
}
