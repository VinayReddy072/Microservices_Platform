# ADR 005 — Database per Service and Cross-Service Data Access Patterns

## Status
Accepted

## Context

Both Loan Service and Inventory Service require persistent storage. The simplest approach is a single shared MySQL schema — all tables in one database, all services connecting to the same credentials. This is the standard approach for a monolith.

In a microservices architecture this creates coupling at the infrastructure layer:
- A schema change in `equipment_items` (e.g., renaming a column) can break a Loan Service ORM mapping if Loan Service's entities reference inventory tables.
- Both services share a single connection pool and MySQL server load.
- There is no enforced ownership — any service can read or write any table, turning API boundaries into polite conventions rather than hard constraints.

The platform has one additional cross-service concern: when Loan Service approves a loan, it needs to: (a) check equipment availability before committing, and (b) update equipment status after committing. These two operations must traverse the service boundary. The mechanism chosen for each has significant architectural consequences.

## Decision

### Separate Databases with Separate Users

Each domain microservice has its own MySQL schema and a dedicated user account with `GRANT ALL PRIVILEGES` on its own schema only:

| Service | Database | User | Password source | Tables |
|---------|----------|------|-----------------|--------|
| Loan Service | `loan_db` | `loan_user` | `${LOAN_DB_PASS}` | `loan_requests` |
| Inventory Service | `inventory_db` | `inventory_user` | `${INVENTORY_DB_PASS}` | `equipment_items` |

`loan_user` has no privileges on `inventory_db`, and `inventory_user` has no privileges on `loan_db`. This is enforced at the MySQL permission level — a cross-service SQL JOIN would be rejected by the database, not just by convention.

Both databases use `utf8mb4 / utf8mb4_unicode_ci` for international character support in borrower names and equipment condition notes.

**Datasource configuration (from Config Server, `config-repo/loan-service-dev.yml`):**

```yaml
spring:
  datasource:
    url: ${LOAN_DB_URL}            # jdbc:mysql://localhost:3306/loan_db?useSSL=false...
    username: ${LOAN_DB_USER}      # loan_user
    password: ${LOAN_DB_PASS}
  jpa:
    hibernate:
      ddl-auto: update             # dev — auto-create/update tables
    show-sql: true
```

### Cross-Service Data Access Patterns

Cross-service reads and writes use proper API and event boundaries — no SQL joins across schemas, no shared ORM entities:

| Operation | Mechanism | Justification |
|-----------|-----------|--------------|
| Loan Service reads equipment availability | Feign: `GET /equipment/{id}/availability` | Must be synchronous — the availability result determines the approval decision in the same request |
| Equipment status change after approval | RabbitMQ `loan.approved` event | A side effect — does not need to block the approval response (see ADR 002) |
| Equipment status change after return | RabbitMQ `loan.returned` event | Same rationale |

**Live evidence — equipment status driven entirely by RabbitMQ events:**

`inventory_db.equipment_items` (live data):

| id | name | status | Updated by |
|----|------|--------|-----------|
| 1 | Portable Defibrillator | ON_LOAN | `loan.approved` for loan 1 (Alice Smith, 2026-07-19) |
| 2 | Oxygen Cylinder | ON_LOAN | `loan.approved` for loan 2 (John Doe, 2026-07-20) |
| 3 | Portable Oxygen Cylinder | AVAILABLE | Never loaned |
| 4 | EMS Resuscitation Kit | ON_LOAN | `loan.approved` for loan 3 (2026-07-27) |
| 5 | Defib Unit | AVAILABLE | Loan 5 REJECTED (item 1 was ON_LOAN — item 5 untouched) |
| 6 | AED Unit | ON_LOAN | `loan.approved` for loan 6 (Trace Tester, 2026-07-28) |

No `PUT /equipment/{id}/status` endpoint exists. The only path by which `equipment_items.status` changes is `LoanEventListener.handleLoanEvent()` — responding to a RabbitMQ message. The database is not a shared integration layer.

`loan_db.loan_requests` (live data):

