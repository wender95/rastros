package com.ostracker.config

import com.ostracker.security.JwtAuthFilter
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableMethodSecurity
class SecurityConfig(
    private val jwtAuthFilter: JwtAuthFilter,
    @Value("\${ostracker.cors.origens}") private val origens: String
) {

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowedOriginPatterns = origens.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("*")
            allowCredentials = true
        }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/**", config) }
    }

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors(Customizer.withDefaults())
            .headers { it.frameOptions { fo -> fo.sameOrigin() } }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                // Quem ve o que (ver COMO-EXECUTAR, "Perfis e acessos"). A ordem importa: vale
                // a primeira regra que casar. A tela so esconde o menu; quem barra e aqui.
                it.requestMatchers("/api/auth/login", "/api/health", "/h2-console/**").permitAll()
                it.requestMatchers("/api/auth/**", "/api/setores/**").authenticated()

                it.requestMatchers("/api/admin/usuarios/**").hasAnyRole(ADMIN, FINANCEIRO)
                it.requestMatchers("/api/admin/**").hasRole(ADMIN)

                // Setores: receber, devolver e despachar a OS do proprio setor. O Financeiro e um
                // setor tambem - recebe as OS e e o unico que conclui.
                it.requestMatchers("/api/movimentacao/**").hasAnyRole(OPERACIONAL, FINANCEIRO)
                it.requestMatchers(HttpMethod.POST, "/api/fluxos/*/receber", "/api/fluxos/*/devolver")
                    .hasAnyRole(OPERACIONAL, FINANCEIRO)
                it.requestMatchers(HttpMethod.POST, "/api/fluxos/*/concluir").hasRole(FINANCEIRO)
                // Despachar tambem e do comercial: a saida da Prateleira/Patio para o Financeiro (RN05).
                it.requestMatchers(HttpMethod.POST, "/api/fluxos/*/despachar")
                    .hasAnyRole(OPERACIONAL, FINANCEIRO, VENDEDOR, DIRETORIA, ADMIN)

                // Modo edicao dos adesivadores da agenda: so Diretoria e Administrador.
                it.requestMatchers(HttpMethod.POST, "/api/agenda/colunas", "/api/agenda/colunas/**").hasAnyRole(DIRETORIA, ADMIN)
                it.requestMatchers(HttpMethod.PUT, "/api/agenda/colunas/**").hasAnyRole(DIRETORIA, ADMIN)
                it.requestMatchers(HttpMethod.DELETE, "/api/agenda/colunas/**").hasAnyRole(DIRETORIA, ADMIN)
                it.requestMatchers("/api/ordens/**", "/api/fluxos/**", "/api/agenda/**")
                    .hasAnyRole(VENDEDOR, DIRETORIA, ADMIN)
                it.requestMatchers("/api/painel", "/api/painel/**")
                    .hasAnyRole(VENDEDOR, DIRETORIA, ADMIN, FINANCEIRO)
                it.requestMatchers("/api/produtividade/**").hasAnyRole(DIRETORIA, ADMIN)

                // Rota de API fora da lista: ninguem.
                // Cada um ve a propria coluna da agenda; quem nao adesiva recebe 404.
                it.requestMatchers("/api/minha-agenda", "/api/minha-agenda/**").authenticated()
                // Nomes dos vendedores (so leitura), usados na agenda e na Minha agenda.
                it.requestMatchers(HttpMethod.GET, "/api/vendedores").authenticated()
                it.requestMatchers("/api/**").denyAll()
                // A tela (index.html, js, css) e publica: quem protege os dados e a API.
                it.anyRequest().permitAll()
            }
            .exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }

    private companion object {
        const val ADMIN = "ADMIN"
        const val DIRETORIA = "DIRETORIA"
        const val VENDEDOR = "VENDEDOR"
        const val FINANCEIRO = "FINANCEIRO"
        const val OPERACIONAL = "OPERACIONAL"
    }
}
