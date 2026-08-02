# Emergency Equipment Lending Platform
## Technical Report - Microservices Architecture Assignment

- **Student Name:** Thalla Vinay Reddy
- **Student ID:** 22FA081016
- **Module:** Microservices Architecture 
- **Repository:** `https://github.com/VinayReddy072/Microservices_Platform`  
- **Stack:** Spring Boot 4.1.0 · Spring Cloud 2025.1.2 · Java 25 · MySQL 8 · RabbitMQ 3 · OpenTelemetry + Zipkin

---

## Table of Contents

1. [Application Overview](#1-application-overview)
2. [Architecture Overview](#2-architecture-overview)
3. [Implementation Evidence](#3-implementation-evidence)
   - 3.1 REST APIs
   - 3.2 Gateway and Service Discovery
   - 3.3 Configuration Management
   - 3.4 Security
   - 3.5 Service-to-Service Communication and Resilience
   - 3.6 Asynchronous Messaging
   - 3.7 Observability and Distributed Tracing
4. [Architecture Decision Records](#4-architecture-decision-records)
5. [Screencast Cross-Reference Table](#5-screencast-cross-reference-table)

---

## 1. Application Overview

### 1.1 Problem Statement

Emergency medical services operate in high-stakes environments where the correct equipment -defibrillators, oxygen cylinders, resuscitation kits - must be allocated, dispatched, and returned in a timely and accountable manner. Manual logbooks and ad hoc tracking create risks of double-allocation (the same item issued to two responders simultaneously), lost equipment, and delayed responses when assets are urgently needed.

The **Emergency Equipment Lending Platform** automates the lifecycle of emergency medical asset allocation: from request submission and real-time availability checking, through approval or rejection, to equipment return. The system maintains an authoritative catalogue of equipment items and a tamper-evident record of every lending transaction.

### 1.2 Service Responsibilities

**loan-service (Port 8081 - Service A):**
Manages the loan request lifecycle. Accepts new requests, performs a synchronous availability check against inventory-service before approving, enforces the state machine (`PENDING → APPROVED / REJECTED → RETURNED`), and publishes domain events to RabbitMQ after state transitions.

**inventory-service (Port 8082 - Service B):**
Manages the equipment catalogue with full CRUD. Exposes a dedicated availability endpoint consumed synchronously by loan-service. Consumes RabbitMQ events to update equipment status asynchronously. Does not initiate calls to loan-service.

### 1.3 Entity Relationship

| Entity | Service | Table | Key Fields |
| :--- | :--- | :--- | :--- |
| `EquipmentItem` | inventory-service | `inventory_db.equipment_items` | `id`, `name`, `category`, `status` (AVAILABLE / ON_LOAN), `location`, `conditionNotes` |
| `LoanRequest` | loan-service | `loan_db.loan_requests` | `id`, `equipmentItemId` (logical ref), `borrowerName`, `borrowerContact`, `status` (PENDING / APPROVED / REJECTED / RETURNED), `requestedAt`, `approvedAt`, `returnedAt` |

`LoanRequest.equipmentItemId` is a logical reference - a plain integer. There is no JPA `@JoinColumn` crossing into `inventory_db`. All cross-service data access uses the REST API (Feign) or RabbitMQ events, never shared SQL.

### 1.4 Domain Originality

This domain was selected because it models a genuine architectural constraint absent from typical academic domains: equipment availability is a real-time property that must be checked synchronously at approval time (the result determines the decision), while the resulting status change is a committed side-effect that must not block the approval response. This creates a natural boundary between synchronous and asynchronous communication. The domain differs from any prior group work i have did, which typically involves hotel bookings, retail orders, or banking.

---

## 2. Architecture Overview

### 2.1 System Architecture Diagram

**Figure 2.1 : System Architecture**

```
┌───────────────────────────────────────────────────────────────────┐
│  Client (HTTP :8080)                                              │
└────────────────────────────┬──────────────────────────────────────┘
                             │
                    ┌────────▼────────┐
                    │  API Gateway    │ :8080
                    │  JWT Auth       │ ← RewritePath filter
                    │  CorrelationId  │ ← X-Correlation-Id filter
                    └──┬──────────┬──┘
                       │ lb://    │ lb://
           ┌───────────▼─┐   ┌───▼──────────────┐
           │ loan-service│   │inventory-service  │
           │   :8081     │   │     :8082         │
           │  loan_db    │   │  inventory_db     │
           └──┬──────────┘   └────────┬──────────┘
              │ OpenFeign (sync)       │
              │──────────────────────►│ GET /equipment/{id}/availability
              │
              │ RabbitMQ (async)
              │──────────────────────►│ loan.events exchange
                                      │ → inventory.loan-events queue
                                      │ → LoanEventListener

┌──────────────────┐  ┌────────────────────┐  ┌──────────────┐
│  Eureka :8761    │  │ Config Server :8888 │  │ Zipkin :9411 │
│  (all 5 register)│  │ (native FS backend) │  │ (OTel spans) │
└──────────────────┘  └────────────────────┘  └──────────────┘
MySQL :3306  loan_db (loan_user) | inventory_db (inventory_user)
RabbitMQ :5672/:15672  exchange: loan.events | queue: inventory.loan-events
```

*[Insert rendered architecture diagram screenshot here — Figure 2.1]*  
*Screencast timestamp: `00:00–00:50`*

### 2.2 Component Responsibilities

| Component | Port | Responsibility |
| :--- | :--- | :--- |
| API Gateway | 8080 | Sole client entry point; JWT auth; CorrelationId filter; discovery-based routing |
| Eureka Server | 8761 | Service registry — all five processes register and resolve via `lb://` |
| Config Server | 8888 | Centralised YAML configuration; native filesystem backend; dev/production profiles |
| loan-service | 8081 | Loan lifecycle domain service; Feign caller; RabbitMQ producer |
| inventory-service | 8082 | Equipment catalogue; Feign target; RabbitMQ consumer |
| MySQL | 3306 | Two isolated schemas: `loan_db` (`loan_user`) and `inventory_db` (`inventory_user`) |
| RabbitMQ | 15672 | Async event broker; `loan.events` exchange; `inventory.loan-events` queue |
| Zipkin | 9411 | Distributed trace aggregator — receives OTel spans from all three application services |

### 2.3 Communication Patterns

| Pattern | Path | Reason |
| :--- | :--- | :--- |
| Synchronous REST (Feign) | loan-service → inventory-service | Availability result determines approval decision; cannot be deferred |
| Asynchronous Messaging (RabbitMQ) | loan-service → inventory-service | Status update is a committed side-effect; must not block approval response |

---

## 3. Implementation Evidence

*Criterion mapping: Criteria 2–7*

> **Note on screenshots:** All `[Insert Screenshot Here]` markers identify where a labelled screenshot must be placed before final submission. Corresponding screencast timestamps are provided in the cross-reference table (Section 5).

---

### 3.1 REST APIs

*Criterion mapping: Criterion 2 — REST API Implementation (5 marks)*

Both services implement full CRUD using standard HTTP semantics. Input validation uses Jakarta Validation annotations (`@NotBlank`, `@NotNull`). A `GlobalExceptionHandler` formats validation errors as field-level JSON maps.

#### 3.1.1 inventory-service Endpoints

| Operation | Method | Gateway URL | Status Code |
| :--- | :--- | :--- | :--- |
| Create equipment | POST | `/api/equipment` | 201 Created |
| List all | GET | `/api/equipment` | 200 OK |
| Get single | GET | `/api/equipment/{id}` | 200 OK / 404 |
| Update | PUT | `/api/equipment/{id}` | 200 OK |
| Delete | DELETE | `/api/equipment/{id}` | 204 No Content |
| Check availability | GET | `/api/equipment/{id}/availability` | 200 OK (internal Feign use) |

**CREATE — POST /api/equipment → HTTP 201:**
```bash
curl -s -X POST http://localhost:8080/api/equipment \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"AED Unit","category":"Cardiac","location":"Bay 3","conditionNotes":"Ready"}'
```
Response:
```json
{"id":6,"name":"AED Unit","category":"Cardiac","status":"AVAILABLE","location":"Bay 3","conditionNotes":"Ready"}
```

*[Insert Screenshot — Figure 3.1.1: POST /api/equipment returning HTTP 201 with new resource body]*  
*Screencast timestamp: `10:15–10:50`*

**READ — GET /api/equipment → HTTP 200:**
```bash
curl -s http://localhost:8080/api/equipment \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```
Returns all six items; items 1, 2, 4, 6 show `status: ON_LOAN` (driven by RabbitMQ events).

*[Insert Screenshot — Figure 3.1.2: GET /api/equipment returning all 6 equipment items]*  
*Screencast timestamp: `10:50–11:10`*

**UPDATE — PUT /api/equipment/5 → HTTP 200:**
```bash
curl -s -X PUT http://localhost:8080/api/equipment/5 \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Defib Unit","category":"Cardiac","location":"Bay 1","conditionNotes":"Ready"}'
```
Response: `{"id":5,"name":"Defib Unit","category":"Cardiac","status":"AVAILABLE","location":"Bay 1","conditionNotes":"Ready"}`

*[Insert Screenshot — Figure 3.1.3: PUT /api/equipment/5 returning HTTP 200]*  
*Screencast timestamp: `11:10–11:25`*

**DELETE — DELETE /api/equipment/{id} → HTTP 204:**

*[Insert Screenshot — Figure 3.1.4: DELETE returning HTTP 204 No Content]*  
*Screencast timestamp: `11:25–11:35`*

**Validation failure — HTTP 400:**
```json
{"name":"Equipment name must not be blank","category":"Category must not be blank"}
```
*[Insert Screenshot — Figure 3.1.5: POST with missing fields returning HTTP 400 with field-level error map]*  
*Screencast timestamp: `11:35–11:50`*

**Not found — HTTP 404:**
```json
{"message":"EquipmentItem not found: 999"}
```
*Screencast timestamp: `11:50–12:00`*

#### 3.1.2 loan-service Endpoints

| Operation | Method | Gateway URL | Status Code |
| :--- | :--- | :--- | :--- |
| Create loan | POST | `/api/loans` | 201 Created |
| List all | GET | `/api/loans` | 200 OK |
| Get single | GET | `/api/loans/{id}` | 200 OK / 404 |
| Approve loan | PUT | `/api/loans/{id}/approve` | 200 OK |
| Return equipment | PUT | `/api/loans/{id}/return` | 200 OK |
| Delete record | DELETE | `/api/loans/{id}` | 204 No Content |

**CREATE — POST /api/loans → HTTP 201:**
```bash
curl -s -X POST http://localhost:8080/api/loans \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"equipmentItemId":6,"borrowerName":"Trace Tester","borrowerContact":"trace@ems.org"}'
```
Response:
```json
{
  "id": 6,
  "equipmentItemId": 6,
  "borrowerName": "Trace Tester",
  "borrowerContact": "trace@ems.org",
  "status": "PENDING",
  "requestedAt": "2026-07-28T12:55:47.102480Z",
  "approvedAt": null,
  "returnedAt": null
}
```
*[Insert Screenshot — Figure 3.1.6: POST /api/loans returning HTTP 201 with PENDING status]*  
*Screencast timestamp: `12:00–12:20`*

**State machine — HTTP 409 conflict:**
Attempting to approve an already-APPROVED loan returns:
```json
{"message":"Cannot approve loan 1 — current status is APPROVED"}
```
*[Insert Screenshot — Figure 3.1.7: PUT /api/loans/1/approve returning HTTP 409]*  
*Screencast timestamp: `12:45–13:00`*

---

### 3.2 Gateway and Service Discovery

*Criterion mapping: Criterion 3 — Gateway, Config and Service Discovery (10 marks)*

#### 3.2.1 Eureka Service Discovery

All five processes register with Eureka Server (port 8761) at startup.

*[Insert Screenshot — Figure 3.2.1: Eureka dashboard at http://localhost:8761 showing all 5 instances UP]*  
*Screencast timestamp: `04:10–05:10`*

The Eureka dashboard confirms five registered instances:

| Application | Status |
| :--- | :--- |
| API-GATEWAY | UP (1) — :8080 |
| CONFIG-SERVER | UP (1) — :8888 |
| INVENTORY-SERVICE | UP (1) — :8082 |
| LOAN-SERVICE | UP (1) — :8081 |
| EUREKA-SERVER | UP (1) — :8761 |

The gateway uses `lb://` URIs resolved at request time against this registry — no hardcoded IP addresses exist anywhere in the source code.

#### 3.2.2 API Gateway Routing

*[Insert Screenshot — Figure 3.2.2: http://localhost:8080/actuator/gateway/routes showing lb:// URIs and RewritePath filters]*  
*Screencast timestamp: `07:30–08:00`*

Gateway route configuration:

```yaml
# platform/api-gateway/src/main/resources/application.yml
routes:
  - id: inventory-service-route
    uri: lb://inventory-service
    predicates:
      - Path=/api/equipment/**
    filters:
      - RewritePath=/api/equipment(?<segment>/?.*), /equipment${segment}
  - id: loan-service-route
    uri: lb://loan-service
    predicates:
      - Path=/api/loans/**
    filters:
      - RewritePath=/api/loans(?<segment>/?.*), /loans${segment}
```

`GET /api/equipment/3` → forwarded as `GET /equipment/3` to inventory-service. The domain services are unaware they are behind a gateway.

#### 3.2.3 Gateway Filters

Two custom `WebFilter` implementations execute on every request:

**CorrelationIdFilter (`@Order(HIGHEST_PRECEDENCE)`):** Generates a UUID and injects it as `X-Correlation-Id` in both the request and response — including rejected 401/403 responses.

**JwtAuthenticationFilter (`@Order(HIGHEST_PRECEDENCE + 1)`):** Validates the Bearer token and enforces RBAC. Runs one slot after the CorrelationIdFilter to ensure rejected responses still carry the trace ID.

*[Insert Screenshot — Figure 3.2.3: curl -sI response showing X-Correlation-Id header on a gateway response]*  
*Screencast timestamp: `08:30–08:45`*

---

### 3.3 Configuration Management

*Criterion mapping: Criterion 3 — Gateway, Config and Service Discovery (10 marks)*

#### 3.3.1 Spring Cloud Config Server

Config Server (port 8888) uses a native filesystem backend, serving YAML files from `config-repo/`. All five services import configuration at startup:

```yaml
spring:
  config:
    import: "optional:configserver:http://localhost:8888"
```

Config files served:

| File | Scope |
| :--- | :--- |
| `application.yml` | Shared across all services — Eureka URL, RabbitMQ, Zipkin, log pattern |
| `loan-service-dev.yml` | loan-service dev: `ddl-auto=update`, `show-sql=true`, Feign FULL logging |
| `loan-service-production.yml` | loan-service production: `ddl-auto=validate`, `show-sql=false` |
| `inventory-service-dev.yml` | inventory-service dev profile |
| `inventory-service-production.yml` | inventory-service production profile |

#### 3.3.2 Multiple Environment Profiles

*[Insert Screenshot — Figure 3.3.1: curl http://localhost:8888/loan-service/dev showing ddl-auto=update and show-sql=true]*  
*Screencast timestamp: `05:10–06:00`*

*[Insert Screenshot — Figure 3.3.2: curl http://localhost:8888/loan-service/production showing ddl-auto=validate and show-sql=false]*  
*Screencast timestamp: `06:00–06:30`*

The same compiled JAR runs in both profiles. The only difference is the runtime configuration fetched from Config Server at startup.

#### 3.3.3 Sensitive Configuration Externalised

Sensitive values are `${ENV_VAR}` placeholders in the YAML files, resolved from environment variables at runtime:

```yaml
# config-repo/loan-service-dev.yml
spring:
  datasource:
    url: ${LOAN_DB_URL:jdbc:mysql://localhost:3306/loan_db}
    username: ${LOAN_DB_USER:loan_user}
    password: ${LOAN_DB_PASS}
```

The `.env` file containing real values is listed in `.gitignore` and is never committed to the repository.

**Environment variables used:**

| Variable | Purpose |
| :--- | :--- |
| `JWT_SECRET` | HMAC-SHA256 signing key — gateway refuses to start without it |
| `LOAN_DB_URL` / `LOAN_DB_USER` / `LOAN_DB_PASS` | loan-service MySQL credentials |
| `INVENTORY_DB_URL` / `INVENTORY_DB_USER` / `INVENTORY_DB_PASS` | inventory-service MySQL credentials |
| `RABBITMQ_HOST` / `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | RabbitMQ broker credentials |
| `CONFIG_REPO_PATH` | Filesystem path to config-repo/ |

*[Insert Screenshot — Figure 3.3.3: .env file in VS Code showing environment variable placeholders (sensitive values masked or blurred)]*  
*Screencast timestamp: `06:30–07:30`*

---

### 3.4 Security

*Criterion mapping: Criterion 4 — Security (10 marks)*  
*ADR Reference: ADR 1 — Gateway-Centred Security*

#### 3.4.1 Token Issuance

`AuthController` at `POST /auth/login` issues HMAC-SHA256 signed JWTs. Two pre-configured accounts:

| Username | Password | Role |
| :--- | :--- | :--- |
| `admin` | `adminpass` | `ADMIN` — full write access |
| `user` | `password` | `USER` — read-only access |

```bash
curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"adminpass"}'
```
Response:
```json
{"token":"eyJhbGciOiJIUzI1NiJ9..."}
```
Decoded payload: `{"sub":"admin","role":"ADMIN","iat":1753696000,"exp":1753699600}`

*[Insert Screenshot — Figure 3.4.1: POST /auth/login returning JWT token for admin credentials]*  
*Screencast timestamp: `08:45–09:20`*

#### 3.4.2 Role-Based Access Control

The `JwtAuthenticationFilter` enforces:
- `GET /api/**` → any authenticated user (USER or ADMIN)
- `POST /PUT /DELETE /api/**` → ADMIN role only

```java
// JwtAuthenticationFilter.java — line 74
if (path.startsWith("/api/") && !READ_METHODS.contains(method) && !"ADMIN".equals(role)) {
    return shortCircuit(exchange, HttpStatus.FORBIDDEN,
            "ADMIN role required for " + method + " operations");
}
```

**No token — HTTP 401:**
```bash
curl -s -w "\nHTTP %{http_code}" http://localhost:8080/api/loans
# {"error":"Authentication required"}
# HTTP 401
```

*[Insert Screenshot — Figure 3.4.2: Request with no token returning HTTP 401]*  
*Screencast timestamp: `09:20–09:35`*

**USER token on write — HTTP 403:**
```bash
curl -s -X POST http://localhost:8080/api/loans \
  -H "Authorization: Bearer $USER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"equipmentItemId":1,"borrowerName":"Test","borrowerContact":"t@t.com"}' \
  -w "\nHTTP %{http_code}"
# {"error":"ADMIN role required for POST operations"}
# HTTP 403
```

*[Insert Screenshot — Figure 3.4.3: USER token on POST returning HTTP 403]*  
*Screencast timestamp: `09:35–09:50`*

**ADMIN token on write — HTTP 201:**

*[Insert Screenshot — Figure 3.4.4: ADMIN token on POST /api/equipment returning HTTP 201]*  
*Screencast timestamp: `09:50–10:15`*

---

### 3.5 Service-to-Service Communication and Resilience

*Criterion mapping: Criterion 5 — Service-to-Service Communication and Resilience (10 marks)*  
*ADR Reference: ADR 3 — Resilience and Failure Handling*

#### 3.5.1 OpenFeign — Synchronous Availability Check

When `PUT /api/loans/{id}/approve` is received, loan-service calls inventory-service synchronously. This check cannot be deferred because the result (available or not) determines the approval decision in the same HTTP request.

```java
// InventoryClient.java
@FeignClient(name = "inventory-service")
public interface InventoryClient {
    @GetMapping("/equipment/{id}/availability")
    EquipmentAvailabilityDto checkAvailability(@PathVariable("id") Long equipmentItemId);
}
```

`name = "inventory-service"` resolves via Eureka — no hardcoded URL.

**Normal path — Loan 6, Equipment 6 (available):**
```
DEBUG InventoryAvailabilityAdapter - Calling inventory-service to check availability for equipmentId=6
INFO  LoanRequestService - Availability check: equipmentId=6 available=true status=AVAILABLE → approving
INFO  LoanEventPublisher - Published LoanApprovedEvent for loanId=6 equipmentId=6
```

**Rejection path — Loan 5, Equipment 1 (ON_LOAN):**
```
DEBUG InventoryAvailabilityAdapter - Calling inventory-service to check availability for equipmentId=1
INFO  LoanRequestService - Availability check: equipmentId=1 available=false status=ON_LOAN → rejecting
```
Result: Loan 5 → REJECTED. No fallback triggered — this is a correct business response, not a transport failure.

*[Insert Screenshot — Figure 3.5.1: loan-service logs showing Feign availability check for loan 6 approval]*  
*Screencast timestamp: `13:15–14:00`*

#### 3.5.2 Resilience4J Stack

`InventoryAvailabilityAdapter` wraps the Feign client with four resilience layers:

```java
@Retry(name = "inventoryService")
@CircuitBreaker(name = "inventoryService", fallbackMethod = "checkAvailabilityFallback")
public EquipmentAvailabilityDto checkAvailability(Long equipmentItemId) {
    return inventoryClient.checkAvailability(equipmentItemId);
}

public EquipmentAvailabilityDto checkAvailabilityFallback(Long equipmentItemId, Throwable throwable) {
    log.warn("inventory-service unreachable for equipmentId={}; provisionally approving. Cause: {}",
             equipmentItemId, throwable.getMessage());
    return new EquipmentAvailabilityDto(equipmentItemId, true, "UNKNOWN_FALLBACK");
}
```

**Configuration values (from `config-repo/loan-service-dev.yml`):**

| Layer | Configuration |
| :--- | :--- |
| Feign connect timeout | 3000 ms |
| Feign read timeout | 5000 ms |
| `@Retry` max-attempts | 3 |
| `@Retry` retry-exceptions | `IOException`, `RetryableException` (transport only) |
| `@CircuitBreaker` sliding-window-size | 5 calls |
| `@CircuitBreaker` failure-rate-threshold | 50% |
| `@CircuitBreaker` wait-duration-in-open-state | 10 seconds |
| `@CircuitBreaker` permitted-in-half-open | 2 probe calls |
| Fallback | Provisional `available=true` + WARN log |

**Fallback path — inventory-service stopped:**
```
WARN InventoryAvailabilityAdapter - inventory-service unreachable for equipmentId=2;
     provisionally approving. Cause: ConnectException — Connection refused
```

*[Insert Screenshot — Figure 3.5.2: loan-service WARN log showing fallback activation]*  
*Screencast timestamp: `14:15–14:45`*

**Actuator circuit-breaker state (OPEN):**
```bash
curl -s http://localhost:8081/actuator/circuitbreakers
# {"circuitBreakers":{"inventoryService":{"state":"OPEN","failureRate":"100.0%",...}}}
```

*[Insert Screenshot — Figure 3.5.3: Actuator /circuitbreakers showing state=OPEN after inventory-service stopped]*  
*Screencast timestamp: `14:45–15:15`*

---

### 3.6 Asynchronous Messaging

*Criterion mapping: Criterion 6 — Asynchronous Messaging (20 marks — highest weighted criterion)*  
*ADR Reference: ADR 2 — Event-Driven Communication*

#### 3.6.1 Justification for Asynchronous Communication

After a loan is approved, the equipment item's status must change from `AVAILABLE` to `ON_LOAN`. Two options were considered:

| Option | Problem | Decision |
| :--- | :--- | :--- |
| Synchronous `PUT /equipment/{id}/status` | Adds a second synchronous call on the critical approval path. If inventory-service is restarting, the loan approval fails even though the loan was already saved. Two independent failure points on one critical path. | **Rejected** |
| Asynchronous RabbitMQ event | The loan approval is already committed. The status update is a side effect. The consumer processes it independently. Loan approval latency is unaffected by inventory-service availability. | **Chosen** |

The availability check (`GET /equipment/{id}/availability`) remains synchronous because its result determines whether the loan is approved — it cannot be deferred. The status update has no such constraint.

#### 3.6.2 Infrastructure

| Element | Name | Type |
| :--- | :--- | :--- |
| Exchange | `loan.events` | TopicExchange, durable |
| Queue | `inventory.loan-events` | Durable |
| Routing key — approval | `loan.approved` | Binds queue to exchange |
| Routing key — return | `loan.returned` | Binds queue to exchange |

*[Insert Screenshot — Figure 3.6.1: RabbitMQ Management UI at http://localhost:15672 showing loan.events exchange]*  
*Screencast timestamp: `15:45–16:30`*

*[Insert Screenshot — Figure 3.6.2: RabbitMQ Bindings tab showing loan.approved and loan.returned routing keys]*  
*Screencast timestamp: `16:30–17:00`*

#### 3.6.3 Event Producer

```java
// LoanEventPublisher.java
public void publishApproved(LoanApprovedEvent event) {
    rabbitTemplate.convertAndSend(
            RabbitMqConfig.LOAN_EVENTS_EXCHANGE,  // "loan.events"
            "loan.approved",
            event
    );
    log.info("Published LoanApprovedEvent for loanId={} equipmentId={}",
            event.loanRequestId(), event.equipmentItemId());
}
```

#### 3.6.4 Event Consumer

```java
// LoanEventListener.java
@RabbitListener(queues = "inventory.loan-events")
public void handleLoanEvent(Message message) throws IOException {
    String routingKey = message.getMessageProperties().getReceivedRoutingKey();
    switch (routingKey) {
        case "loan.approved" -> {
            LoanApprovedEvent event = objectMapper.readValue(message.getBody(), LoanApprovedEvent.class);
            equipmentItemService.updateStatus(event.equipmentItemId(), EquipmentStatus.ON_LOAN);
            log.info("Equipment {} → ON_LOAN (loan request {})", event.equipmentItemId(), event.loanRequestId());
        }
        case "loan.returned" -> {
            LoanItemReturnedEvent event = objectMapper.readValue(message.getBody(), LoanItemReturnedEvent.class);
            equipmentItemService.updateStatus(event.equipmentItemId(), EquipmentStatus.AVAILABLE);
            log.info("Equipment {} → AVAILABLE (loan request {})", event.equipmentItemId(), event.loanRequestId());
        }
    }
}
```

The listener uses `ObjectMapper` directly on the raw AMQP `Message` body, bypassing `Jackson2JsonMessageConverter`'s trusted-package check. This achieves schema independence: neither service holds a compile-time dependency on the other's event classes.

#### 3.6.5 Complete Event Flow Verification — Loan 6, Equipment 6

**Step 1 — Equipment 6 before approval:**
```json
{"id":6,"name":"AED Unit","status":"AVAILABLE","location":"Bay 3"}
```

**Step 2 — Approve loan 6:**
```bash
curl -s -X PUT http://localhost:8080/api/loans/6/approve \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```
Response: `{"id":6,"status":"APPROVED","approvedAt":"2026-07-28T12:55:49.025248Z"}`

**Step 3 — inventory-service log (RabbitMQ consumer):**
```
INFO [inventory-service,4f2e1a3b9c7d8e0f,9b8c7d6e] LoanEventListener - Equipment 6 → ON_LOAN (loan request 6)
```

**Step 4 — Equipment 6 after approval:**
```json
{"id":6,"name":"AED Unit","status":"ON_LOAN","location":"Bay 3"}
```

No `PUT /equipment/{id}/status` endpoint was called at any point. Equipment status is updated **exclusively** through `LoanEventListener` in response to RabbitMQ messages. There is no such REST endpoint in the inventory-service codebase.

*[Insert Screenshot — Figure 3.6.3: inventory-service log showing Equipment 6 → ON_LOAN immediately after loan 6 approval]*  
*Screencast timestamp: `17:00–17:30`*

*[Insert Screenshot — Figure 3.6.4: MySQL query showing equipment_items.status = ON_LOAN for item 6 after approval]*  
*Screencast timestamp: `17:30–18:00`*

---

### 3.7 Observability and Distributed Tracing

*Criterion mapping: Criterion 7 — Observability and Distributed Tracing (10 marks)*  
*ADR Reference: ADR 3 — Resilience and Failure Handling*

#### 3.7.1 OpenTelemetry Configuration

Spring Boot 4.x requires manual OTel SDK wiring. `TracingConfig.java` (present in api-gateway, loan-service, and inventory-service) instantiates the SDK:

```java
// TracingConfig.java
@Bean
public OpenTelemetry openTelemetry(
        @Value("${spring.application.name}") String serviceName,
        @Value("${management.zipkin.tracing.endpoint}") String zipkinEndpoint) {

    ZipkinSpanExporter exporter = ZipkinSpanExporter.builder()
            .setEndpoint(zipkinEndpoint).build();
    SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .setResource(Resource.create(
                    Attributes.of(AttributeKey.stringKey("service.name"), serviceName)))
            .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
            .build();
    return OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .buildAndRegisterGlobal();
}
```

Shared configuration in `config-repo/application.yml`:
```yaml
management:
  tracing:
    sampling:
      probability: 1.0    # 100% of requests sampled
  zipkin:
    tracing:
      endpoint: http://localhost:9411/api/v2/spans

logging:
  pattern:
    level: "%5p [${spring.application.name},%X{traceId:-},%X{spanId:-}]"
```

#### 3.7.2 Services Confirmed in Zipkin

```bash
curl http://localhost:9411/api/v2/services
# ["api-gateway","inventory-service","loan-service"]
```

*[Insert Screenshot — Figure 3.7.1: Zipkin UI at http://localhost:9411 showing all three services in the dropdown]*  
*Screencast timestamp: `19:00–19:20`*

#### 3.7.3 Distributed Trace — Loan Approval

For `PUT /api/loans/6/approve`, Zipkin produces the following span waterfall:

```
api-gateway: http put                              [535 ms]
  └─ loan-service: http put /loans/{id}/approve   [513 ms]
       ├─ inventory-service: GET /equipment/6/availability  [2.3 ms — Feign]
       ├─ loan-service: save loan_request                   [JPA]
       └─ loan-service: RabbitMQ publish loan.approved      [AMQP]
```

Trace context propagates via W3C `traceparent` headers from gateway → loan-service → inventory-service.

*[Insert Screenshot — Figure 3.7.2: Zipkin span waterfall showing gateway, loan-service, and inventory-service spans for PUT /api/loans/6/approve]*  
*Screencast timestamp: `19:20–20:00`*

**Async trace — documented finding:** The RabbitMQ consumer span in `LoanEventListener` appears as a linked child trace (OTel `LINK`) in Zipkin, not as an inline child span. This is a known Spring AMQP observation behaviour in Spring Boot 4.x. The linked trace is visible in Zipkin under the `inventory-service` filter. This is documented in ADR 3 as a known limitation, not a defect.

#### 3.7.4 Cross-Service Log Correlation

The same `traceId` appears across loan-service and inventory-service log lines:

```
INFO [loan-service,4f2e1a3b9c7d8e0f,2a1b3c4d] LoanRequestService - Approving loan 6
INFO [loan-service,4f2e1a3b9c7d8e0f,2a1b3c4d] LoanEventPublisher - Published LoanApprovedEvent for loanId=6
INFO [inventory-service,4f2e1a3b9c7d8e0f,9b8c7d6e] LoanEventListener - Equipment 6 → ON_LOAN (loan request 6)
```

`traceId=4f2e1a3b9c7d8e0f` spans both services, enabling cross-service correlation without a UI.

*[Insert Screenshot — Figure 3.7.3: Terminal log lines showing identical traceId in loan-service and inventory-service logs]*  
*Screencast timestamp: `20:00–20:15`*

---

## 4. Architecture Decision Records

*Criterion mapping: Criterion 8 — ADRs and Technical Justification (10 marks)*

The assignment specifies three required ADRs. All three are maintained as standalone files in `docs/adr/` and summarised below. Each is cross-referenced to implementation artefacts, evidence sections, and screencast timestamps.

---

### ADR 1 — Gateway-Centred Security

**File:** `docs/adr/001-gateway-centred-security.md`  
**Status:** Accepted

**Context:**  
The platform exposes five Spring Boot processes. Clients must be directed through a single controlled entry point. JWT was evaluated against session cookies (no shared session store across stateless services) and OAuth2 (adds an authorisation server process; no third-party application delegation needed for two internal users). A filter ordering constraint exists: the correlation ID must be present on every response, including rejected 401/403 responses, which requires the JWT filter to run after the correlation ID filter.

**Decision:**  
All authentication and RBAC enforcement is handled exclusively by `JwtAuthenticationFilter` in the API Gateway. The filter runs at `@Order(HIGHEST_PRECEDENCE + 1)` — one slot after `CorrelationIdFilter` (`HIGHEST_PRECEDENCE`). `SecurityConfig` disables Spring Security's reactive defaults (form login, HTTP Basic, CSRF, block-all rule) so the JWT filter is the sole access-control mechanism.

Role rule: `GET /api/**` → any authenticated user (USER or ADMIN); `POST/PUT/DELETE /api/**` → ADMIN role required.

**Alternatives Considered:**

| Alternative | Reason Rejected |
| :--- | :--- |
| Per-service Spring Security | Duplicates HMAC-SHA256 JWT parsing across every service; a secret rotation requires redeployment of all services |
| OAuth2 with authorisation server | Adds an entire extra process; no third-party delegation needed for two internal users |
| API key header | No expiry mechanism; no role payload without a separate database lookup |

**Consequences:**  
Positive: Security logic in one file; correlation ID present on all responses including rejections; domain services are simpler with no security code.  
Negative: Domain service ports 8081/8082 are open on localhost in dev. Production fix: private VPC/cluster subnet with `NetworkPolicy` dropping all external traffic to ports 8081/8082. Two in-memory accounts in `AuthController` — a database user store is required for production.

**Implementation Artefacts:**
- `platform/api-gateway/.../security/JwtAuthenticationFilter.java` — filter at `HIGHEST_PRECEDENCE + 1`
- `platform/api-gateway/.../security/SecurityConfig.java` — disables Spring Security defaults
- `platform/api-gateway/.../filter/CorrelationIdFilter.java` — filter at `HIGHEST_PRECEDENCE`
- `platform/api-gateway/.../auth/AuthController.java` — `POST /auth/login`

**Report Evidence:** §3.4 — 401, 403, 201 evidence; JWT payload decoded  
**Screencast Timestamps:** `08:45–10:15`

---

### ADR 2 — Event-Driven Communication

**File:** `docs/adr/002-event-driven-communication.md`  
**Status:** Accepted

**Context:**  
After a loan is approved, equipment status must change from `AVAILABLE` to `ON_LOAN`. A synchronous Feign call already exists for the availability check (`GET /equipment/{id}/availability`) — this is justified because its result determines the approval decision in the same request. The question is whether the status update should also be synchronous.

**Decision:**  
The equipment status update is performed asynchronously via a RabbitMQ topic exchange. The availability check remains synchronous because its outcome determines the approval decision — it cannot be deferred. The status update is a committed side-effect: the loan record is already saved as APPROVED before the event is published. Making the HTTP approval response wait for a second synchronous call to inventory-service would add a second critical-path failure point with no benefit to the borrower.

**Exact infrastructure:**

| Element | Name |
| :--- | :--- |
| Exchange | `loan.events` (TopicExchange, durable) |
| Queue | `inventory.loan-events` (durable) |
| Routing key — approval | `loan.approved` |
| Routing key — return | `loan.returned` |

Schema independence is achieved by having each service hold its own copy of the event record definition. `LoanEventListener` deserialises using `ObjectMapper` directly on the AMQP message body, bypassing the `Jackson2JsonMessageConverter` trusted-package check. Neither service holds a compile-time dependency on the other's event classes.

**Alternatives Considered:**

| Alternative | Reason Rejected |
| :--- | :--- |
| Synchronous `PUT /equipment/{id}/status` | Second critical-path failure point: if inventory-service is restarting, the approval fails even though the loan is already committed |
| Shared event library JAR | Compile-time coupling; simultaneous redeployment of both services required for any event schema change |
| Spring Cloud Stream | Abstracts exchange/queue names without benefit; harder to verify in Zipkin |

**Consequences:**  
Positive: Approval latency unaffected by inventory-service availability; durable queue guarantees delivery across restarts; schema independence.  
Negative: Equipment status is eventually consistent (sub-second delay accepted for this domain); a `GET /equipment/{id}` immediately after approval may briefly return `AVAILABLE`.

**Implementation Artefacts:**
- `services/inventory-service/.../config/RabbitMqConfig.java` — exchange, queue, two bindings
- `services/loan-service/.../messaging/LoanEventPublisher.java` — `convertAndSend("loan.events", "loan.approved", ...)`
- `services/inventory-service/.../messaging/LoanEventListener.java` — `@RabbitListener(queues = "inventory.loan-events")`

**Report Evidence:** §3.6 — complete event flow; §3.6.5 — before/after database state  
**Screencast Timestamps:** `15:45–18:00`

---

### ADR 3 — Resilience and Failure Handling

**File:** `docs/adr/003-resilience-and-failure-handling.md`  
**Status:** Accepted

**Context:**  
The Feign call to inventory-service is on the critical approval path. A TCP connection timeout or inventory-service restart must not block loan-service's thread pool or prevent all approvals. Separately, five processes need a way to correlate log lines without manual grepping across five terminal windows.

**Decision — Resilience:**  
A four-layer resilience stack is applied in `InventoryAvailabilityAdapter` (a concrete Spring bean that wraps the Feign interface, required because Resilience4J annotations do not work on Feign proxy interface methods):

| Layer | Configuration (from `config-repo/loan-service-dev.yml`) |
| :--- | :--- |
| Feign connect timeout | 3000 ms |
| Feign read timeout | 5000 ms |
| `@Retry` | 3 attempts, 500 ms wait, `IOException` + `RetryableException` only (transport failures; business responses not retried) |
| `@CircuitBreaker` | 5-call sliding window, 50% failure-rate threshold, 10 s open, 2 HALF-OPEN probes |
| Fallback | Returns provisional `available=true`; logs WARN — loan approved, operations staff alerted |

Fallback rationale: in an emergency lending context, blocking all loan approvals during a 30-second inventory-service restart is operationally worse than provisionally approving a small number of loans. The WARN log alerts operations staff.

`@Retry` is restricted to transport exceptions only. Business responses (`available=false` for an ON_LOAN item) pass through without retry. Loan 5 (`status: REJECTED`) confirms this: the Feign call succeeded, returned `available=false`, and the loan was correctly rejected without the fallback firing.

**Decision — Distributed Tracing:**  
Manual `TracingConfig.java` wires OTel SDK + `ZipkinSpanExporter` + `W3CTraceContextPropagator` in all three application services. 100% sampling (`probability: 1.0`). W3C `traceparent` header propagates spans across HTTP hops.

**Documented finding:** The RabbitMQ consumer span in `LoanEventListener` appears as a linked child trace (OTel `LINK`) in Zipkin, not an inline child span. This is a known Spring AMQP observation behaviour in Spring Boot 4.x. Cross-service log correlation works via the shared `traceId` in MDC. Inline span would require manual `traceparent` extraction in the listener — a future improvement.

**Alternatives Considered:**

| Alternative | Reason Rejected |
| :--- | :--- |
| No fallback / fail-fast | Blocks all loan approvals during inventory outage — unacceptable for emergency domain |
| Jaeger | More feature-rich; rejected because Zipkin requires zero additional configuration |
| Spring Cloud Sleuth | End-of-life since Spring Boot 3.x; not available in Spring Boot 4.x |
| Bulkhead isolation | Useful at higher scale; not applied at current single-instance scale |

**Consequences:**  
Positive: Loan approval is resilient to transient inventory outages; actuator exposes real-time circuit-breaker state; 100% trace sampling enables full visibility.  
Negative: Fallback provisional approvals can double-book during sustained outages (mitigated by WARN log); async span appears as OTel LINK in Zipkin.

**Implementation Artefacts:**
- `services/loan-service/.../client/InventoryAvailabilityAdapter.java` — `@Retry` + `@CircuitBreaker` + fallback method
- `config-repo/loan-service-dev.yml` — exact threshold values
- `services/*/config/TracingConfig.java` — OTel SDK, ZipkinSpanExporter, W3C propagator

**Report Evidence:** §3.5.2 — Resilience stack; §3.5.1 — Feign logs (normal, rejection, fallback); §3.7 — Zipkin traces and log correlation  
**Screencast Timestamps:** `13:15–15:45` (Feign + Resilience4J), `19:00–20:15` (Zipkin)

---

## 5. Screencast Cross-Reference Table

*Criterion mapping: Criterion 10 — Screencast and Report Quality (10 marks)*

> Timestamps are in `MM:SS–MM:SS` format. Insert actual timestamps after recording.

| Requirement | Report Section | Screenshot Figure | Screencast Timestamp | ADR |
| :--- | :--- | :--- | :--- | :--- |
| Architecture diagram and service overview | §2 | Figure 2.1 | `00:00–02:00` | — |
| All 5 services registered UP in Eureka | §3.2.1 | Figure 3.2.1 | `04:10–05:10` | ADR 4* |
| Config Server — shared profile | §3.3.1 | — | `05:10–05:30` | ADR 4* |
| Config Server — dev profile (`ddl-auto=update`) | §3.3.2 | Figure 3.3.1 | `05:30–06:00` | ADR 4* |
| Config Server — production profile (`ddl-auto=validate`) | §3.3.2 | Figure 3.3.2 | `06:00–06:30` | ADR 4* |
| Environment variables — sensitive config externalised | §3.3.3 | Figure 3.3.3 | `06:30–07:30` | ADR 1 |
| Gateway routing table — `lb://` URIs | §3.2.2 | Figure 3.2.2 | `07:30–08:00` | ADR 4* |
| Gateway filters — CorrelationId and JWT filter code | §3.2.3 | — | `08:00–08:45` | ADR 1 |
| X-Correlation-Id header visible in response | §3.2.3 | Figure 3.2.3 | `08:30–08:45` | ADR 1 |
| JWT login — ADMIN token issued | §3.4.1 | Figure 3.4.1 | `08:45–09:20` | ADR 1 |
| 401 — no token | §3.4.2 | Figure 3.4.2 | `09:20–09:35` | ADR 1 |
| 403 — USER token on write | §3.4.2 | Figure 3.4.3 | `09:35–09:50` | ADR 1 |
| 201 — ADMIN token on write | §3.4.2 | Figure 3.4.4 | `09:50–10:15` | ADR 1 |
| POST /api/equipment → 201 Created | §3.1.1 | Figure 3.1.1 | `10:15–10:50` | — |
| GET /api/equipment → 200 (all items) | §3.1.1 | Figure 3.1.2 | `10:50–11:10` | — |
| PUT /api/equipment/5 → 200 Updated | §3.1.1 | Figure 3.1.3 | `11:10–11:25` | — |
| DELETE /api/equipment → 204 No Content | §3.1.1 | Figure 3.1.4 | `11:25–11:35` | — |
| POST with missing fields → 400 validation error | §3.1.1 | Figure 3.1.5 | `11:35–11:50` | — |
| POST /api/loans → 201 Created (PENDING) | §3.1.2 | Figure 3.1.6 | `12:00–12:20` | — |
| PUT /api/loans/1/approve → 409 state conflict | §3.1.2 | Figure 3.1.7 | `12:45–13:00` | — |
| Feign call — loan 6 approved (happy path log) | §3.5.1 | Figure 3.5.1 | `13:15–14:00` | ADR 3 |
| Feign call — loan 5 rejected (business response, no fallback) | §3.5.1 | — | `14:00–14:15` | ADR 3 |
| Fallback WARN log — inventory-service stopped | §3.5.2 | Figure 3.5.2 | `14:15–14:45` | ADR 3 |
| Actuator `state=OPEN` after circuit trips | §3.5.2 | Figure 3.5.3 | `14:45–15:15` | ADR 3 |
| RabbitMQ Management UI — `loan.events` exchange | §3.6.2 | Figure 3.6.1 | `15:45–16:30` | ADR 2 |
| RabbitMQ Bindings — `loan.approved` and `loan.returned` | §3.6.2 | Figure 3.6.2 | `16:30–17:00` | ADR 2 |
| inventory-service log `Equipment 6 → ON_LOAN` | §3.6.5 | Figure 3.6.3 | `17:00–17:30` | ADR 2 |
| MySQL: equipment_items.status = ON_LOAN after approval | §3.6.5 | Figure 3.6.4 | `17:30–18:00` | ADR 2 |
| MySQL: loan_requests table — loan 5 REJECTED, loan 6 APPROVED | §3.1.2 | — | `18:00–18:30` | — |
| Zipkin UI — 3 services in service dropdown | §3.7.2 | Figure 3.7.1 | `19:00–19:20` | ADR 3 |
| Zipkin waterfall — 6 spans across 3 services | §3.7.3 | Figure 3.7.2 | `19:20–20:00` | ADR 3 |
| Logs — identical traceId across loan and inventory services | §3.7.4 | Figure 3.7.3 | `20:00–20:15` | ADR 3 |
| ADR 1 explained — Gateway-Centred Security | §4 ADR 1 | — | `20:15–20:45` | ADR 1 |
| ADR 2 explained — Event-Driven Communication | §4 ADR 2 | — | `20:45–21:15` | ADR 2 |
| ADR 3 explained — Resilience and Failure Handling | §4 ADR 3 | — | `21:15–21:45` | ADR 3 |
| Repository structure, README, commit history | §Repository | — | `22:15–22:45` | — |

> *ADR 4 refers to ADR 004 (Service Discovery and Configuration) which is maintained in `docs/adr/004-service-discovery-and-config.md` as a supplementary record beyond the three required by the assignment brief. The three required ADRs are numbered 1, 2, and 3 in this report.

---

## Repository

**URL:** `https://github.com/VinayReddy072/Microservices_Platform`

The repository contains all source code, supporting configuration, environment variable documentation, the `config-repo/` directory, five ADRs, and the verification report.

**README:** [`ReadMe.md`](ReadMe.md) — contains complete startup sequence, MySQL provisioning SQL, environment variable setup, and comprehensive curl examples for every endpoint.

**Commit history:** Seven commits representing: initial module structure, infrastructure services (Eureka, Config), domain service REST and JPA layers, API Gateway and security, service-to-service communication and resilience, RabbitMQ integration, and observability with documentation.

---

*End of report.*
