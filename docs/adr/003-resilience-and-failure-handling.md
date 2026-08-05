# ADR 003 — Resilience, Failure Handling, and Distributed Tracing

## Status
Accepted

## Context

### Resilience
Loan Service calls Inventory Service synchronously via OpenFeign on the critical approval path. Without a resilience pattern, a TCP connection timeout, a GC pause, or an Inventory Service restart would block the approving thread for the full read-timeout duration (5 s) on every concurrent request, saturating the thread pool.

### Tracing
The platform routes every client request through five processes. When a request fails or performs poorly, determining which service is responsible without distributed tracing requires manually grepping five separate terminal windows. A trace system assigns a `traceId` at the Gateway, propagates it through every downstream call, and presents the full request journey as a waterfall in a single UI.

## Decision

### Part 1 - OpenFeign + Resilience4J Stack

The synchronous Inventory Service call is wrapped in a four-layer resilience stack inside `InventoryAvailabilityAdapter`, a concrete Spring `@Component` bean. The layers cannot be applied directly to the Feign interface because Resilience4J annotations require a Spring AOP proxy on a concrete bean, not on an interface.

**Exact configuration values (`services/loan-service/src/main/resources/application.yml`):**

```yaml
feign:
  client:
    config:
      inventory-service:
        connect-timeout: 3000      # Layer 1: 3 s TCP connect timeout
        read-timeout: 5000         # Layer 1: 5 s response read timeout
        logger-level: FULL

resilience4j:
  retry:
    instances:
      inventoryService:
        max-attempts: 3            # Layer 2: up to 3 attempts
        wait-duration: 500ms
        retry-exceptions:
          - java.io.IOException
          - feign.RetryableException
  circuitbreaker:
    instances:
      inventoryService:
        sliding-window-size: 5     # Layer 3: 5-call sliding window
        failure-rate-threshold: 50 # trips at 50% failure rate
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 2
```

**Layer 4 - Fallback (the critical business decision):**

```java
// services/loan-service/.../client/InventoryAvailabilityAdapter.java
@Retry(name = "inventoryService")
@CircuitBreaker(name = "inventoryService", fallbackMethod = "checkAvailabilityFallback")
public EquipmentAvailabilityDto checkAvailability(Long equipmentItemId) {
    return inventoryClient.checkAvailability(equipmentItemId);
}

public EquipmentAvailabilityDto checkAvailabilityFallback(Long equipmentItemId, Throwable t) {
    log.warn("inventory-service unreachable for equipmentId={}; provisionally approving. Cause: {}",
             equipmentItemId, t.getMessage());
    return new EquipmentAvailabilityDto(equipmentItemId, true, "UNKNOWN_FALLBACK");
}
```

**Fallback rationale:** The fallback returns provisional `available=true` — the loan is approved rather than blocked. In an emergency equipment lending context, blocking all loan approvals because Inventory Service is restarting for 30 seconds is operationally worse than occasionally approving a loan whose equipment status cannot be confirmed at that instant. Operations staff are alerted by the `WARN` log. This is a deliberate business tradeoff, not an oversight.

Note on retry scope: `@Retry` retries only on `IOException` and `RetryableException` (transport-level failures). Business responses — such as Inventory Service correctly returning `available=false` for an `ON_LOAN` item — are not retried. **Loan 5 in the live data (`status: REJECTED`) is evidence of this:** the Feign call succeeded, returned `available=false` for item 1 (already on loan), and the loan was correctly rejected without the fallback firing.

### Part 2 - Distributed Tracing with OpenTelemetry and Zipkin

**Why manual configuration is required:** Spring Boot 4.x does not auto-configure the Micrometer OTel bridge from `spring-boot-starter-actuator` alone. `TracingConfig.java` (present in api-gateway, loan-service, inventory-service) manually wires `OpenTelemetrySdk` → `ZipkinSpanExporter` → `OtelTracer` → `DefaultTracingObservationHandler`. This is the correct and fully supported approach for Spring Boot 4.x.

**Zipkin as the backend:** `openzipkin/zipkin` Docker image starts with a single `docker run` command and requires zero configuration, making it straightforward to reproduce.

**Configuration (`config-repo/application.yml`):**

```yaml
management:
  tracing:
    sampling:
      probability: 1.0
  zipkin:
    tracing:
      endpoint: http://localhost:9411/api/v2/spans
spring:
  rabbitmq:
    template:
      observation-enabled: true
    listener:
      simple:
        observation-enabled: true

logging:
  pattern:
    level: "%5p [${spring.application.name},%X{traceId:-},%X{spanId:-}]"
```

**Confirmed service registration:**
```bash
curl http://localhost:9411/api/v2/services
["api-gateway","inventory-service","loan-service"]
```

**Synchronous span propagation** — `traceparent` W3C header propagated automatically across HTTP hops:

