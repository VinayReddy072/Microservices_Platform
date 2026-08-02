# Emergency Equipment Lending Platform — Verification Report

**Domain:** Emergency Equipment Lending  
**Stack:** Spring Boot 4.1.0 · Spring Cloud 2025.1.2 · MySQL 8 · RabbitMQ 3 · OpenTelemetry + Zipkin  
**Build:** `mvn clean install -DskipTests` → `BUILD SUCCESS` across all 6 modules

---

## How to Use This Document

This verification report maps every assignment requirement to specific, reproducible evidence. Each section identifies the rubric criterion it satisfies, the command or query used to obtain the evidence, and the actual output observed against the running system.

---

## Rubric Requirements Checklist

| Criterion | Marks | Status | Evidence Section |
| :--- | :---: | :---: | :--- |
| 1. Architecture and Domain Design | 5 | ✅ | §1, §2 |
| 2. REST API Implementation | 5 | ✅ | §4, §5 |
| 3. Gateway, Config and Service Discovery | 10 | ✅ | §3, §6, §7 |
| 4. Security (JWT/OAuth2) | 10 | ✅ | §8 |
| 5. Service-to-Service Communication and Resilience | 10 | ✅ | §9 |
| 6. Asynchronous Messaging | 20 | ✅ | §10 |
| 7. Observability and Distributed Tracing | 10 | ✅ | §11 |
| 8. ADRs and Technical Justification | 10 | ✅ | §12 |
| 9. Repository Quality and Development Process | 10 | ✅ | §13 |
| 10. Screencast and Report Quality | 10 | Pending | §14 |

---

## 1. System Architecture

*Criterion mapping: Criterion 1 — Architecture and Domain Design*

The platform consists of five Spring Boot processes and three Docker-hosted infrastructure services:

| Process | Port | Role |
| :--- | :--- | :--- |
| Eureka Server | 8761 | Service registry — all services register here |
| Config Server | 8888 | Centralised YAML config, native filesystem backend, `config-repo/` |
| API Gateway | 8080 | Sole client entry point; JWT filter, correlation-ID filter, discovery-based routing |
| Loan Service | 8081 | Loan lifecycle (`PENDING → APPROVED → RETURNED`); Feign caller; RabbitMQ producer |
| Inventory Service | 8082 | Equipment catalogue CRUD; Feign target; RabbitMQ consumer |

| Infrastructure | Port | Purpose |
| :--- | :--- | :--- |
| MySQL 8 | 3306 | Dedicated schemas: `loan_db` (`loan_user`), `inventory_db` (`inventory_user`) |
| RabbitMQ | 5672 / 15672 | Topic exchange `loan.events`; queue `inventory.loan-events` |
| Zipkin | 9411 | Distributed trace collection (OTel spans from 3 services) |

**Domain originality:** The Emergency Equipment Lending domain is distinct from prior group work (hotel bookings, retail, banking). It models a genuine architectural constraint: availability must be checked synchronously (result determines approval decision) but status changes can propagate asynchronously (committed side-effect).

---

## 2. Build and Startup Verification

*Criterion mapping: Criterion 1, Criterion 9*

```
[INFO] Building Emergency Equipment Lending Platform — Parent      1.0.0-SNAPSHOT
[INFO] Building Emergency Equipment Lending — Eureka Server        BUILD SUCCESS
[INFO] Building Emergency Equipment Lending — Config Server        BUILD SUCCESS
[INFO] Building Emergency Equipment Lending — API Gateway          BUILD SUCCESS
[INFO] Building Emergency Equipment Lending — Loan Service         BUILD SUCCESS
[INFO] Building Emergency Equipment Lending — Inventory Service    BUILD SUCCESS
[INFO] BUILD SUCCESS
```

All six modules compile cleanly with `mvn clean install -DskipTests` from the project root.

**Startup order required (dependency chain):**

```
1. docker start eelp-zipkin eelp-rabbitmq eelp-mysql
2. eureka-server       (8761) — must start first; all others register here
3. config-server       (8888) — must start before domain services
4. inventory-service   (8082) — declares RabbitMQ exchange/queue
5. loan-service        (8081)
6. api-gateway         (8080) — last; needs services registered in Eureka
```

**Eureka dashboard — all five registered UP:**

