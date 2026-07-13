# Emergency Equipment Lending Platform

> **Days 1–5 scaffold** — Eureka · Config Server · domain CRUD · Gateway routing · Feign + Resilience4J.
> Days 6–15 (JWT security, RabbitMQ messaging, OpenTelemetry/Zipkin tracing) are added in a separate phase once this foundation is verified working.

---

## Project Description

The **Emergency Equipment Lending Platform** is a microservices-based system that manages the lending of emergency equipment (defibrillators, stretchers, oxygen cylinders, etc.) to authorised borrowers. It is composed of two domain microservices and three platform infrastructure services:

| Module | Role |
|--------|------|
| **loan-service** (Service A) | Owns the full loan-request lifecycle: `PENDING → APPROVED → RETURNED`. Before approving a loan it calls inventory-service synchronously via OpenFeign to confirm the equipment is available. |
| **inventory-service** (Service B) | Owns the equipment catalogue. Exposes CRUD endpoints and a dedicated `/equipment/{id}/availability` endpoint consumed by loan-service. |
| **eureka-server** | Service registry — all services register here; loan-service resolves `lb://inventory-service` through it. |
| **config-server** | Centralised config server (native filesystem backend). Serves per-service, per-profile YAML from `config-repo/`. |
| **api-gateway** | Single reactive Spring Cloud Gateway entry point on port 8080. Routes `/api/loans/**` → loan-service and `/api/equipment/**` → inventory-service. Adds `X-Correlation-Id` to every request. |

---

## Confirmed Environment

| Tool | Required |
|------|---------|
| JDK | 25.x (run `java -version` and paste output here) |
| Maven | 3.9+ (run `mvn -version` and paste output here) |
| OS | Windows 10/11 or any POSIX shell (WSL / Git Bash) |
| Spring Boot | **4.1.0** (confirmed stable on start.spring.io, June 2026) |
| Spring Cloud | **2025.1.2** (confirmed compatible with Boot 4.1.0, June 2026) |
| PostgreSQL | 15+ |
| Docker (optional) | For running PostgreSQL via container |

> **Action required**: paste your `java -version` and `mvn -version` output into this section and replace the placeholder rows above.

---

## One-Time Setup

### 1 — Initialise Git

```bash
cd "C:\Users\SAI VIKRANTH TEJ\Desktop\Microservices_Platform"
git init
git add .
git commit -m "chore: initial project scaffold — Days 1-5"
git remote add origin https://github.com/VinayReddy072/Microservices_Platform   # replace with your GitHub/GitLab URL
git push -u origin main
```

### 2 — Start PostgreSQL

**Option A — Docker (recommended for local dev):**

```bash
docker run -d \
  --name postgres-emergency \
  -e POSTGRES_USER=admin \
  -e POSTGRES_PASSWORD=admin123 \
  -e POSTGRES_DB=postgres \
  -p 5432:5432 \
  postgres:16-alpine
```

**Option B — Native PostgreSQL install:**

```bash
# Windows: open "SQL Shell (psql)" from the Start Menu
# or run:
psql -U postgres
```

### 3 — Create Databases and Users

Connect to PostgreSQL and run:

```sql
-- Loan service database
CREATE DATABASE loan_db;
CREATE USER loan_user WITH ENCRYPTED PASSWORD 'loan_pass';
GRANT ALL PRIVILEGES ON DATABASE loan_db TO loan_user;

-- Inventory service database
CREATE DATABASE inventory_db;
CREATE USER inventory_user WITH ENCRYPTED PASSWORD 'inventory_pass';
GRANT ALL PRIVILEGES ON DATABASE inventory_db TO inventory_user;
```

### 4 — Export Environment Variables (per shell session)

**Windows PowerShell:**

```powershell
$env:LOAN_DB_URL      = "jdbc:postgresql://localhost:5432/loan_db"
$env:LOAN_DB_USER     = "loan_user"
$env:LOAN_DB_PASS     = "loan_pass"
$env:INVENTORY_DB_URL = "jdbc:postgresql://localhost:5432/inventory_db"
$env:INVENTORY_DB_USER= "inventory_user"
$env:INVENTORY_DB_PASS= "inventory_pass"
$env:CONFIG_REPO_PATH = "file:///C:/Users/SAI VIKRANTH TEJ/Desktop/Microservices_Platform/config-repo"
```

**Git Bash / WSL:**

