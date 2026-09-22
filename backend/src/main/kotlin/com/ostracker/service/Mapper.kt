package com.ostracker.service

import com.ostracker.api.*
import com.ostracker.domain.*
import java.time.Instant

fun Usuario.paraResponse() = UsuarioResponse(
    id = id!!,
    nome = nome,
    login = login,
    email = email,
    perfil = perfil.nome,
    setor = setor?.nome,
    setorId = setor?.id,
    ativo = ativo,
    trocarSenha = trocarSenha
)

fun Setor.paraResponse() = SetorResponse(
    id = id!!,
    nome = nome,
    ativo = ativo,
    localFisico = nome.localFisico
)

fun EventoMovimentacao.paraResponse() = EventoResponse(
    id = id!!,
    tipoEvento = tipoEvento,
    setorOrigem = setorOrigem?.nome,
    setorDestino = setorDestino.nome,
    usuario = usuario.nome,
    perfilUsuario = usuario.perfil.nome,
    dataHora = dataHora,
    observacao = observacao
)

fun FluxoOs.paraResponse(): FluxoResponse {
    val referencia = encerradoEm ?: Instant.now()
    return FluxoResponse(
        id = id!!,
        osId = ordemServico.id!!,
        numeroOsErp = ordemServico.numeroOsErp,
        cliente = ordemServico.cliente,
        identificadorFluxo = identificadorFluxo,
        status = statusAtual,
        setorAtualId = setorAtual.id!!,
        setorAtual = setorAtual.nome,
        setorAnterior = setorAnterior?.nome,
        recebidoPor = recebidoPor?.nome,
        recebidoEm = recebidoEm,
        entrouNoSetorEm = entrouNoSetorEm,
        encerrado = encerrado,
        encerradoEm = encerradoEm,
        motivoCancelamento = motivoCancelamento,
        criadoEm = ordemServico.criadoEm,
        criadoPor = ordemServico.criadoPor.nome,
        segundosNoSetor = HorarioComercial.entre(entrouNoSetorEm, referencia).seconds
    )
}

fun OrdemServico.paraResponse(fluxosDaOs: List<FluxoOs>) = OrdemResponse(
    id = id!!,
    numeroOsErp = numeroOsErp,
    cliente = cliente,
    criadoPor = criadoPor.nome,
    criadoEm = criadoEm,
    cancelada = cancelada,
    motivoCancelamento = motivoCancelamento,
    fluxos = fluxosDaOs.map { it.paraResponse() }
)