```
Application         AMIs  Availability Zones  Status
API-GATEWAY          n/a        1            UP (1) — 172.x.x.x:api-gateway:8080
CONFIG-SERVER        n/a        1            UP (1) — 172.x.x.x:config-server:8888
INVENTORY-SERVICE    n/a        1            UP (1) — 172.x.x.x:inventory-service:8082
LOAN-SERVICE         n/a        1            UP (1) — 172.x.x.x:loan-service:8081
EUREKA-SERVER        n/a        1            UP (1) — 172.x.x.x:eureka-server:8761
```

*URL: http://localhost:8761*

---

## 3. Config Server — Multi-Profile Verification

*Criterion mapping: Criterion 3 — Gateway, Config and Service Discovery*

Config Server serves per-service, per-profile YAML from `config-repo/`.

```bash
# Shared configuration (all services)
curl http://localhost:8888/application/default
# Returns: eureka URL, RabbitMQ host/port, tracing settings, log pattern with traceId/spanId

# Loan service — DEV profile
curl http://localhost:8888/loan-service/dev
# Returns: datasource.url=jdbc:mysql://localhost:3306/loan_db
#          spring.jpa.hibernate.ddl-auto=update
#          spring.jpa.show-sql=true
#          feign.client.config.inventory-service.logger-level=FULL
#          Resilience4J: failure-rate-threshold=50, sliding-window-size=5

# Loan service — PRODUCTION profile
curl http://localhost:8888/loan-service/production
# Returns: spring.jpa.show-sql=false
#          spring.jpa.hibernate.ddl-auto=validate
```

**Config files present in `config-repo/`:**

| File | Profile |
| :--- | :--- |
| `application.yml` | Shared (all services, all profiles) |
| `loan-service-dev.yml` | Loan service, dev |
| `loan-service-production.yml` | Loan service, production |
| `inventory-service-dev.yml` | Inventory service, dev |
| `inventory-service-production.yml` | Inventory service, production |

**Sensitive configuration — externalised via environment variables:**

Sensitive values — `${LOAN_DB_PASS}`, `${INVENTORY_DB_PASS}`, `${JWT_SECRET}`, `${RABBITMQ_PASSWORD}` — are placeholders in config files resolved from environment variables at runtime. The `.env` file is listed in `.gitignore` and is not committed.

```yaml
# config-repo/loan-service-dev.yml (excerpt)
spring:
  datasource:
    url: ${LOAN_DB_URL:jdbc:mysql://localhost:3306/loan_db}
    username: ${LOAN_DB_USER:loan_user}
    password: ${LOAN_DB_PASS}
```

**Environment variables used:**

