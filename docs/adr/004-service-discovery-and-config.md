# ADR 004 - Service Discovery and Centralised Configuration

## Status
Accepted

## Context

The platform deploys five Spring Boot processes across specific ports. Two separate but related infrastructure concerns must be addressed:

**Service Discovery:** The API Gateway routes client requests to `loan-service` and `inventory-service`. Loan Service also calls Inventory Service via Feign. With hardcoded URLs (e.g., `http://localhost:8081`), any port change, horizontal scaling, or host change requires manual edits and redeploys of every dependent service. Consumers need to locate services by logical name, not physical address.

**Configuration Management:** Services share values (Eureka URL, RabbitMQ host, Zipkin endpoint, sampling probability) that would otherwise be duplicated across five `application.yml` files. Environment-specific differences (verbose SQL in dev, minimal logging in production) must be expressible without building different JAR files. Credentials must not be committed to version control.

## Decision

### Part 1 - Eureka Service Discovery

Spring Cloud Netflix Eureka is deployed as `platform/eureka-server/` on port 8761. All five processes register at startup and heartbeat every 10 seconds. The API Gateway and Loan Service resolve downstream addresses using `lb://` (load-balanced) URIs, which Spring Cloud LoadBalancer resolves against the Eureka registry at call time.

**Eureka Server configuration:**

```yaml
# platform/eureka-server/src/main/resources/application.yml
server:
  port: 8761
eureka:
  instance:
    hostname: localhost
  client:
    register-with-eureka: false    # Server does not register itself
    fetch-registry: false
  server:
    enable-self-preservation: false      # Immediate removal in dev (no lingering ghosts)
    eviction-interval-timer-in-ms: 5000  # Check every 5 s
```

`enable-self-preservation: false` is set deliberately for local development. In production, self-preservation protects against false de-registrations due to temporary network partitions; it would be re-enabled.

**Client registration (shared via `config-repo/application.yml`):**

```yaml
eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
  instance:
    prefer-ip-address: true
    lease-renewal-interval-in-seconds: 10
    lease-expiration-duration-in-seconds: 30
```

**Discovery-based routing in API Gateway (`platform/api-gateway/src/main/resources/application.yml`):**

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          routes:
            - id: inventory-service-route
              uri: lb://inventory-service          # ← Eureka-resolved
              predicates:
                - Path=/api/equipment/**
              filters:
                - RewritePath=/api/equipment(?<segment>/?.*), /equipment${segment}

            - id: loan-service-route
              uri: lb://loan-service               # ← Eureka-resolved
              predicates:
                - Path=/api/loans/**
              filters:
                - RewritePath=/api/loans(?<segment>/?.*), /loans${segment}
```

**OpenFeign also resolves via Eureka:**

```java
// services/loan-service/.../client/InventoryClient.java
@FeignClient(name = "inventory-service")   // "inventory-service" = Eureka registration name
public interface InventoryClient {
    @GetMapping("/equipment/{id}/availability")
    EquipmentAvailabilityDto checkAvailability(@PathVariable("id") Long equipmentItemId);
}
```

No hardcoded `http://localhost:8082` appears anywhere in the codebase.

### Part 2 - Spring Cloud Config

Config Server is deployed as `platform/config-server/` on port 8888 using the native filesystem backend pointing at `config-repo/`. Each service pulls configuration at startup via `spring.config.import: "optional:configserver:http://localhost:8888"`. The `optional:` prefix allows services to start with embedded fallback values if Config Server is temporarily unavailable.

**Files in `config-repo/`:**

| File | Applies to |
|------|-----------|
| `application.yml` | All services — Eureka, RabbitMQ, Zipkin, tracing, log pattern |
| `loan-service-dev.yml` | Loan Service dev: `ddl-auto=update`, `show-sql=true`, `feign.logger-level=FULL` |
| `loan-service-production.yml` | Loan Service prod: `ddl-auto=validate`, `show-sql=false` |
| `inventory-service-dev.yml` | Inventory Service dev |
| `inventory-service-production.yml` | Inventory Service prod |

**Sensitive value handling:** Configuration files contain `${ENV_VAR}` placeholders only. Actual values come from `.env` (gitignored):

```yaml
# config-repo/loan-service-dev.yml
spring:
  datasource:
    url: ${LOAN_DB_URL}
    username: ${LOAN_DB_USER}
    password: ${LOAN_DB_PASS}
```

**Verification:**

```bash
curl http://localhost:8888/loan-service/dev
# Returns: ddl-auto=update, show-sql=true

curl http://localhost:8888/loan-service/production
# Returns: ddl-auto=validate, show-sql=false
```

`CONFIG_REPO_PATH` environment variable overrides the config directory path, allowing the platform to run from any working directory without code changes.

## Alternatives Considered

**Hardcoded service URLs** - changes to any service's port require edits to every dependent service's configuration and a redeploy. No load balancing across multiple instances. Rejected.

**Kubernetes DNS-based discovery** - stable DNS names within a Kubernetes cluster (e.g., `http://loan-service.default.svc.cluster.local`). The correct production approach. For a local development environment without a container orchestrator, Eureka is simpler and provides a visual dashboard for verification. Rejected for this environment, not for production.

**Consul** - stronger service mesh capabilities (health checking, key-value store, multi-datacenter). Adds a separate agent process. Rejected: Eureka is already included in the Spring Cloud Netflix stack; no additional process or configuration is needed.

**Per-service `application.yml` only** - no Config Server. Changing a shared value (e.g., RabbitMQ host) requires editing and redeploying all five services. No queryable configuration endpoint. Rejected.

**Environment variables alone** - valid for secrets but does not support per-service profile overlays or a central queryable configuration state (`curl http://localhost:8888/application/default`). Config Server provides a superset.

## Consequences

**Positive:** Services resolve each other by logical name — no hardcoded host:port. One shared YAML file to update when infrastructure changes. Per-service profiles enable identical JARs to run with different behaviour in dev vs. production. Credentials never in version control.

**Negative:** Two additional processes (Eureka, Config Server) must start before domain services. `optional:` import mitigates the Config Server ordering dependency but means services start with stale fallback values if Config Server is down. Eureka self-preservation disabled in dev — re-enable in production. Config Server native filesystem backend (`active: native`) must be replaced with the Git backend for auditability in production.

## Implementation Artefacts

| File | Role |
|------|------|
| `platform/eureka-server/src/main/resources/application.yml` | `register-with-eureka: false`, `enable-self-preservation: false`, port 8761 |
| `config-repo/application.yml` | Shared Eureka URL, RabbitMQ, tracing, sampling, log pattern |
| `config-repo/loan-service-dev.yml` | Dev: `ddl-auto=update`, `show-sql=true`, Feign FULL logging |
| `config-repo/loan-service-production.yml` | Prod: `ddl-auto=validate`, `show-sql=false` |
| `platform/api-gateway/src/main/resources/application.yml` | `uri: lb://inventory-service`, `lb://loan-service` |
| `platform/config-server/src/main/resources/application.yml` | `profiles.active: native`, `search-locations: ${CONFIG_REPO_PATH}` |
| `services/loan-service/.../client/InventoryClient.java` | `@FeignClient(name = "inventory-service")` |

## Report Evidence

- Eureka dashboard showing all 5 processes UP
- Config Server multi-profile curl output
- Gateway route table with `lb://` URIs

## Screencast Timestamps

- `[00:02:45]` - Eureka dashboard at http://localhost:8761 - all 5 services UP
- `[00:05:19]` - `curl http://localhost:8888/loan-service/dev` vs `production` - different DDL values
