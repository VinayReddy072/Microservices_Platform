package com.emergencylending.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Adds X-Correlation-Id to every request and response, generating a UUID if none arrives
 * in the incoming request headers.
 *
 * <p>Implemented as a {@link WebFilter} at {@code HIGHEST_PRECEDENCE} rather than a
 * Spring Cloud Gateway {@code GlobalFilter} so that it runs for ALL requests — including
 * those rejected by {@link com.emergencylending.gateway.security.JwtAuthenticationFilter}
 * with a 401 or 403 before the gateway routing pipeline even starts. A GlobalFilter
 * would only run inside the gateway's routing pipeline, which is skipped for rejected requests.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {

        String correlationIdInput = exchange.getRequest()
                .getHeaders()
                .getFirst(CORRELATION_ID_HEADER);

        final String correlationId = (correlationIdInput == null || correlationIdInput.isBlank())
                ? UUID.randomUUID().toString()
                : correlationIdInput;

        ServerHttpRequest mutatedRequest = exchange.getRequest()
                .mutate()
                .header(CORRELATION_ID_HEADER, correlationId)
                .build();

        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(mutatedRequest)
                .build();

        mutatedExchange.getResponse().beforeCommit(() -> {
            mutatedExchange.getResponse()
                    .getHeaders()
                    .set(CORRELATION_ID_HEADER, correlationId);
            return Mono.empty();
        });

        log.info("Incoming request: {} {} correlationId={}",
                mutatedRequest.getMethod(),
                mutatedRequest.getURI().getPath(),
                correlationId);

        return chain.filter(mutatedExchange);
    }
}