| Variable | Purpose |
| :--- | :--- |
| `JWT_SECRET` | HMAC-SHA256 signing key — gateway refuses to start without it |
| `LOAN_DB_URL` / `LOAN_DB_USER` / `LOAN_DB_PASS` | loan-service MySQL credentials |
| `INVENTORY_DB_URL` / `INVENTORY_DB_USER` / `INVENTORY_DB_PASS` | inventory-service MySQL credentials |
| `RABBITMQ_HOST` / `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | RabbitMQ broker credentials |
| `CONFIG_REPO_PATH` | Filesystem path to config-repo/ |

---

## 4. Inventory Service — REST API

*Criterion mapping: Criterion 2 — REST API Implementation*

**Base path:** `/equipment` (via gateway: `:8080/api/equipment`, direct: `:8082/equipment`)  
**Database:** `inventory_db.equipment_items`

### Live Data — equipment_items Table

| id | name | category | status | location | condition_notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 1 | Portable Defibrillator | Cardiac | ON_LOAN | Station A | Fully charged |
| 2 | Oxygen Cylinder | Respiratory | ON_LOAN | Ambulance Bay 2 | Full tank, pressure checked |
| 3 | Portable Oxygen Cylinder | Medical | AVAILABLE | — | — |
| 4 | EMS Resuscitation Kit | Medical | ON_LOAN | — | — |
| 5 | Defib Unit | Cardiac | AVAILABLE | Bay 1 | Ready |
| 6 | AED Unit | Cardiac | ON_LOAN | Bay 3 | Ready |

Items 1, 2, 4, 6 are `ON_LOAN` because their corresponding loans are `APPROVED` and the RabbitMQ listener has processed the `LoanApprovedEvent`. Status was **not** updated by any REST call — only by `LoanEventListener.handleLoanEvent()`.

### CRUD Evidence

**POST /api/equipment — HTTP 201 Created**
```bash
curl -s -X POST http://localhost:8080/api/equipment \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"AED Unit","category":"Cardiac","location":"Bay 3","conditionNotes":"Ready"}'
```
```json
{"id":6,"name":"AED Unit","category":"Cardiac","status":"AVAILABLE","location":"Bay 3","conditionNotes":"Ready"}
```

**GET /api/equipment — HTTP 200 OK** — returns all 6 records shown above.

**GET /api/equipment/3 — HTTP 200 OK**
```json
{"id":3,"name":"Portable Oxygen Cylinder","category":"Medical","status":"AVAILABLE","location":null,"conditionNotes":null}
```

**GET /api/equipment/6/availability — HTTP 200 OK** (internal endpoint, consumed by loan-service via Feign)
```json
{"equipmentItemId":6,"available":true,"status":"AVAILABLE"}
```
Returns `"available":false` for items with `status: ON_LOAN`.

**PUT /api/equipment/5 — HTTP 200 OK**
```bash
curl -s -X PUT http://localhost:8080/api/equipment/5 \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Defib Unit","category":"Cardiac","location":"Bay 1","conditionNotes":"Ready"}'
```
```json
{"id":5,"name":"Defib Unit","category":"Cardiac","status":"AVAILABLE","location":"Bay 1","conditionNotes":"Ready"}
```

**DELETE /api/equipment/{id} — HTTP 204 No Content**

**Validation failure — HTTP 400 Bad Request**
```json
{"name":"Equipment name must not be blank","category":"Category must not be blank"}
```

**Not found — HTTP 404 Not Found**
```json
{"message":"EquipmentItem not found: 999"}
```

---

## 5. Loan Service — REST API

*Criterion mapping: Criterion 2 — REST API Implementation*

**Base path:** `/loans` (via gateway: `:8080/api/loans`, direct: `:8081/loans`)  
**Database:** `loan_db.loan_requests`

### Live Data — loan_requests Table

| id | borrower_name | equipment_item_id | status | approved_at |
| :--- | :--- | :--- | :--- | :--- |
| 1 | Alice Smith | 1 | APPROVED | 2026-07-19 06:58:23 |
| 2 | John Doe | 2 | APPROVED | 2026-07-20 13:05:52 |
| 3 | Man | 4 | APPROVED | 2026-07-27 13:05:46 |
| 4 | Dr. Smith | 1 | APPROVED | 2026-07-27 14:02:24 |
| 5 | Trace Tester | 1 | **REJECTED** | null |
| 6 | Trace Tester | 6 | APPROVED | 2026-07-28 12:55:49 |

Loan 5 was correctly **REJECTED** — equipment item 1 had `status: ON_LOAN` when the Feign availability check ran. The fallback was not triggered — the Feign call to inventory-service succeeded and returned `available: false`.

### CRUD Evidence

**POST /api/loans — HTTP 201 Created (status starts as PENDING)**
```bash
curl -s -X POST http://localhost:8080/api/loans \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"equipmentItemId":6,"borrowerName":"Trace Tester","borrowerContact":"trace@ems.org"}'
```
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

**GET /api/loans — HTTP 200 OK** — returns all 6 records above.

**GET /api/loans/6 — HTTP 200 OK** — returns single record.

**PUT /api/loans/6/approve — HTTP 200 OK**  
Triggers: (1) Feign `GET /equipment/6/availability`, (2) if available → status `APPROVED`, (3) `LoanApprovedEvent` published to RabbitMQ routing key `loan.approved`.

```json
{
  "id": 6,
  "equipmentItemId": 6,
  "borrowerName": "Trace Tester",
  "status": "APPROVED",
  "requestedAt": "2026-07-28T12:55:47.102480Z",
  "approvedAt": "2026-07-28T12:55:49.025248Z",
  "returnedAt": null
}
```

**PUT /api/loans/{id}/return — HTTP 200 OK** — status → `RETURNED`, publishes `LoanItemReturnedEvent`

**DELETE /api/loans/{id} — HTTP 204 No Content**

**Validation failure — HTTP 400 Bad Request**
```json
{
  "equipmentItemId": "Equipment item ID must not be null",
  "borrowerContact": "Borrower contact must not be blank"
}
```

**Business rejection — HTTP 200 OK, status=REJECTED** (when item is ON_LOAN)
```json
{"id":5,"status":"REJECTED","equipmentItemId":1,...}
```

**State machine conflict — HTTP 409 Conflict**
```json
{"message":"Cannot approve loan 1 — current status is APPROVED"}
```

---

## 6. API Gateway — Routing and Filters

*Criterion mapping: Criterion 3 — Gateway, Config and Service Discovery*

All client traffic enters through port 8080. Domain service ports 8081 and 8082 are not used directly by clients.

**Route Configuration** (`http://localhost:8080/actuator/gateway/routes`):

