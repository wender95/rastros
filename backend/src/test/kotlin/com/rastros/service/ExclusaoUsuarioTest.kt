package com.rastros.service

import com.rastros.TesteIntegracao
import com.rastros.api.NovaOrdemRequest
import com.rastros.api.NovoFluxoRequest
import com.rastros.domain.PerfilNome
import com.rastros.domain.SetorNome
import com.rastros.domain.Usuario
import com.rastros.repository.PerfilRepository
import com.rastros.repository.SetorRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** Excluir usuario: so quem nao deixou historico; quem trabalhou se desativa. */
class ExclusaoUsuarioTest : TesteIntegracao() {

    @Autowired lateinit var exclusao: ExclusaoUsuarioService
    @Autowired lateinit var fluxos: FluxoService
    @Autowired lateinit var setores: SetorRepository

    @Autowired lateinit var perfis: PerfilRepository

    private fun id(email: String) = usuarioRepository.findByEmailIgnoreCase(email)!!.id!!

    /** Conta nova, sem nenhum registro. */
    private fun novo(login: String) = usuarioRepository.save(
        Usuario(
            nome = "Conta $login", login = login, senhaHash = "x",
            perfil = perfis.findByNome(PerfilNome.VENDEDOR)!!, ativo = false
        )
    ).id!!

    @Test
    fun `quem nunca mexeu em nada e excluido de vez`() {
        val alvo = novo("antigo")

        exclusao.excluir(alvo, autor("admin@rastros.cloud"))

        assertThat(usuarioRepository.findById(alvo)).isEmpty()
    }

    @Test
    fun `quem tem historico nao e excluido - desativa-se`() {
        fluxos.criarOrdem(
            NovaOrdemRequest(
                numeroOsErp = "EXC-1",
                fluxos = listOf(NovoFluxoRequest(setorInicialId = setores.findByNome(SetorNome.CRIACAO)!!.id!!))
            ),
            autor("vendedor@rastros.cloud")
        )
        val alvo = id("vendedor@rastros.cloud")

        assertThatThrownBy { exclusao.excluir(alvo, autor("admin@rastros.cloud")) }
            .isInstanceOf(RegraDeNegocioException::class.java)
            .hasMessageContaining("Desative")
        assertThat(usuarioRepository.findById(alvo)).isPresent
    }

    @Test
    fun `ninguem exclui a propria conta`() {
        assertThatThrownBy { exclusao.excluir(id("admin@rastros.cloud"), autor("admin@rastros.cloud")) }
            .isInstanceOf(RegraDeNegocioException::class.java)
    }

    @Test
    fun `o financeiro exclui usuarios comuns, mas nao administrador`() {
        assertThatThrownBy { exclusao.excluir(id("admin@rastros.cloud"), autor("financeiro@rastros.cloud")) }
            .isInstanceOf(PermissaoNegadaException::class.java)

        val alvo = novo("comum")
        exclusao.excluir(alvo, autor("financeiro@rastros.cloud"))
        assertThat(usuarioRepository.findById(alvo)).isEmpty()
    }
}
