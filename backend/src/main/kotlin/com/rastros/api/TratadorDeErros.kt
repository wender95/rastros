package com.rastros.api

import com.rastros.service.NaoEncontradoException
import com.rastros.service.PermissaoNegadaException
import com.rastros.service.RegraDeNegocioException
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class TratadorDeErros {

    @ExceptionHandler(RegraDeNegocioException::class)
    fun regra(e: RegraDeNegocioException) =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ErroResponse(e.message ?: "Regra violada."))

    @ExceptionHandler(PermissaoNegadaException::class)
    fun permissao(e: PermissaoNegadaException) =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErroResponse(e.message ?: "Acesso negado."))

    /** Freio do login: tentativas demais de senha. */
    @ExceptionHandler(com.rastros.security.MuitasTentativasException::class)
    fun muitasTentativas(e: com.rastros.security.MuitasTentativasException) =
        ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(ErroResponse(e.message ?: "Muitas tentativas."))

    @ExceptionHandler(NaoEncontradoException::class)
    fun naoEncontrado(e: NaoEncontradoException) =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErroResponse(e.message ?: "Nao encontrado."))

    /** Duas pessoas no mesmo fluxo ao mesmo tempo: a segunda perde e ve a OS como ficou. */
    @ExceptionHandler(ConcurrencyFailureException::class)
    fun concorrencia(e: ConcurrencyFailureException) =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ErroResponse("Outra pessoa movimentou esta OS agora mesmo. Atualize a tela e confira antes de repetir.")
        )

    /** Regra que so o banco consegue garantir sob concorrencia (ex.: numero de OS unico). */
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun integridade(e: DataIntegrityViolationException): ResponseEntity<ErroResponse> {
        val mensagem = if (e.mostSpecificCause.message.orEmpty().contains("UK_OS_NUMERO_ATIVO", ignoreCase = true)) {
            "Esta OS acabou de ser aberta por outra pessoa. Atualize e acrescente os fluxos nela."
        } else {
            "O registro conflita com outro que ja existe. Atualize a tela e tente de novo."
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErroResponse(mensagem))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validacao(e: MethodArgumentNotValidException): ResponseEntity<ErroResponse> {
        val detalhes = e.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "invalido") }
        return ResponseEntity.badRequest()
            .body(ErroResponse("Dados invalidos.", detalhes))
    }
}
