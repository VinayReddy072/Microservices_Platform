# ADR 003 — Resilience and Observability Choices

## Status
Accepted

## Context

Loan Service calls Inventory Service synchronously via Feign before approving a loan. This call is on the critical path — a network failure or inventory-service restart would block every loan approval indefinitely if no resilience pattern were applied. Additionally, debugging across five processes requires distributed traces that survive service boundaries, including the asynchronous RabbitMQ boundary.

## Decision

### Resilience stack

A four-layer stack is applied specifically to `InventoryAvailabilityAdapter.checkAvailability()`, not to the Feign interface itself (Resilience4J annotations require a Spring proxy, which Feign interfaces don't receive):

1. **Feign connection timeout** — configured in `loan-service`'s config file.
2. **`@Retry(name = "inventoryCheck", fallbackMethod = "...")`** — 3 attempts, restricted to `IOException` and Feign's `RetryableException` to avoid retrying 4xx client errors.
3. **`@CircuitBreaker(name = "inventoryCheck", fallbackMethod = "availabilityFallback")`** — opens after the configured `failure-rate-threshold` percentage of calls fail within the sliding window.
4. **Fallback `availabilityFallback()`** — returns a provisional "available" response and logs a `WARN`. The loan is approved with a warning rather than blocked. This is the deliberate tradeoff: in an emergency equipment context, blocking all loans because the inventory service is temporarily unreachable is worse than occasionally approving a loan against an item that may already be on loan.

### Distributed tracing

OpenTelemetry is used via `micrometer-tracing-bridge-otel` with the `opentelemetry-exporter-zipkin` runtime dependency. Sampling is set to `1.0` (100%) in `config-repo/application.yml`. The logging pattern surfaces `traceId` and `spanId` from the MDC on every log line.

**Synchronous spans** (gateway → loan-service → Feign call to inventory-service) link into a single trace automatically. The `traceparent` header is propagated by Micrometer's OTel bridge across HTTP calls.

**Async span propagation (RabbitMQ)** — `spring.rabbitmq.template.observation-enabled: true` causes loan-service to write trace context into message headers when publishing. `spring.rabbitmq.listener.simple.observation-enabled: true` causes inventory-service's listener container to extract that context and start a child span. In testing, this creates a linked but separate trace in Zipkin rather than a fully connected single trace. Zipkin displays the `loan-service` publish span and the `inventory-service` listener span with a parent-child relationship visible under "Show all spans," but they appear as a linked child trace rather than inline in the root trace timeline.

This is a known behaviour of Spring AMQP's observation support in Spring Boot 4.x: the listener container creates an `OTEL_LINK` rather than a strict parent-child `OTEL_SPAN` when the message was published by a different process. The result is accurate but visually separate. A production fix would involve extracting the `traceparent` header explicitly in `LoanEventListener` and calling `Tracer.startSpan()` with the extracted context as parent — this would force the listener span into the same trace timeline. For the purposes of this assignment demonstration, the linked-trace result is documented honestly here rather than obscured.

## Alternatives Considered

**No fallback — fail fast** — rejected because loan approval is a time-critical operation. A transient inventory-service outage should not prevent all equipment from being lent.

**Bulkhead isolation** — not applied at this stage. All Feign calls share the same thread pool. Bulkhead would be appropriate if inventory-service calls were expected to saturate the loan-service thread pool.

**Jaeger instead of Zipkin** — both accept OTLP. Zipkin was chosen because its Docker image (`openzipkin/zipkin`) requires no configuration file and starts with a single `docker run` command, making it easier to reproduce in a marker's environment.

## Implementation Evidence

- `services/loan-service/src/.../client/InventoryAvailabilityAdapter.java` — `@Retry` + `@CircuitBreaker` on `checkAvailability()`, fallback `availabilityFallback()`.
- `config-repo/application.yml` — `management.tracing.sampling.probability: 1.0`, `spring.rabbitmq.*.observation-enabled`.
- `services/*/config/TracingConfig.java` — manual `OpenTelemetrySdk` + `ZipkinSpanExporter` + `OtelTracer` bean wiring (Spring Boot 4.x does not auto-configure the OTel bridge from `spring-boot-starter-actuator` alone; `micrometer-tracing-bridge-otel:1.7.0` and `opentelemetry-exporter-zipkin:1.62.0` are managed via Spring Boot BOM).
- Zipkin UI at http://localhost:9411 — shows api-gateway, loan-service, and inventory-service spans; RabbitMQ listener spans appear as linked child traces.

