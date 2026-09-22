package com.ostracker

import com.ostracker.repository.UsuarioRepository
import com.ostracker.security.UsuarioAutenticado
import com.ostracker.security.paraAutenticado
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

/**
 * Base dos testes que sobem a aplicacao inteira sobre um H2 em memoria.
 *
 * Cada teste roda numa transacao desfeita ao final, entao um nao enxerga o que o outro
 * gravou. Os usuarios de demonstracao da carga inicial servem de autores.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
abstract class TesteIntegracao {

    @Autowired
    protected lateinit var usuarioRepository: UsuarioRepository

    protected fun autor(email: String = "diretoria@ostracker.com"): UsuarioAutenticado =
        usuarioRepository.findByEmailIgnoreCase(email)!!.paraAutenticado()
}