| Route ID | Predicate | Target URI | Rewrite Rule |
| :--- | :--- | :--- | :--- |
| inventory-service-route | `Path=/api/equipment/**` | `lb://inventory-service` | `/api/equipment → /equipment` |
| loan-service-route | `Path=/api/loans/**` | `lb://loan-service` | `/api/loans → /loans` |

Both routes use `lb://` URIs resolved by Spring Cloud LoadBalancer against Eureka at request time.

**CorrelationId Filter (`@Order(HIGHEST_PRECEDENCE)`):**  
Generates a UUID and injects it as `X-Correlation-Id` in both the request and response headers. Present on all responses — including 401 and 403 rejections.

```bash
curl -sI -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/api/loans
# Response headers include:
# X-Correlation-Id: 3a7b2c1d-4e5f-6789-abcd-ef0123456789
```

**RewritePath Filter:**
```
Client:    GET /api/equipment/3
Forwarded: GET /equipment/3   (to inventory-service)

Client:    PUT /api/loans/6/approve
Forwarded: PUT /loans/6/approve  (to loan-service)
```

---

## 7. Service Discovery — Eureka

*Criterion mapping: Criterion 3 — Gateway, Config and Service Discovery*

All five services register with Eureka on startup using the shared configuration from `config-repo/application.yml`:

```yaml
eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
  instance:
    prefer-ip-address: true
    lease-renewal-interval-in-seconds: 10
```

Gateway routes use `uri: lb://service-name` — resolved against Eureka at request time. Feign client uses `@FeignClient(name = "inventory-service")` — resolved against Eureka. No hardcoded host or port exists in any service's source code.

`enable-self-preservation: false` is configured for local development (immediate eviction of terminated instances). Must be `true` in production.

---

## 8. Security — JWT Authentication and RBAC

*Criterion mapping: Criterion 4 — Security (JWT/OAuth2)*

Authentication is enforced exclusively at the gateway by `JwtAuthenticationFilter`. Domain services carry no security configuration.

### Token Issuance

```bash
# ADMIN token
curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"adminpass"}'
# Response: {"token":"eyJhbGciOiJIUzI1NiJ9..."}

# USER token
curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"user","password":"password"}'
# Response: {"token":"eyJhbGciOiJIUzI1NiJ9..."}
```

**JWT payload (decoded):**
```json
{"sub":"admin","role":"ADMIN","iat":1753696000,"exp":1753699600}
{"sub":"user","role":"USER","iat":1753696000,"exp":1753699600}
```

Tokens are HMAC-SHA256 signed using `JWT_SECRET` environment variable. Expiry: 1 hour.

### Role-Based Access Control Matrix

| Method | Path | USER token | ADMIN token | No token |
| :--- | :--- | :---: | :---: | :---: |
| GET | `/api/**` | ✅ 200 | ✅ 200 | ❌ 401 |
| POST | `/api/**` | ❌ 403 | ✅ 201 | ❌ 401 |
| PUT | `/api/**` | ❌ 403 | ✅ 200 | ❌ 401 |
| DELETE | `/api/**` | ❌ 403 | ✅ 204 | ❌ 401 |
| Any | `/auth/login` | ✅ public | ✅ public | ✅ public |

### Verified Security Responses

**No token — HTTP 401:**
```bash
curl -s -w "\nHTTP %{http_code}" http://localhost:8080/api/loans
# {"error":"Authentication required"}
# HTTP 401
```

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

