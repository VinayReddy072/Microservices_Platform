package com.emergencylending.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

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

        // Add the response header BEFORE the response is committed
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

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}