```bash
export LOAN_DB_URL="jdbc:postgresql://localhost:5432/loan_db"
export LOAN_DB_USER="loan_user"
export LOAN_DB_PASS="loan_pass"
export INVENTORY_DB_URL="jdbc:postgresql://localhost:5432/inventory_db"
export INVENTORY_DB_USER="inventory_user"
export INVENTORY_DB_PASS="inventory_pass"
export CONFIG_REPO_PATH="file:///C:/Users/SAI VIKRANTH TEJ/Desktop/Microservices_Platform/config-repo"
```

> **Tip:** Create a `.env` file (it is `.gitignore`d) and source it each session with `source .env` on Bash or import it in PowerShell.

### 5 — First Build

From the project root, compile and install all 5 modules:

```bash
cd "C:\Users\SAI VIKRANTH TEJ\Desktop\Microservices_Platform"
mvn clean install -DskipTests
```

Expected output (no errors):
```
[INFO] Building Emergency Equipment Lending Platform — Parent
[INFO] Building Emergency Equipment Lending — Eureka Server        BUILD SUCCESS
[INFO] Building Emergency Equipment Lending — Config Server        BUILD SUCCESS
[INFO] Building Emergency Equipment Lending — API Gateway          BUILD SUCCESS
[INFO] Building Emergency Equipment Lending — Loan Service         BUILD SUCCESS
[INFO] Building Emergency Equipment Lending — Inventory Service    BUILD SUCCESS
[INFO] BUILD SUCCESS
```

> **Spring Boot 4.x breaking changes already applied in this project** (documented here for reference):
> - `spring-boot-starter-aop` was **renamed** to `spring-boot-starter-aspectj` in Spring Boot 4.0
> - `resilience4j-spring-boot3` was **replaced** by `resilience4j-spring-boot4` (v2.4.0) for Boot 4.x
> - **Lombok** requires an explicit `<annotationProcessorPaths>` entry in `maven-compiler-plugin` with JDK 17+ — relying on `<optional>true</optional>` alone no longer works
> - All three fixes are already applied in [pom.xml](pom.xml). If you add a new module, copy the `maven-compiler-plugin` block from the parent.

> **IDE null analysis popup** — If your IDE asks *"Null annotation types detected — enable null analysis?"*, click **No**. This is triggered by JSR-305 annotations inside Spring/Lombok JARs and is not relevant to this project.

---

## Running Order

> **Why order matters:** Eureka must be up before any client tries to register. Config Server must be up before domain services pull their config on startup. Gateway can only route to services that have already registered.

```
1. eureka-server        → http://localhost:8761
2. config-server        → http://localhost:8888
3. inventory-service    → http://localhost:8082
4. loan-service         → http://localhost:8081
5. api-gateway          → http://localhost:8080
```

Start each in a separate terminal:

```bash
# Terminal 1
cd platform/eureka-server && mvn spring-boot:run

# Terminal 2
cd platform/config-server && mvn spring-boot:run

# Terminal 3
cd services/inventory-service && mvn spring-boot:run

# Terminal 4
cd services/loan-service && mvn spring-boot:run

# Terminal 5
cd platform/api-gateway && mvn spring-boot:run
```

Or build JARs first with `mvn clean package -DskipTests` and run:

```bash
java -jar platform/eureka-server/target/eureka-server-1.0.0-SNAPSHOT.jar
java -jar platform/config-server/target/config-server-1.0.0-SNAPSHOT.jar
java -jar services/inventory-service/target/inventory-service-1.0.0-SNAPSHOT.jar
java -jar services/loan-service/target/loan-service-1.0.0-SNAPSHOT.jar
java -jar platform/api-gateway/target/api-gateway-1.0.0-SNAPSHOT.jar
```

---

## Day 1 — Skeleton + Eureka Server

### What to do
- Confirm `mvn clean install -DskipTests` passes from the project root.
- Start `eureka-server` and verify the dashboard.

### Commands & Verification

```bash
# Build everything
mvn clean install -DskipTests

# Start Eureka
cd platform/eureka-server
mvn spring-boot:run
```

