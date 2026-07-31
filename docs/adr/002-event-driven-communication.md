# ADR 002 — Asynchronous Event-Driven Communication via RabbitMQ

## Status
Accepted

## Context

When a loan is approved, two things must happen: the loan record is saved as `APPROVED`, and the associated equipment item's status changes from `AVAILABLE` to `ON_LOAN`. When a loan is returned, the equipment status must revert to `AVAILABLE`.

The critical design question is: should the equipment status update be part of the same HTTP request that approves the loan, or should it happen asynchronously?

There is already one synchronous HTTP call on the approval path — the Feign call to `GET /equipment/{id}/availability` on Inventory Service. That call exists precisely because the outcome (available or not) must be known before the loan approval decision can be made. If the equipment is already on loan, the loan must be rejected in the same request. That decision cannot be deferred.

The status update after approval is structurally different. Its result does not affect the loan approval response — the decision is already committed. Making the loan approval wait for a second synchronous HTTP call to Inventory Service means:

1. An Inventory Service outage blocks all loan approvals, even though the equipment was already confirmed available moments before.
2. Two synchronous HTTP calls in sequence double the failure surface on the critical approval path.
3. Inventory Service becomes a blocker for two distinct loan approval concerns (read: availability check; write: status update) rather than one.

The key distinction is **same-request answer vs. side effect**:

| Interaction | Must be same-request? | Rationale |
|-------------|----------------------|-----------|
| `GET /equipment/{id}/availability` (Feign) | **Yes** | The availability result determines whether the loan is approved or rejected. Cannot defer. |
| Equipment status change (AVAILABLE → ON_LOAN) | **No** | The loan is already approved. The status update is a side effect — it updates inventory's view of the world, but the loan outcome does not depend on it. |

## Decision

Equipment status changes are communicated asynchronously via a RabbitMQ topic exchange. Loan Service publishes domain events after committing the loan state change. Inventory Service listens and updates its own database in response.

### Infrastructure — Exact Names

| Element | Name |
|---------|------|
| Exchange | `loan.events` (TopicExchange, durable) |
| Queue | `inventory.loan-events` (durable, bound to exchange) |
| Routing key — approval | `loan.approved` |
| Routing key — return | `loan.returned` |
| Consumer queue | `inventory.loan-events` (single consumer: `LoanEventListener`) |

**Binding configuration (`services/inventory-service/.../config/RabbitMqConfig.java`):**

```java
@Bean
public TopicExchange loanEventsExchange() {
    return new TopicExchange("loan.events", true, false);
}

@Bean
public Queue inventoryLoanEventsQueue() {
    return QueueBuilder.durable("inventory.loan-events").build();
}

@Bean
public Binding approvedBinding(Queue inventoryLoanEventsQueue, TopicExchange loanEventsExchange) {
    return BindingBuilder.bind(inventoryLoanEventsQueue).to(loanEventsExchange).with("loan.approved");
}

@Bean
public Binding returnedBinding(Queue inventoryLoanEventsQueue, TopicExchange loanEventsExchange) {
    return BindingBuilder.bind(inventoryLoanEventsQueue).to(loanEventsExchange).with("loan.returned");
}
```

**Publisher (`services/loan-service/.../messaging/LoanEventPublisher.java`):**

```java
rabbitTemplate.convertAndSend("loan.events", "loan.approved",
    new LoanApprovedEvent(loan.getId(), loan.getEquipmentItemId(), loan.getBorrowerName()));
```

**Consumer (`services/inventory-service/.../messaging/LoanEventListener.java`):**

```java
@RabbitListener(queues = "inventory.loan-events")
public void handleLoanEvent(Message message) {
    String routingKey = message.getMessageProperties().getReceivedRoutingKey();
    if ("loan.approved".equals(routingKey)) {
        // update equipment status → ON_LOAN
    } else if ("loan.returned".equals(routingKey)) {
        // update equipment status → AVAILABLE
    }
}
```

### Schema Independence

