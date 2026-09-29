package com.rastros.config

import com.rastros.security.JwtAuthFilter
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
    @Value("\${rastros.cors.origens}") private val origens: String
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
                // A conexao de avisos (tempo real) termina num despacho assincrono, que ja foi
                // autorizado quando a conexao abriu.
                it.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC).permitAll()
                it.requestMatchers("/api/auth/login", "/api/health", "/api/empresa", "/h2-console/**").permitAll()
                it.requestMatchers("/api/auth/**", "/api/setores/**").authenticated()

                it.requestMatchers("/api/admin/usuarios/**").hasAnyRole(ADMIN, FINANCEIRO)
                it.requestMatchers("/api/admin/**").hasRole(ADMIN)

                // Setores: receber, devolver e despachar a OS do proprio setor. O Financeiro e um
                // setor tambem - recebe as OS e e o unico que conclui.
                it.requestMatchers("/api/movimentacao/**").hasAnyRole(OPERACIONAL, FINANCEIRO)
                it.requestMatchers(HttpMethod.POST, "/api/fluxos/*/receber", "/api/fluxos/*/devolver")
                    .hasAnyRole(OPERACIONAL, FINANCEIRO)
                it.requestMatchers(HttpMethod.POST, "/api/fluxos/*/concluir").hasRole(FINANCEIRO)
                // Daqui em diante vale a SECAO de cada pessoa: o perfil traz as dele prontas e o
                // cadastro acrescenta ou tira (SecaoDoSistema).
                // Despachar tambem e do comercial: a saida da Prateleira/Patio para o Financeiro (RN05).
                it.requestMatchers(HttpMethod.POST, "/api/fluxos/*/despachar")
                    .hasAnyAuthority(*papeis(OPERACIONAL, FINANCEIRO), SECAO_PATIO_PRATELEIRA)

                // A agenda: adesivadores, status e vendedores (a legenda); o resto dela.
                it.requestMatchers(HttpMethod.GET, "/api/status-agenda", "/api/vendedores").authenticated()
                it.requestMatchers("/api/status-agenda", "/api/status-agenda/**", "/api/vendedores", "/api/vendedores/**")
                    .hasAuthority(SECAO_AGENDA_LEGENDA)
                it.requestMatchers(HttpMethod.GET, "/api/agenda/por-os/**")
                    .hasAnyAuthority(SECAO_AGENDA_VER, SECAO_CONSULTA)
                it.requestMatchers(HttpMethod.GET, "/api/agenda/**").hasAuthority(SECAO_AGENDA_VER)
                it.requestMatchers("/api/agenda/colunas", "/api/agenda/colunas/**").hasAuthority(SECAO_AGENDA_LEGENDA)
                it.requestMatchers("/api/agenda/**").hasAuthority(SECAO_AGENDA_EDITAR)

                // OS e fluxos: ver (consulta, e quem precisa delas na agenda ou no patio); abrir,
                // acrescentar fluxo e cancelar.
                it.requestMatchers(HttpMethod.GET, "/api/ordens/**", "/api/fluxos/**")
                    .hasAnyAuthority(SECAO_CONSULTA, SECAO_CRIAR_OS, SECAO_PATIO_PRATELEIRA, SECAO_AGENDA_VER)
                it.requestMatchers("/api/ordens/**", "/api/fluxos/**").hasAuthority(SECAO_CRIAR_OS)

                // Painel: o completo e as duas telas de TV.
                it.requestMatchers(HttpMethod.GET, "/api/painel/agendas")
                    .hasAnyAuthority(SECAO_PAINEL, SECAO_PAINEL_ADESIVADORES)
                it.requestMatchers(HttpMethod.GET, "/api/painel/acabamento")
                    .hasAnyAuthority(SECAO_PAINEL, SECAO_PAINEL_ACABAMENTO)
                it.requestMatchers(HttpMethod.GET, "/api/painel/clima")
                    .hasAnyAuthority(SECAO_PAINEL, SECAO_PAINEL_ADESIVADORES, SECAO_PAINEL_ACABAMENTO)
                it.requestMatchers(HttpMethod.POST, "/api/painel/projetos/**").hasAuthority(SECAO_PAINEL_ACOES)
                it.requestMatchers("/api/painel", "/api/painel/**").hasAuthority(SECAO_PAINEL)

                // Produtividade e relatorio; o score se lanca no relatorio.
                it.requestMatchers(HttpMethod.GET, "/api/produtividade/**")
                    .hasAnyAuthority(SECAO_PRODUTIVIDADE, SECAO_RELATORIO)
                it.requestMatchers("/api/produtividade/**").hasAuthority(SECAO_RELATORIO)

                // Rota de API fora da lista: ninguem.
                // Cada um ve a propria coluna da agenda; quem nao adesiva recebe 404.
                it.requestMatchers("/api/minha-agenda", "/api/minha-agenda/**").authenticated()
                // Avisos de mudanca em tempo real: qualquer um logado.
                it.requestMatchers("/api/mudancas").authenticated()
                it.requestMatchers("/api/**").denyAll()
                // A tela (index.html, js, css) e publica: quem protege os dados e a API.
                it.anyRequest().permitAll()
            }
            .exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }

    private companion object {
        fun papeis(vararg perfis: String) = perfis.map { "ROLE_$it" }.toTypedArray()
        const val SECAO_PAINEL = "SECAO_PAINEL"
        const val SECAO_PAINEL_ADESIVADORES = "SECAO_PAINEL_ADESIVADORES"
        const val SECAO_PAINEL_ACABAMENTO = "SECAO_PAINEL_ACABAMENTO"
        const val SECAO_AGENDA_VER = "SECAO_AGENDA_VER"
        const val SECAO_AGENDA_EDITAR = "SECAO_AGENDA_EDITAR"
        const val SECAO_AGENDA_LEGENDA = "SECAO_AGENDA_LEGENDA"
        const val SECAO_PATIO_PRATELEIRA = "SECAO_PATIO_PRATELEIRA"
        const val SECAO_CONSULTA = "SECAO_CONSULTA"
        const val SECAO_CRIAR_OS = "SECAO_CRIAR_OS"
        const val SECAO_RELATORIO = "SECAO_RELATORIO"
        const val SECAO_PRODUTIVIDADE = "SECAO_PRODUTIVIDADE"
        const val SECAO_PAINEL_ACOES = "SECAO_PAINEL_ACOES"
        const val ADMIN = "ADMIN"
        const val DIRETORIA = "DIRETORIA"
        const val VENDEDOR = "VENDEDOR"
        const val FINANCEIRO = "FINANCEIRO"
        const val OPERACIONAL = "OPERACIONAL"
    }
}
