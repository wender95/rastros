package com.ostracker.api

import com.ostracker.service.NaoEncontradoException
import com.ostracker.service.PermissaoNegadaException
import com.ostracker.service.RegraDeNegocioException
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

    @ExceptionHandler(NaoEncontradoException::class)
    fun naoEncontrado(e: NaoEncontradoException) =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErroResponse(e.message ?: "Nao encontrado."))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validacao(e: MethodArgumentNotValidException): ResponseEntity<ErroResponse> {
        val detalhes = e.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "invalido") }
        return ResponseEntity.badRequest()
            .body(ErroResponse("Dados invalidos.", detalhes))
    }
}
