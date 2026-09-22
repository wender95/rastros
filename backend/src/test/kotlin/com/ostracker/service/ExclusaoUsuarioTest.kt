package com.ostracker.service

import com.ostracker.TesteIntegracao
import com.ostracker.api.NovaOrdemRequest
import com.ostracker.api.NovoFluxoRequest
import com.ostracker.domain.PerfilNome
import com.ostracker.domain.SetorNome
import com.ostracker.domain.Usuario
import com.ostracker.repository.PerfilRepository
import com.ostracker.repository.SetorRepository
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

        exclusao.excluir(alvo, autor("admin@ostracker.com"))

        assertThat(usuarioRepository.findById(alvo)).isEmpty()
    }

    @Test
    fun `quem tem historico nao e excluido - desativa-se`() {
        fluxos.criarOrdem(
            NovaOrdemRequest(
                numeroOsErp = "EXC-1",
                fluxos = listOf(NovoFluxoRequest(setorInicialId = setores.findByNome(SetorNome.CRIACAO)!!.id!!))
            ),
            autor("vendedor@ostracker.com")
        )
        val alvo = id("vendedor@ostracker.com")

        assertThatThrownBy { exclusao.excluir(alvo, autor("admin@ostracker.com")) }
            .isInstanceOf(RegraDeNegocioException::class.java)
            .hasMessageContaining("Desative")
        assertThat(usuarioRepository.findById(alvo)).isPresent
    }

    @Test
    fun `ninguem exclui a propria conta`() {
        assertThatThrownBy { exclusao.excluir(id("admin@ostracker.com"), autor("admin@ostracker.com")) }
            .isInstanceOf(RegraDeNegocioException::class.java)
    }

    @Test
    fun `o financeiro exclui usuarios comuns, mas nao administrador`() {
        assertThatThrownBy { exclusao.excluir(id("admin@ostracker.com"), autor("financeiro@ostracker.com")) }
            .isInstanceOf(PermissaoNegadaException::class.java)

        val alvo = novo("comum")
        exclusao.excluir(alvo, autor("financeiro@ostracker.com"))
        assertThat(usuarioRepository.findById(alvo)).isEmpty()
    }
}
