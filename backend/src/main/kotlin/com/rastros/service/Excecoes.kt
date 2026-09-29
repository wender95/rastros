package com.rastros.service

/** Violacao de regra de negocio -> 422. */
class RegraDeNegocioException(mensagem: String) : RuntimeException(mensagem)

/** Acao nao permitida para o perfil/setor do usuario -> 403. */
class PermissaoNegadaException(mensagem: String) : RuntimeException(mensagem)

/** Recurso inexistente -> 404. */
class NaoEncontradoException(mensagem: String) : RuntimeException(mensagem)