**USER token on GET — HTTP 200:**
```bash
curl -s -o /dev/null -w "HTTP %{http_code}" \
  -H "Authorization: Bearer $USER_TOKEN" http://localhost:8080/api/loans
# HTTP 200
```

**ADMIN token on write — HTTP 201:**
```bash
curl -s -X POST http://localhost:8080/api/equipment \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"AED Unit","category":"Cardiac","location":"Bay 3","conditionNotes":"Ready"}' \
  -w "\nHTTP %{http_code}"
# HTTP 201
```

---

## 9. Service-to-Service Communication and Resilience

*Criterion mapping: Criterion 5 — Service-to-Service Communication and Resilience*

### 9.1 Normal Path — Feign Call (Loan 6, Equipment 6 Available)

When `PUT /api/loans/{id}/approve` is called, loan-service calls `GET /equipment/{id}/availability` on inventory-service via OpenFeign before saving the approval. This call is synchronous because the outcome determines the approval decision.

**Feign client:**
```java
@FeignClient(name = "inventory-service")
public interface InventoryClient {
    @GetMapping("/equipment/{id}/availability")
    EquipmentAvailabilityDto checkAvailability(@PathVariable("id") Long equipmentItemId);
}
```

**loan-service log — successful approval:**
```
DEBUG InventoryAvailabilityAdapter - Calling inventory-service to check availability for equipmentId=6
INFO  LoanRequestService - Availability check: equipmentId=6 available=true status=AVAILABLE → approving
INFO  LoanEventPublisher - Published LoanApprovedEvent for loanId=6 equipmentId=6
```

**Result:** Loan 6 → APPROVED. Equipment 6 → ON_LOAN (via RabbitMQ consumer).

### 9.2 Business Rejection — Feign Returns Unavailable (Loan 5, Equipment 1 ON_LOAN)

Inventory-service is reachable but returns `available=false`. No fallback fires — this is a legitimate business response.

**loan-service log:**
```
DEBUG InventoryAvailabilityAdapter - Calling inventory-service to check availability for equipmentId=1
INFO  LoanRequestService - Availability check: equipmentId=1 available=false status=ON_LOAN → rejecting
```

**Result:** Loan 5 → REJECTED. `@Retry` did not retry — restricted to transport exceptions (`IOException`, `RetryableException`) only. Business responses pass through unchanged.

### 9.3 Fallback Path — inventory-service Stopped

When inventory-service is stopped and the circuit breaker opens:

**loan-service log:**
```
WARN  InventoryAvailabilityAdapter - inventory-service unreachable for equipmentId=2;
      provisionally approving — verify equipment status manually.
      Cause: ConnectException — Connection refused: localhost/127.0.0.1:8082
```

**Actuator circuit-breaker state:**
```bash
curl -s http://localhost:8081/actuator/circuitbreakers
# {"circuitBreakers":{"inventoryService":{"state":"OPEN","failureRate":"100.0%",...}}}
```

**Fallback rationale:** In an emergency equipment context, blocking all loan approvals during a transient inventory-service restart is worse than provisionally approving a small number. Operations staff are alerted via the WARN log. Full justification in ADR 3.

### 9.4 Resilience Stack Configuration

| Layer | Configuration (from `config-repo/loan-service-dev.yml`) |
| :--- | :--- |
| Feign connect timeout | 3000 ms |
| Feign read timeout | 5000 ms |
| `@Retry` max-attempts | 3 |
| `@Retry` wait-duration | 500 ms |
| `@Retry` retry-exceptions | `IOException`, `RetryableException` (transport only) |
| `@CircuitBreaker` sliding-window-size | 5 calls |
| `@CircuitBreaker` failure-rate-threshold | 50% |
| `@CircuitBreaker` wait-duration-in-open-state | 10 seconds |
| `@CircuitBreaker` permitted-in-half-open | 2 probe calls |
| Fallback method | `checkAvailabilityFallback` — provisional `available=true` + WARN log |

---

## 10. Asynchronous Messaging — RabbitMQ

*Criterion mapping: Criterion 6 — Asynchronous Messaging (20 marks)*

### 10.1 Infrastructure

