package com.emergencylending.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Reactive WebFilter that enforces JWT authentication on every route except /auth/login.
 *
 * <p>Runs at {@code HIGHEST_PRECEDENCE + 1} — after {@link com.emergencylending.gateway.filter.CorrelationIdFilter}
 * (which is at {@code HIGHEST_PRECEDENCE}) and before Spring Security's WebFilterChainProxy
 * (which is at order -100). This ordering ensures the correlation-ID header is present on
 * every response, including rejected 401/403 responses produced by this filter.
 *
 * <p>Role rules for /api/** routes:
 * <ul>
 *   <li>GET, HEAD, OPTIONS — any valid token (USER or ADMIN) is accepted.</li>
 *   <li>POST, PUT, DELETE  — ADMIN role required; USER-role tokens get 403.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final List<String> READ_METHODS = List.of("GET", "HEAD", "OPTIONS");

    private final JwtUtil jwtUtil;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        if (path.startsWith("/auth/login")) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(AUTH_HEADER);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            log.debug("Request to {} rejected — no Bearer token", path);
            return shortCircuit(exchange, HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        String token = authHeader.substring(BEARER_PREFIX.length());
        Claims claims;
        try {
            claims = jwtUtil.parseToken(token);
        } catch (JwtException ex) {
            log.debug("Request to {} rejected — invalid token: {}", path, ex.getMessage());
            return shortCircuit(exchange, HttpStatus.UNAUTHORIZED, "Invalid or expired token");
        }

        String role = claims.get("role", String.class);
        String method = exchange.getRequest().getMethod().name();

        if (path.startsWith("/api/") && !READ_METHODS.contains(method) && !"ADMIN".equals(role)) {
            log.debug("Request {} {} rejected — ADMIN role required, token has role={}",
                    method, path, role);
            return shortCircuit(exchange, HttpStatus.FORBIDDEN,
                    "ADMIN role required for " + method + " operations");
        }

        return chain.filter(exchange);
    }

    private Mono<Void> shortCircuit(ServerWebExchange exchange, HttpStatus status, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"error\":\"" + message + "\"}";
        DataBuffer buffer = exchange.getResponse().bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
