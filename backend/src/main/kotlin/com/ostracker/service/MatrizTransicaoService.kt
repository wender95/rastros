package com.ostracker.service

import com.ostracker.api.DestinoPermitidoResponse
import com.ostracker.domain.*
import com.ostracker.repository.SetorRepository
import com.ostracker.repository.TransicaoPermitidaRepository
import com.ostracker.security.UsuarioAutenticado
import org.springframework.stereotype.Service

/**
 * Matriz de transicao de setores (docs/06-fluxos-e-matriz-de-transicao.md).
 *
 * Alem dos destinos parametrizados, todo setor pode devolver o fluxo exclusivamente
 * para o setor de origem imediata (regra de excecao - Retorno).
 */
@Service
class MatrizTransicaoService(
    private val transicaoRepository: TransicaoPermitidaRepository,
    private val setorRepository: SetorRepository
) {

    /** Setores iniciais que o Vendedor pode escolher ao abrir um fluxo (origem = Vendas). */
    fun setoresIniciais(): List<Setor> =
        transicaoRepository.findBySetorOrigemIsNull()
            .mapNotNull { setorRepository.findByNome(it.setorDestino) }
            .filter { it.ativo }
            .sortedBy { it.nome.name }

    fun destinosDe(fluxo: FluxoOs): List<DestinoPermitidoResponse> {
        val daMatriz = transicaoRepository.findBySetorOrigem(fluxo.setorAtual.nome)
            .map { it.setorDestino }
            .toSet()

        val retorno = fluxo.setorAnterior?.nome?.takeIf { it !in daMatriz }

        return (daMatriz.map { it to false } + listOfNotNull(retorno?.let { it to true }))
            .mapNotNull { (nome, ehRetorno) ->
                setorRepository.findByNome(nome)
                    ?.takeIf { it.ativo }
                    ?.let { DestinoPermitidoResponse(it.id!!, it.nome, ehRetorno) }
            }
            .sortedWith(compareBy({ it.retorno }, { it.setor.name }))
    }

    fun validarDestino(fluxo: FluxoOs, destino: Setor) {
        val permitido = destinosDe(fluxo).any { it.setorId == destino.id }
        if (!permitido) {
            throw RegraDeNegocioException(
                "Destino ${destino.nome} nao permitido a partir de ${fluxo.setorAtual.nome}. " +
                    "O destino deve seguir a matriz de transicao ou ser o retorno ao setor de origem."
            )
        }
    }

    /**
     * Quem pode executar o despacho a partir do setor atual:
     * - PRATELEIRA / PATIO: apenas Comercial, Diretoria e Administrador (RN05);
     * - demais setores: apenas o Operacional lotado naquele setor (preserva auditabilidade).
     */
    fun validarExecutorDespacho(fluxo: FluxoOs, usuario: UsuarioAutenticado) {
        if (fluxo.setorAtual.nome.localFisico) {
            if (usuario.perfil != PerfilNome.VENDEDOR && usuario.perfil != PerfilNome.DIRETORIA &&
                usuario.perfil != PerfilNome.ADMIN
            ) {
                throw PermissaoNegadaException(
                    "A saida de ${fluxo.setorAtual.nome} para o Financeiro e restrita a Comercial, Diretoria e Administrador."
                )
            }
            return
        }
        if (!trabalhaNoSetor(usuario, fluxo.setorAtual)) {
            throw PermissaoNegadaException(
                "Somente um operador do setor ${fluxo.setorAtual.nome} pode despachar este fluxo."
            )
        }
    }

    /**
     * Quem trabalha num setor: o operador lotado nele e, no setor Financeiro, as pessoas
     * do perfil Financeiro - elas recebem as OS que chegam e sao as unicas que concluem.
     */
    fun trabalhaNoSetor(usuario: UsuarioAutenticado, setor: Setor): Boolean = when (usuario.perfil) {
        PerfilNome.OPERACIONAL -> usuario.setorId == setor.id
        PerfilNome.FINANCEIRO -> setor.nome == SetorNome.FINANCEIRO
        else -> false
    }

    /** O setor cuja tela "Meu setor" a pessoa ve. */
    fun setorDeTrabalho(usuario: UsuarioAutenticado): Setor? = when (usuario.perfil) {
        PerfilNome.OPERACIONAL -> usuario.setorId?.let { setorRepository.findById(it).orElse(null) }
        PerfilNome.FINANCEIRO -> setorRepository.findByNome(SetorNome.FINANCEIRO)
        else -> null
    }

    fun validarExecutorRecebimento(fluxo: FluxoOs, usuario: UsuarioAutenticado) {
        if (fluxo.setorAtual.nome.localFisico) {
            throw RegraDeNegocioException(
                "${fluxo.setorAtual.nome} e um local fisico de espera e nao possui etapa de recebimento."
            )
        }
        if (!trabalhaNoSetor(usuario, fluxo.setorAtual)) {
            throw PermissaoNegadaException(
                "Somente um operador do setor ${fluxo.setorAtual.nome} pode receber este fluxo."
            )
        }
    }
}
