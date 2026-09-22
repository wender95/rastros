package com.ostracker.config

import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.resource.PathResourceResolver
import java.util.concurrent.TimeUnit

/**
 * Serve a tela (o build do React que vai dentro do jar).
 *
 * O React Router cuida das rotas no navegador, entao recarregar /agenda ou abrir um link
 * de /ordens/42 pede ao servidor um caminho que nao e arquivo: devolve o index.html e o
 * app assume dali. Caminhos de /api nunca caem aqui - quem nao existe na API e 404.
 */
@Configuration
class FrontendConfig : WebMvcConfigurer {

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        // Arquivos do build levam o hash no nome: podem ficar em cache por um ano.
        registry.addResourceHandler("/assets/**")
            .addResourceLocations("classpath:/static/assets/")
            .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())

        // O resto (index.html e rotas do app) sempre revalida, para uma versao nova aparecer.
        registry.addResourceHandler("/**")
            .addResourceLocations("classpath:/static/")
            .setCacheControl(CacheControl.noCache())
            .resourceChain(true)
            .addResolver(object : PathResourceResolver() {
                override fun getResource(caminho: String, local: Resource): Resource? {
                    if (caminho.startsWith("api/")) return null
                    val pedido = local.createRelative(caminho)
                    if (pedido.exists() && pedido.isReadable) return pedido
                    return ClassPathResource("/static/index.html").takeIf { it.exists() }
                }
            })
    }
}
