# ADR 002 — Event-Driven Communication for Equipment Status Updates

## Status
Accepted

## Context

When a loan is approved, the Loan Service needs to change the associated equipment item's status from `AVAILABLE` to `ON_LOAN`. Two architectural options exist: call the Inventory Service synchronously over HTTP, or publish an event and let Inventory Service react asynchronously.

A synchronous Feign call already exists in this codebase — `InventoryAvailabilityAdapter` calls `GET /equipment/{id}/availability` before approving a loan. That call is intentionally synchronous: the outcome of the availability check (is this equipment actually loanable right now?) must be known before the loan can be approved, and a provisional result in the fallback path is a deliberately designed degraded mode, not the normal flow.

The status update after approval is a different kind of interaction. Its result does not affect the loan approval response. Making the loan approval wait for a second HTTP call to inventory-service means a transient inventory-service outage would block the entire loan approval, even though the equipment was already confirmed available moments before.

## Decision

Equipment status changes (`AVAILABLE → ON_LOAN` on approval, `ON_LOAN → AVAILABLE` on return) are communicated via a RabbitMQ topic exchange named `loan.events`. Loan Service publishes to routing keys `loan.approved` and `loan.returned`. Inventory Service binds a durable queue `inventory.loan-events` to both routing keys and processes events via `LoanEventListener`.

This means the two services share zero code — each has its own independent copy of the event record (`com.emergencylending.loan.event.LoanApprovedEvent` and `com.emergencylending.inventory.event.LoanApprovedEvent`) with matching JSON field names. The `LoanEventListener` receives the raw AMQP `Message` and uses `ObjectMapper` directly, bypassing the `Jackson2JsonMessageConverter` trusted-package check that would fail when it encounters the loan-service class name in the `__TypeId__` header.

## Alternatives Considered

**Synchronous PUT to `/equipment/{id}/status`** — rejected because it tightly couples loan approval latency to inventory-service availability. The Feign/Resilience4J stack already handles the pre-approval check; adding a second synchronous call creates two failure points instead of one.

**Shared event library (common JAR)** — rejected because it creates a compile-time coupling between independently deployable services. If the event schema changes, both services must be rebuilt and redeployed simultaneously.

## Consequences

**Positive:** Loan approval succeeds even if inventory-service is momentarily down at the moment of publishing (RabbitMQ buffers the message). Status updates are eventual but guaranteed — the durable queue retains messages across inventory-service restarts.

**Negative:** Equipment status is eventually consistent, not immediately consistent. A GET on `/equipment/{id}` immediately after loan approval may still show `AVAILABLE` for the few milliseconds before the listener processes the event. This is acceptable for this domain (equipment lending does not require sub-millisecond consistency).

## Implementation Evidence

- `services/loan-service/src/.../messaging/LoanEventPublisher.java` — publishes to `loan.events`.
- `services/inventory-service/src/.../messaging/LoanEventListener.java` — `@RabbitListener(queues = "inventory.loan-events")`.
- `services/inventory-service/src/.../config/RabbitMqConfig.java` — declares queue, exchange, and two bindings; no `MessageConverter` on the listener factory.
- `config-repo/application.yml` — `spring.rabbitmq.template.observation-enabled: true` and `spring.rabbitmq.listener.simple.observation-enabled: true`.