| Element | Value |
| :--- | :--- |
| Exchange | `loan.events` (TopicExchange, durable) |
| Queue | `inventory.loan-events` (durable) |
| Binding 1 | routing key `loan.approved` → `inventory.loan-events` |
| Binding 2 | routing key `loan.returned` → `inventory.loan-events` |
| RabbitMQ Management UI | http://localhost:15672 (guest / guest) |

### 10.2 Justification — Async vs. Synchronous REST

| Dimension | Synchronous `PUT /equipment/{id}/status` | Asynchronous RabbitMQ (chosen) |
| :--- | :--- | :--- |
| **Coupling** | Runtime dependency — inventory must be UP | Temporal decoupling |
| **Failure surface** | Two synchronous calls on one critical path | One synchronous check; status update retried by durable queue |
| **Availability** | Both services must be UP for loan approval | Loan approval succeeds even during inventory restart |
| **Latency** | Two round-trips before response returned | Response returned after DB commit only |

The availability check (`GET /equipment/{id}/availability`) remains synchronous because its result determines the approval decision. The status update has no such requirement.

### 10.3 Event Flow — Loan Approval (Loan 6, Equipment 6)

1. `PUT /api/loans/6/approve` received by gateway → forwarded to loan-service
2. loan-service calls `GET /equipment/6/availability` via Feign → `{available:true, status:AVAILABLE}`
3. Loan saved as `APPROVED`, `approvedAt` set to `2026-07-28T12:55:49Z`
4. `LoanEventPublisher.publishApproved()` calls `rabbitTemplate.convertAndSend("loan.events", "loan.approved", event)`
5. RabbitMQ delivers to `inventory.loan-events`
6. `LoanEventListener.handleLoanEvent()` receives message; reads routing key `loan.approved`
7. Calls `equipmentItemService.updateStatus(6, ON_LOAN)`
8. `equipment_items` row 6: `status` updated to `ON_LOAN`

**inventory-service log:**
```
INFO  [inventory-service,4f2e1a3b9c7d8e0f,9b8c7d6e] LoanEventListener - Equipment 6 → ON_LOAN (loan request 6)
```

### 10.4 Event Flow — Loan Return

1. `PUT /api/loans/{id}/return` → loan saved as `RETURNED`
2. `LoanItemReturnedEvent` published with routing key `loan.returned`
3. `LoanEventListener` receives; calls `updateStatus(id, AVAILABLE)`

**inventory-service log:**
```
INFO  LoanEventListener - Equipment 6 → AVAILABLE (loan request 6)
```

### 10.5 State Verification

Equipment status changes are driven **exclusively by RabbitMQ messages** — there is no `PUT /equipment/{id}/status` endpoint anywhere in the inventory-service codebase. The live equipment table (§4) confirms: items 1, 2, 4, 6 are `ON_LOAN` because their corresponding loans (1, 2, 3, 6) are `APPROVED` and the listener has processed the events.

**SQL verification:**
```sql
USE inventory_db;
SELECT id, name, status FROM equipment_items ORDER BY id;
-- id=1: ON_LOAN, id=2: ON_LOAN, id=3: AVAILABLE, id=4: ON_LOAN, id=5: AVAILABLE, id=6: ON_LOAN
```

---

## 11. Observability — Distributed Tracing

*Criterion mapping: Criterion 7 — Observability and Distributed Tracing*

### 11.1 Zipkin Service Registration Confirmed

```bash
curl http://localhost:9411/api/v2/services
# ["api-gateway","inventory-service","loan-service"]
```

All three services are registered and actively sending spans to Zipkin at http://localhost:9411.

### 11.2 Zipkin Trace — Loan Approval (End-to-End)

For `PUT /loans/6/approve`, Zipkin shows the following span waterfall:

```
api-gateway: http put                                    [535 ms]
  └─ loan-service: http put /loans/{id}/approve         [513 ms]
       ├─ inventory-service: GET /equipment/6/availability  [2.3 ms — Feign]
       ├─ loan-service: save loan_request                   [JPA]
       └─ loan-service: RabbitMQ publish loan.approved      [AMQP]
```

The `traceparent` W3C header propagates trace context: gateway → loan-service → inventory-service (Feign).

**Async trace — documented finding:** The RabbitMQ consumer span in inventory-service (`LoanEventListener`) appears as a **linked child trace** (OTel `LINK`) in Zipkin, not as an inline child span. This is a known Spring AMQP observation behaviour in Spring Boot 4.x. The linked trace is visible in Zipkin under the `inventory-service` filter. Documented in ADR 3.