| id | borrower_name | equipment_item_id | status | approved_at |
|----|---------------|-------------------|--------|-------------|
| 1 | Alice Smith | 1 | APPROVED | 2026-07-19 06:58:23 |
| 2 | John Doe | 2 | APPROVED | 2026-07-20 13:05:52 |
| 3 | Man | 4 | APPROVED | 2026-07-27 13:05:46 |
| 4 | Dr. Smith | 1 | APPROVED | 2026-07-27 14:02:24 |
| 5 | Trace Tester | 1 | REJECTED | (no approved_at) |
| 6 | Trace Tester | 6 | APPROVED | 2026-07-28 12:55:49 |

`loan_requests` contains no equipment-specific columns beyond `equipment_item_id` (a logical reference, not a foreign key to `inventory_db`). Equipment name, category, and location are never duplicated in `loan_db`.

## Alternatives Considered

**Shared database, single schema** — all tables in one MySQL database. Both services connect to the same credentials. Rejected: a schema migration in `equipment_items` could break `LoanRequest` JPA mappings; both services are deployed together or risk schema mismatch; the ownership boundary is a convention, not an enforcement.

**Shared database, separate schemas on the same instance** — logical separation but same MySQL server. Services could still issue cross-schema JOINs if misconfigured. Rejected in favour of separate databases with per-user permission enforcement.

**Synchronous REST call for equipment status update** — `Loan Service → PUT /equipment/{id}/status` after approval. Rejected: creates a second synchronous failure point on the approval path; an Inventory Service restart would fail loan approvals even though the loan decision was already made. See ADR 002 for the full comparison.

**Shared ORM entity library** — a common JAR defining `EquipmentItem` and `LoanRequest` as `@Entity` classes. Rejected: compile-time coupling between services; both must be rebuilt simultaneously on any entity change; defeats the independent deployability that is the primary benefit of the microservices approach.

**Polyglot persistence (different DB engines per service)** — e.g., Loan Service on PostgreSQL, Inventory Service on MongoDB. The full expression of the Database-per-Service pattern. Rejected for this system: both services have relational data with simple, well-defined schemas. Introducing different database engines adds operational complexity without a data-model-driven justification.

## Consequences

**Positive:** Schema changes in one service cannot break another. Services can be deployed, migrated, and scaled independently. Database permissions enforce ownership at infrastructure level. Cross-service data access is always through versioned APIs or events — both are observable, testable, and auditable.

**Negative:** Cross-service queries that would be a single SQL JOIN in a monolith (e.g., "list all loans with the full equipment name") require an API call or a denormalised read model. Equipment status in `inventory_db` is eventually consistent with loan state in `loan_db` — there is a sub-second window after approval where equipment may still show `AVAILABLE`. For this domain, that window is accepted.

## Implementation Artefacts

| File | Role |
|------|------|
| `services/loan-service/.../entity/LoanRequest.java` | `@Entity @Table(name = "loan_requests")` — no reference to inventory tables |
| `services/inventory-service/.../entity/EquipmentItem.java` | `@Entity @Table(name = "equipment_items")` — no reference to loan tables |
| `config-repo/loan-service-dev.yml` | `datasource.url: ${LOAN_DB_URL}`, `username: ${LOAN_DB_USER}` |
| `config-repo/inventory-service-dev.yml` | `datasource.url: ${INVENTORY_DB_URL}`, `username: ${INVENTORY_DB_USER}` |
| `services/loan-service/.../client/InventoryClient.java` | Only cross-service read: `GET /equipment/{id}/availability` |
| `services/inventory-service/.../messaging/LoanEventListener.java` | Only cross-service write path: updates `equipment_items.status` on `loan.approved`/`loan.returned` |
| MySQL provisioning SQL (README §4) | `CREATE DATABASE loan_db`, `CREATE DATABASE inventory_db`, separate users, separate GRANT scopes |

## Report Evidence

- **Verification Report §4** — `inventory_db.equipment_items` live table (6 rows)
- **Verification Report §5** — `loan_db.loan_requests` live table (6 rows)
- **Verification Report §9.4** — Equipment status updated by RabbitMQ event, not REST
- **Verification Report §8.1** — Feign call as the only cross-service read pathway

## Screencast Timestamps

- `[00:XX:XX]` — MySQL Workbench: two separate databases `loan_db` and `inventory_db`
- `[00:XX:XX]` — `loan_db.loan_requests` — 6 rows, no equipment-specific columns beyond `equipment_item_id`
- `[00:XX:XX]` — `inventory_db.equipment_items` — item 6 status changes to ON_LOAN after loan 6 approved (no REST call, only RabbitMQ)
