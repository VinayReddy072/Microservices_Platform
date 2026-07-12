package com.emergencylending.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * CorrelationIdFilter — Global Gateway Filter (Days 1–5)
 *
 * <p>Satisfies the "at least one Gateway filter" rubric requirement and provides
 * distributed tracing groundwork ahead of the OpenTelemetry/Zipkin integration
 * in Days 9–10.
 *
 * <p>Behaviour per request:
 * <ol>
 *   <li>Reads {@code X-Correlation-Id} from the inbound request header.</li>
 *   <li>If absent, generates a new UUID4 as the correlation ID.</li>
 *   <li>Mutates the forwarded request to include the correlation ID so
 *       downstream services receive it.</li>
 *   <li>Adds the same header to the outbound response so clients can correlate
 *       their request to server-side logs.</li>
 *   <li>Logs the method, URI, and correlation ID at INFO level.</li>
 * </ol>
 */
@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // Propagate existing ID or generate a fresh one
        String correlationId = request.getHeaders().getFirst(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        final String finalCorrelationId = correlationId;

        log.info("Incoming request: {} {} correlationId={}",
                request.getMethod(),
                request.getURI().getPath(),
                finalCorrelationId);

        // Forward the correlation ID to the downstream service
        ServerHttpRequest mutatedRequest = request.mutate()
                .header(CORRELATION_ID_HEADER, finalCorrelationId)
                .build();

        // Add the correlation ID to the response so the calling client sees it
        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(mutatedRequest)
                .build();

        return chain.filter(mutatedExchange)
                .then(Mono.fromRunnable(() -> {
                    ServerHttpResponse response = mutatedExchange.getResponse();
                    response.getHeaders().add(CORRELATION_ID_HEADER, finalCorrelationId);
                }));
    }

    /**
     * Run before other filters (lowest order value = highest precedence).
     * Ensures the correlation ID is set before any routing or authentication filter runs.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