Open [http://localhost:8761](http://localhost:8761) — you should see the Eureka dashboard with **"No instances currently registered with Eureka"** (nothing is registered yet).

**Screenshot/observe:** Eureka dashboard loading without errors.

### Git commit

```bash
git add .
git commit -m "feat: Day 1 — project skeleton and Eureka server"
git push
```

---

## Day 2 — Config Server

### What to do
- Start config-server (Eureka must already be running).
- Verify config-server registers with Eureka.
- Confirm property files are served correctly.

### Commands & Verification

```bash
cd platform/config-server
mvn spring-boot:run
```

Test config serving (in a new terminal):

```bash
# Shared application config
curl http://localhost:8888/application/default

# Loan service dev config
curl http://localhost:8888/loan-service/dev

# Inventory service production config
curl http://localhost:8888/inventory-service/production
```

Expected: JSON response with `propertySources` array containing the YAML values.

**Screenshot/observe:**
- Config-server appears in the Eureka dashboard under `CONFIG-SERVER`.
- `curl http://localhost:8888/loan-service/dev` returns `datasource.url`, `ddl-auto: update`, `logging.level: DEBUG`.

### Git commit

```bash
git add .
git commit -m "feat: Day 2 — Config Server with native filesystem backend"
git push
```

---

## Day 3 — Domain Service CRUD

### What to do
- Start `inventory-service` and `loan-service` (Eureka + Config Server must be running).
- Exercise every CRUD endpoint on both services.
- Confirm both services appear in the Eureka dashboard.

### Inventory Service — Port 8082

```bash
# CREATE equipment item
curl -s -X POST http://localhost:8082/equipment \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Portable Defibrillator",
    "category": "Cardiac",
    "location": "Station A",
    "conditionNotes": "Fully charged, PADs attached"
  }' | jq .

# GET all equipment
curl -s http://localhost:8082/equipment | jq .

# GET single item (replace 1 with the id returned above)
curl -s http://localhost:8082/equipment/1 | jq .

# CHECK AVAILABILITY (key endpoint called by loan-service)
curl -s http://localhost:8082/equipment/1/availability | jq .

# UPDATE item
curl -s -X PUT http://localhost:8082/equipment/1 \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Portable Defibrillator MkII",
    "category": "Cardiac",
    "location": "Station B",
    "conditionNotes": "Recertified 2026-07"
  }' | jq .

# Validation failure — name is blank (expect HTTP 400 with field-level errors)
curl -s -X POST http://localhost:8082/equipment \
  -H "Content-Type: application/json" \
  -d '{"name": "", "category": ""}' | jq .

# DELETE item
curl -s -X DELETE http://localhost:8082/equipment/1
```

### Loan Service — Port 8081

```bash
# CREATE a loan request
curl -s -X POST http://localhost:8081/loans \
  -H "Content-Type: application/json" \
  -d '{
    "equipmentItemId": 1,
    "borrowerName": "Alice Smith",
    "borrowerContact": "alice@hospital.nhs.uk"
  }' | jq .

# GET all loans
curl -s http://localhost:8081/loans | jq .

# GET single loan (replace 1 with returned id)
curl -s http://localhost:8081/loans/1 | jq .

# APPROVE loan (triggers Feign call to inventory-service)
curl -s -X PUT http://localhost:8081/loans/1/approve | jq .

# RETURN loan
curl -s -X PUT http://localhost:8081/loans/1/return | jq .

# Validation failure — missing required fields (expect HTTP 400 field-level map)
curl -s -X POST http://localhost:8081/loans \
  -H "Content-Type: application/json" \
  -d '{"equipmentItemId": null, "borrowerName": "", "borrowerContact": ""}' | jq .

# DELETE loan
curl -s -X DELETE http://localhost:8081/loans/1

# 404 — non-existent resource
curl -s http://localhost:8081/loans/999 | jq .
```

**Screenshot/observe:**
- Both services appear in Eureka dashboard.
- POST returns HTTP 201 with the created entity (including generated `id`).
- Blank-name POST returns HTTP 400 with per-field validation errors (not a generic Spring error body).
- GET /999 returns HTTP 404 with `"message": "LoanRequest not found: 999"`.

### Git commit

```bash
git add .
git commit -m "feat: Day 3 — domain CRUD for loan-service and inventory-service"
git push
```

---

## Day 4 — API Gateway Routing & Discovery

### What to do
- Start `api-gateway` (all other services must be running).
- Verify all requests routed through the gateway work correctly.
- Observe `X-Correlation-Id` header in responses.

### Gateway — Port 8080

```bash
# Route /api/equipment/** → inventory-service /equipment/**
curl -s http://localhost:8080/api/equipment | jq .

# Create equipment via gateway
curl -s -X POST http://localhost:8080/api/equipment \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Oxygen Cylinder",
    "category": "Respiratory",
    "location": "Ambulance Bay 3",
    "conditionNotes": "Full 100%"
  }' | jq .

# Route /api/loans/** → loan-service /loans/**
curl -s http://localhost:8080/api/loans | jq .

# Create a loan via gateway
curl -s -X POST http://localhost:8080/api/loans \
  -H "Content-Type: application/json" \
  -d '{
    "equipmentItemId": 1,
    "borrowerName": "Dr. Ben Carter",
    "borrowerContact": "b.carter@ambulance.nhs.uk"
  }' | jq .

# Approve via gateway
curl -s -X PUT http://localhost:8080/api/loans/1/approve | jq .

# Check X-Correlation-Id header is present in response
curl -s -I http://localhost:8080/api/equipment | grep -i correlation
```

**Screenshot/observe:**
- All gateway routes resolve correctly (no 404s at gateway level).
- `X-Correlation-Id` header appears in every response.
- Gateway logs show: `"Incoming request: GET /api/equipment correlationId=<uuid>"`.

> **Note on port exposure:** During development and demonstration, domain service ports 8081 and 8082 are left open locally so you can test direct access vs. gateway access and kill a service mid-demo. In a real deployment, these ports would be firewalled/network-restricted (e.g., within a private VPC or Kubernetes pod network) so the API Gateway on port 8080 is the **sole externally reachable entry point**. This distinction should be noted explicitly in the project report.

### Git commit

```bash
git add .
git commit -m "feat: Day 4 — API Gateway routes and CorrelationIdFilter"
git push
```

---

## Day 5 — Feign Client + Resilience4J

### What to do
- Verify the Feign + Resilience4J stack when approving a loan.
- Test the circuit-breaker fallback by stopping inventory-service.

### Test resilience patterns

```bash
# Normal approval (inventory-service running) — should call Feign and succeed
curl -s -X POST http://localhost:8081/loans \
  -H "Content-Type: application/json" \
  -d '{"equipmentItemId":1,"borrowerName":"Jane Roe","borrowerContact":"jroe@fire.gov"}' | jq .

curl -s -X PUT http://localhost:8081/loans/1/approve | jq .
# Expect: status=APPROVED, equipment availability checked

# ---- Now STOP inventory-service (Ctrl+C in its terminal) ----

# Create another loan request
curl -s -X POST http://localhost:8081/loans \
  -H "Content-Type: application/json" \
  -d '{"equipmentItemId":1,"borrowerName":"Tom Brown","borrowerContact":"t.brown@fire.gov"}' | jq .

# Attempt approve — Retry will fire 3 times, then CircuitBreaker will
# trip and invoke fallback (provisional approval + warning log)
curl -s -X PUT http://localhost:8081/loans/2/approve | jq .
# Expect: status=APPROVED with fallback message in logs:
#   "WARN  InventoryAvailabilityAdapter - inventory-service unreachable;
#    provisionally approving loan [id] — verify manually"

# After several failures, circuit breaker opens; subsequent calls fail fast
curl -s -X PUT http://localhost:8081/loans/3/approve | jq .
# Expect: immediate fallback (no retry delay) — circuit is OPEN
```

**Screenshot/observe:**
- loan-service logs show `Retry attempt 1/3`, `Retry attempt 2/3`, `Retry attempt 3/3` then the fallback warning.
- Actuator circuit-breaker state: `curl http://localhost:8081/actuator/circuitbreakers`
- After recovery (restart inventory-service), circuit transitions from OPEN → HALF_OPEN → CLOSED.

### Git commit

```bash
git add .
git commit -m "feat: Day 5 — Feign client + Resilience4J retry/circuit-breaker on inventory call"
git push
```

---

## Quick Troubleshooting

### Services not registering with Eureka

1. Confirm Eureka is running: `curl http://localhost:8761/eureka/apps`
2. Check service `application.yml` has:
   ```yaml
   eureka:
     client:
       service-url:
         defaultZone: http://localhost:8761/eureka/
   ```
3. Ensure `spring-cloud-starter-netflix-eureka-client` is on the classpath.
4. Look for `"Registering application ... with eureka"` in the service startup log.

### Config not applying

1. Confirm config-server is running: `curl http://localhost:8888/actuator/health`
2. Check `spring.config.import` in the service's `application.yml`:
   ```yaml
   spring:
     config:
       import: "optional:configserver:http://localhost:8888"
   ```
3. Verify the config-repo files are named exactly `<service-name>-<profile>.yml` (e.g. `loan-service-dev.yml`).
4. Check `CONFIG_REPO_PATH` env var is set and the path uses the correct OS separator.

### PostgreSQL connection refused

1. Check Postgres is running: `psql -U postgres -c "\l"`
2. Verify the database and user exist (Step 3 of One-Time Setup).
3. Confirm env vars are exported in your current shell session (they don't persist across new terminals).
4. Check `LOAN_DB_URL` / `INVENTORY_DB_URL` are set: `echo $env:LOAN_DB_URL` (PowerShell) or `echo $LOAN_DB_URL` (Bash).

### Feign client not resolving the target service

1. Confirm inventory-service is registered in Eureka: visit [http://localhost:8761](http://localhost:8761) and look for `INVENTORY-SERVICE`.
2. Check loan-service `InventoryClient` uses the exact service name: `@FeignClient(name = "inventory-service")`.
3. Confirm `spring.application.name=inventory-service` in inventory-service's `application.yml`.
4. Check load-balancer dependency: `spring-cloud-starter-loadbalancer` must be on loan-service's classpath.

---

## Rubric Coverage Checklist

| Rubric Criterion | In Scope (Days 1–5)? | Status | Evidence / "Excellent" band |
|-----------------|----------------------|--------|----------------------------|
| Multi-service architecture (2+ domain services) | ✅ Yes | ✅ Complete | `loan-service` (port 8081) + `inventory-service` (port 8082) — separate Spring Boot apps, separate databases (`loan_db`, `inventory_db`), separate schemas |
| Service discovery (Eureka) | ✅ Yes | ✅ Complete | All services register with Eureka; `lb://` URIs used in Gateway routes and Feign client — no hardcoded IPs |
| Centralised config (Config Server) | ✅ Yes | ✅ Complete | Native filesystem backend; dev vs production profiles with meaningful differences (`ddl-auto: update` vs `validate`, `DEBUG` vs `INFO`); `${ENV_VAR:default}` placeholders for secrets |
| API Gateway as entry point | ✅ Yes | ✅ Complete | Explicit routes (not auto-discovery-locator); path rewrite (`/api/loans/**` → `/loans/**`); `CorrelationIdFilter` adds/propagates `X-Correlation-Id` on every request |
| Gateway is sole external entry point | ✅ Yes | ✅ Noted | Direct ports (8081, 8082) intentionally left open for local demo — report states these would be firewalled in production |
| Service-to-Service communication + Resilience | ✅ Yes | ✅ Complete — **Excellent** | Four-layer resilience stack on `InventoryAvailabilityAdapter.checkAvailability()`: (1) Feign timeout config, (2) `@Retry` restricted to `IOException`/`RetryableException`, (3) `@CircuitBreaker` with configurable failure-rate threshold, (4) fallback that provisionally approves + logs warning |
| Domain-driven REST API (CRUD) | ✅ Yes | ✅ Complete | Full CRUD on both services; `@Valid` on POST; per-field `MethodArgumentNotValidException` handler (HTTP 400 field→message map); `EntityNotFoundException` → HTTP 404 |
| JWT Security | ❌ Deferred (Days 6–7) | ⏳ Pending | Not in this phase — requires JWT infrastructure. Will be added with Spring Security + token issuance service. |
| Async messaging (RabbitMQ) | ❌ Deferred (Days 7–8) | ⏳ Pending | `TODO` comments placed at integration points. Not scaffolded — RabbitMQ container not started in this phase. |
| Distributed tracing (Zipkin/OpenTelemetry) | ❌ Deferred (Days 9–10) | ⏳ Pending | Zipkin not started. Tracing instrumentation added after messaging phase. |
| Architecture Decision Records (ADRs) | ❌ Deferred | ⏳ Pending | `docs/` directory created. ADRs written during later phases. |
| Architecture diagram | ❌ Deferred | ⏳ Pending | `docs/` directory created. Diagram added after all services are implemented. |
| Screencast / demo video | ❌ Deferred | ⏳ Pending | Recorded after Days 6–15 features are complete. |