```
api-gateway: http put (535 ms, 6 spans)
  └─ loan-service: http put /loans/{id}/approve (513 ms)
       ├─ inventory-service: GET /equipment/6/availability  ← Feign call
       ├─ loan-service: JPA commit
       └─ loan-service: RabbitMQ publish loan.approved
```

**Async tracing — documented finding:** The RabbitMQ consumer span in Inventory Service (`LoanEventListener`) appears as a **linked child trace** in Zipkin rather than as an inline child span within the root trace timeline. This is a known behaviour of Spring AMQP's observation implementation in Spring Boot 4.x: the listener container creates an OTel `LINK` to the publishing span, not a strict parent-child relationship. The linked trace is visible in Zipkin under the `inventory-service` service filter. This is not a configuration error — the trace context is correctly propagated through AMQP message headers (`observation-enabled: true` on both publisher and listener). Making the async span appear inline would require the `LoanEventListener` to manually extract the `traceparent` header and call `Tracer.startSpan()` with the extracted context as parent. This is a known production-hardening step and is a documented finding, not a defect.

**Cross-service log correlation** — same `traceId` in MDC across services:
```
INFO [loan-service,4f2e1a3b9c7d8e0f,2a1b3c4d] LoanEventPublisher - Published LoanApprovedEvent for loanId=6
INFO [inventory-service,4f2e1a3b9c7d8e0f,9b8c7d6e] LoanEventListener - Equipment 6 → ON_LOAN
```

## Alternatives Considered

**No fallback - fail fast:** The circuit breaker would propagate a `CallNotPermittedException` as HTTP 500 to the client. Every loan approval attempt during an Inventory Service outage would fail. Rejected: in emergency equipment lending, a transient outage should not freeze loan approvals.

**Bulkhead isolation:** Would isolate the Feign thread pool so inventory-service failures cannot saturate the loan-service thread pool. Not applied at this stage - unnecessary at current scale but documented as a future hardening step.

**Jaeger instead of Zipkin:** Jaeger is more feature-rich (OTLP native, richer storage options). Rejected for this context because Zipkin requires zero configuration to start.

**Spring Cloud Sleuth:** Reached end-of-life with Spring Boot 3.x. Unavailable for Spring Boot 4.1.0. Not considered.

**Letting the async listener span appear only as a link (no production hardening):** The decision is to document this as a finding rather than implement inline parent-child span construction in the listener. The linked trace is fully visible in Zipkin and correctly identified as belonging to the same logical operation via the shared `traceId`. Hardening it to an inline span would require non-trivial listener instrumentation that adds complexity beyond the assignment scope.

## Consequences

**Positive:** Loan approval is resilient to transient Inventory Service outages. Actuator exposes real-time circuit-breaker state (`/actuator/circuitbreakers`). End-to-end traces visible in Zipkin for all HTTP hops. `traceId`/`spanId` in every log line enables cross-service correlation without Zipkin.

**Negative:** Fallback provisional approvals can lead to equipment double-booking during sustained outages — mitigated by WARN log alerting. Async listener span appears as a linked child trace rather than inline — documented and accepted. `ZipkinSpanExporter` is deprecated in OTel Java 1.x in favour of OTLP — suppressed with `@SuppressWarnings("deprecation")`, functional for Zipkin v2, documented in `TracingConfig.java`.

## Implementation Artefacts

| File | Role |
|------|------|
| `services/loan-service/.../client/InventoryAvailabilityAdapter.java` | `@Retry` + `@CircuitBreaker` + fallback method |
| `services/loan-service/.../client/InventoryClient.java` | `@FeignClient(name = "inventory-service")` — `GET /equipment/{id}/availability` |
| `services/loan-service/src/main/resources/application.yml` | Feign timeouts (3s/5s), Resilience4J values (window=5, threshold=50%, open=10s) |
| `services/*/config/TracingConfig.java` | Manual OTel SDK wiring — `OpenTelemetrySdk`, `ZipkinSpanExporter`, `OtelTracer`, `DefaultTracingObservationHandler` |
| `config-repo/application.yml` | `sampling.probability: 1.0`, `zipkin.endpoint`, `rabbitmq.*.observation-enabled: true`, log pattern with `traceId`/`spanId` |

## Report Evidence

- Normal path: loan 8 approved via live Feign call, `available=true`
- Loan 5 REJECTED: Feign returned `available=false` for ON_LOAN item 1 — confirms `@Retry` does not retry business responses
- Fallback path: circuit-breaker WARN log; actuator showing `state: OPEN`
- Zipkin services: `["api-gateway","inventory-service","loan-service"]`
- Zipkin 6 spans across 3 services for `PUT /api/loans/6/approve`
- Log excerpt with matching `traceId` across loan-service and inventory-service

## Screencast Timestamps

- `[00:16:55]` — Approve with item 5 ON_LOAN → REJECTED (no fallback, correct)
- `[00:18:13]` — Stop inventory-service → WARN fallback log → actuator circuitbreakers → OPEN
- `[00:21:56]` — Zipkin Run Query → showing gateway

