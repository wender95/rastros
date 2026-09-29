package com.rastros.service

import com.rastros.domain.PerfilNome
import com.rastros.repository.UsuarioRepository
import com.rastros.security.UsuarioAutenticado
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Excluir um usuario de vez. So vale para quem nao deixou rastro: a linha do tempo das OS
 * e a agenda guardam quem fez cada coisa, e esse registro nao pode apontar para ninguem.
 * Quem ja trabalhou no sistema se desativa (nao entra mais, mas o historico fica).
 *
 * O que nao e historico vai junto: a pilha do Desfazer da agenda e o vinculo de alguma
 * coluna da agenda com a conta.
 */
@Service
class ExclusaoUsuarioService(
    private val usuarioRepository: UsuarioRepository,
    private val jdbc: JdbcTemplate
) {

    @Transactional
    fun excluir(id: Int, autor: UsuarioAutenticado): String {
        val usuario = usuarioRepository.findById(id)
            .orElseThrow { NaoEncontradoException("Usuario $id nao encontrado.") }

        if (usuario.id == autor.id) throw RegraDeNegocioException("Voce nao pode excluir a propria conta.")
        if (autor.perfil != PerfilNome.ADMIN && usuario.perfil.nome !in com.rastros.api.GERENCIAVEIS_PELO_FINANCEIRO) {
            throw PermissaoNegadaException("O Financeiro exclui so contas de Operacional e Comercial.")
        }
        if (usuario.perfil.nome == PerfilNome.ADMIN) {
            if (autor.perfil != PerfilNome.ADMIN) {
                throw PermissaoNegadaException("Somente um administrador exclui contas de administrador.")
            }
            val outrosAdmins = usuarioRepository.findAll()
                .count { it.id != id && it.ativo && it.perfil.nome == PerfilNome.ADMIN }
            if (outrosAdmins == 0) throw RegraDeNegocioException("Nao e possivel excluir o ultimo administrador.")
        }

        val registros = HISTORICO.sumOf { (tabela, coluna) ->
            jdbc.queryForObject("SELECT COUNT(*) FROM $tabela WHERE $coluna = ?", Long::class.java, id) ?: 0L
        }
        if (registros > 0) {
            throw RegraDeNegocioException(
                "${usuario.nome} tem $registros registro(s) no historico das OS e da agenda e nao pode ser excluido. " +
                    "Desative o usuario: ele deixa de entrar, e o historico continua mostrando quem fez cada coisa."
            )
        }

        jdbc.update("DELETE FROM historico_agenda WHERE usuario_id = ?", id)
        jdbc.update("UPDATE adesivadores SET usuario_id = NULL WHERE usuario_id = ?", id)
        usuarioRepository.delete(usuario)
        return "${usuario.nome} (${usuario.login}) foi excluido."
    }

    private companion object {
        /** Onde o sistema guarda quem fez o que - tabela e coluna. */
        val HISTORICO = listOf(
            "ordens_servico" to "criado_por",
            "fluxos_os" to "recebido_por_id",
            "eventos_movimentacao" to "usuario_id",
            "agendamentos" to "criado_por"
        )
    }
}
