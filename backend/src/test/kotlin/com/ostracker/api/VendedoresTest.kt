package com.ostracker.api

import com.ostracker.TesteIntegracao
import com.ostracker.domain.PerfilNome
import com.ostracker.domain.Usuario
import com.ostracker.repository.PerfilRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** Os vendedores da agenda saem dos usuarios cadastrados, sem nome fixo no codigo. */
class VendedoresTest : TesteIntegracao() {

    @Autowired lateinit var vendedores: VendedoresController
    @Autowired lateinit var perfis: PerfilRepository

    private fun usuario(nome: String, login: String, perfil: PerfilNome, ativo: Boolean = true) =
        usuarioRepository.save(Usuario(nome = nome, login = login, senhaHash = "x", perfil = perfis.findByNome(perfil)!!, ativo = ativo))

    @Test
    fun `o codigo e a primeira letra do nome, e o comercial vem antes da diretoria`() {
        usuario("Xuxa Diretora", "xuxa", PerfilNome.DIRETORIA)
        usuario("Xavier Comercial", "xavier", PerfilNome.VENDEDOR)
        usuario("Yuri Desligado", "yuri", PerfilNome.VENDEDOR, ativo = false)
        usuario("Zeca Operador", "zeca", PerfilNome.OPERACIONAL)
        usuario("Ágata Comercial", "agata", PerfilNome.VENDEDOR)

        val porCodigo = vendedores.listar().associate { it.codigo to it.nome }

        assertThat(porCodigo["X"]).isEqualTo("Xavier Comercial")
        assertThat(porCodigo).doesNotContainKeys("Y", "Z") // inativo e operacional nao entram
        assertThat(porCodigo.values).contains("Ágata Comercial") // acento nao atrapalha: vira A (se livre)
    }
}