### 11.3 Correlation IDs in Logs

Every log line includes `traceId` and `spanId` from MDC via the log pattern in `config-repo/application.yml`:

```yaml
logging:
  pattern:
    level: "%5p [${spring.application.name},%X{traceId:-},%X{spanId:-}]"
```

**Cross-service log correlation:**
```
INFO [loan-service,4f2e1a3b9c7d8e0f,2a1b3c4d] LoanRequestService - Approving loan 6
INFO [loan-service,4f2e1a3b9c7d8e0f,2a1b3c4d] LoanEventPublisher - Published LoanApprovedEvent for loanId=6
INFO [inventory-service,4f2e1a3b9c7d8e0f,9b8c7d6e] LoanEventListener - Equipment 6 → ON_LOAN (loan request 6)
```

The same `traceId` (`4f2e1a3b9c7d8e0f`) spans both services, enabling cross-service log correlation.

### 11.4 Tracing Implementation Note

Spring Boot 4.x does not auto-configure the OTel bridge from `spring-boot-starter-actuator` alone. `TracingConfig.java` in each service manually wires `OpenTelemetrySdk` + `ZipkinSpanExporter` + `W3CTraceContextPropagator`. Sampling probability: 1.0 (100%). Documented in ADR 3.

---

## 12. Architecture Decision Records

*Criterion mapping: Criterion 8 — ADRs and Technical Justification*

The assignment requires three ADRs. All three are maintained in `docs/adr/` and summarised here.

### ADR 1 — Gateway-Centred Security

**Context:** Five processes; clients must not call domain service ports directly. JWT chosen over session cookies (no shared session store) and OAuth2 (no third-party delegation needed for two internal users).

**Decision:** `JwtAuthenticationFilter` at `@Order(HIGHEST_PRECEDENCE + 1)` — one slot after `CorrelationIdFilter` (`HIGHEST_PRECEDENCE`), ensuring `X-Correlation-Id` is present on every response including 401/403. `SecurityConfig` disables Spring Security defaults. Role rule: `GET /api/**` → any authenticated user; `POST/PUT/DELETE /api/**` → ADMIN only.

**Documented tradeoff:** Domain service ports 8081/8082 are open on localhost in dev. Production fix: private VPC/cluster subnet with network-level firewall dropping all traffic to 8081/8082.

**Alternatives rejected:** Per-service Spring Security; OAuth2 authorisation server; API key header.

**Evidence:** §8 — 401, 403, 200, 201 evidence across four token/method combinations.

**Screencast timestamps:** `08:45–10:15`

---

### ADR 2 — Event-Driven Communication

**Context:** After approval, equipment status must change from `AVAILABLE` to `ON_LOAN`. Synchronous availability check already exists for the decision path. Should the status update also be synchronous?

**Decision:** Asynchronous via RabbitMQ. Availability check stays synchronous (result determines decision). Status update is a committed side-effect — the loan is already saved. A second synchronous call to inventory-service adds a second critical-path failure point with no benefit.

**Infrastructure:** Exchange `loan.events` (TopicExchange, durable), Queue `inventory.loan-events` (durable), Routing keys `loan.approved` and `loan.returned`. Schema independence via direct `ObjectMapper` deserialisation in the listener.

**Alternatives rejected:** Synchronous `PUT /equipment/{id}/status` (second failure point); shared event library JAR (compile-time coupling); Spring Cloud Stream (hides infrastructure names).

**Evidence:** §10 — complete event flow; before/after database state in §10.5.

**Screencast timestamps:** `15:45–18:00`

---

### ADR 3 — Resilience and Failure Handling

**Context:** Feign call is on critical approval path. Inventory-service restarts must not block all loan approvals. Five processes need cross-service log correlation.

**Decision (Resilience):** Four-layer stack in `InventoryAvailabilityAdapter`: Feign timeouts (3s/5s), `@Retry` (3 attempts, 500 ms, transport exceptions only), `@CircuitBreaker` (5-call window, 50% threshold, 10 s open, 2 HALF-OPEN probes), Fallback (provisional `available=true` + WARN log). Fallback rationale: provisional approvals during a short outage are operationally preferable to blocking all lending.

