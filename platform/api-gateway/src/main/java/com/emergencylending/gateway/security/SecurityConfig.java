package com.emergencylending.gateway.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Disables Spring Security's default reactive behaviours (form login, HTTP Basic,
 * CSRF, and the "block all unauthenticated requests" rule) so that our custom
 * {@link JwtAuthenticationFilter} is the sole access-control mechanism.
 *
 * <p>Without this bean, Spring Security's auto-configuration would add a second
 * authentication layer that conflicts with the JWT filter and converts 401 responses
 * into 302 redirects to /login.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .build();
    }
}