The two services share zero code. Each holds its own copy of the event record with matching JSON field names. `LoanEventListener` reads the raw AMQP `Message` and uses `ObjectMapper` directly — bypassing the `Jackson2JsonMessageConverter` trusted-package check that would fail on the loan-service class name in the `__TypeId__` header.

### Live Evidence from the Database

Equipment status changes driven entirely by RabbitMQ — no REST call to `PUT /equipment/{id}/status` exists anywhere in the codebase:

| Equipment Item | Status | Driven by |
|---------------|--------|-----------|
| 1 — Portable Defibrillator | ON_LOAN | Loan 1 (Alice Smith, 2026-07-19), loan.approved event |
| 2 — Oxygen Cylinder | ON_LOAN | Loan 2 (John Doe, 2026-07-20), loan.approved event |
| 4 — EMS Resuscitation Kit | ON_LOAN | Loan 3 (2026-07-27), loan.approved event |
| 6 — AED Unit | ON_LOAN | Loan 6 (Trace Tester, 2026-07-28), loan.approved event |
| 3, 5 | AVAILABLE | Never loaned, or loan rejected |

## Alternatives Considered

**Synchronous `PUT /equipment/{id}/status`** — rejected. The approval HTTP response would block until Inventory Service acknowledges the status update. An inventory-service restart during peak demand blocks all loan approvals for the restart duration. Two synchronous calls in sequence also means two independent failure points on the same critical path.

**Shared event library JAR** — a common `LoanApprovedEvent` class shared between services. Rejected because it creates compile-time coupling: both services must be rebuilt and redeployed simultaneously whenever the event schema changes. Schema independence is preserved by each service defining its own record with matching JSON field names.

**Spring Cloud Stream** — higher-level abstraction over RabbitMQ/Kafka. Evaluated but not chosen: for two services and two event types, using `spring-amqp` directly makes the exchange name, routing key, and queue name explicit in code and configuration. Spring Cloud Stream's functional model would hide this detail without adding value at this scale.

**Outbox pattern** — would guarantee delivery by storing the event in the same transaction as the loan record before publishing to RabbitMQ. Not applied: RabbitMQ's durable queue buffers messages across Inventory Service restarts, and the explicit WARN log from the circuit-breaker fallback alerts operations staff. Accepted as sufficient for this scope.

## Consequences

**Positive:** Loan approval latency is not affected by Inventory Service's write availability. Equipment status is guaranteed-delivery via the durable queue — Inventory Service can restart without losing events. Schema independence: neither service can break the other by changing internal model details.

**Negative:** Equipment status is eventually consistent. A `GET /equipment/{id}` immediately after loan approval may briefly return `AVAILABLE` before the listener processes the event. For emergency lending this is accepted — the loan record itself is immediately consistent, and the physical equipment is with the borrower regardless of what the status field shows for 50–200ms.

## Implementation Artefacts

| File | Role |
|------|------|
| `services/inventory-service/.../config/RabbitMqConfig.java` | Declares exchange, queue, two bindings |
| `services/loan-service/.../messaging/LoanEventPublisher.java` | `convertAndSend("loan.events", "loan.approved", ...)` |
| `services/inventory-service/.../messaging/LoanEventListener.java` | `@RabbitListener(queues = "inventory.loan-events")`, routes on `receivedRoutingKey` |
| `config-repo/application.yml` | `spring.rabbitmq.template.observation-enabled: true`, `listener.simple.observation-enabled: true` |

## Report Evidence — Verification Report §9

- Full event flow with log evidence: `Equipment 6 → ON_LOAN (loan request 6)`
- Live equipment table showing items 1, 2, 4, 6 as ON_LOAN — state driven by RabbitMQ, confirmed by absence of any REST PUT to inventory

## Screencast Timestamps

- `[00:XX:XX]` — equipment item 6 at AVAILABLE before approval
- `[00:XX:XX]` — approve loan 6; inventory-service log: `Equipment 6 → ON_LOAN`
- `[00:XX:XX]` — RabbitMQ Management UI showing `loan.events` exchange with two bindings