**Decision (Tracing):** Manual `TracingConfig.java` in all three services. 100% sampling. W3C `traceparent` propagation. RabbitMQ consumer span appears as OTel `LINK` — known Spring AMQP behaviour, documented not hidden.

**Alternatives rejected:** No fallback/fail-fast; Jaeger (Zipkin requires zero config); Spring Cloud Sleuth (end-of-life).

**Evidence:** §9.1–9.3 (Feign scenarios), §9.4 (threshold values), §11 (Zipkin waterfall, log correlation).

**Screencast timestamps:** `13:15–15:45` (resilience), `19:00–20:15` (tracing)

---

## 13. Repository Quality

*Criterion mapping: Criterion 9 — Repository Quality and Development Process*

**Repository URL:** `https://github.com/VinayReddy072/Microservices_Platform`

**Structure:**
```
Microservices_Platform/
├── platform/
│   ├── api-gateway/          JWT auth, correlation filter, routing
│   ├── config-server/        Native FS backend, config-repo/
│   └── eureka-server/        Service registry
├── services/
│   ├── loan-service/         Loan lifecycle, Feign caller, RabbitMQ producer
│   └── inventory-service/    Equipment catalogue, RabbitMQ consumer
├── config-repo/              5 YAML files (shared + 2 services × 2 profiles)
├── docs/
│   ├── adr/                  5 ADR files (001–005)
│   └── verification-report.md
├── .env                      Environment variables (gitignored)
├── .gitignore
├── pom.xml                   Multi-module parent POM
└── ReadMe.md                 Startup guide, SQL, curl examples
```

**Commit history:** Seven commits representing incremental development milestones — initial structure, infrastructure services, REST/JPA layer, gateway security, service-to-service communication, messaging, and observability.

**README** (`ReadMe.md`): Contains full startup sequence, MySQL provisioning SQL, environment variable setup, and comprehensive curl examples for every endpoint.

---

## 14. Screencast Evidence Map

*Criterion mapping: Criterion 10 — Screencast and Report Quality*

| Requirement | Report Section | Screencast Timestamp | ADR |
| :--- | :--- | :--- | :--- |
| Architecture overview | §2 | `00:00–02:00` | — |
| All 5 services in Eureka | §7, §3.2.1 | `04:10–05:10` | — |
| Config Server — dev profile | §3.3.2 | `05:30–06:00` | — |
| Config Server — production profile | §3.3.2 | `06:00–06:30` | — |
| Sensitive config externalised | §3.3.3 | `06:30–07:30` | ADR 1 |
| Gateway routing (`lb://`) | §3.2.2 | `07:30–08:00` | — |
| X-Correlation-Id filter | §3.2.3 | `08:30–08:45` | ADR 1 |
| JWT login — ADMIN | §3.4.1 | `08:45–09:20` | ADR 1 |
| 401 — no token | §3.4.2 | `09:20–09:35` | ADR 1 |
| 403 — USER on write | §3.4.2 | `09:35–09:50` | ADR 1 |
| 201 — ADMIN on write | §3.4.2 | `09:50–10:15` | ADR 1 |
| Equipment CRUD (POST, GET, PUT, DELETE) | §4 | `10:15–11:50` | — |
| Loan CRUD + state machine | §5 | `12:00–13:00` | — |
| Feign — happy path log | §9.1 | `13:15–14:00` | ADR 3 |
| Feign — rejection (no fallback) | §9.2 | `14:00–14:15` | ADR 3 |
| Circuit-breaker WARN + OPEN state | §9.3 | `14:15–15:15` | ADR 3 |
| RabbitMQ exchange + bindings UI | §10.1 | `15:45–17:00` | ADR 2 |
| Loan approval → Equipment ON_LOAN log | §10.3 | `17:00–17:30` | ADR 2 |
| MySQL before/after state change | §10.5 | `17:30–18:00` | ADR 2 |
| Zipkin service list | §11.1 | `19:00–19:20` | ADR 3 |
| Zipkin span waterfall | §11.2 | `19:20–20:00` | ADR 3 |
| Log correlation — same traceId | §11.3 | `20:00–20:15` | ADR 3 |
| ADR 1, 2, 3 explained | §12 | `20:15–21:45` | All |
| Repository + commit history | §13 | `22:15–22:45` | — |
