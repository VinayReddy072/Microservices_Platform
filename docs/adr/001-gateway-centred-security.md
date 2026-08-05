# ADR 001 — Gateway-Centred Security (JWT Authentication and Role-Based Access Control)

## Status
Accepted

## Context

The platform exposes five Spring Boot processes: Eureka Server (8761), Config Server (8888), API Gateway (8080), Loan Service (8081), and Inventory Service (8082). All client traffic must enter through the Gateway. JWT was chosen over session cookies because microservices do not share a session store, and over OAuth2 because the platform has a fixed, internal set of users - a separate authorisation server adds complexity with no benefit at this scale.

Two design constraints shaped the implementation:
1. Authentication must reject unauthenticated requests at the earliest possible point - before the routing pipeline, so that even 401/403 responses carry the `X-Correlation-Id` that `CorrelationIdFilter` generates.
2. Role rules must distinguish between read-only consumers (USER) and operators who mutate state (ADMIN), with no per-service duplication of these rules.

## Decision

All authentication and role enforcement is handled exclusively by `JwtAuthenticationFilter` in the API Gateway. Domain services carry no security configuration.

### Filter Ordering

```
HIGHEST_PRECEDENCE       → CorrelationIdFilter        (assigns X-Correlation-Id UUID)
HIGHEST_PRECEDENCE + 1   → JwtAuthenticationFilter    (validates JWT, enforces roles)
order -100               → Spring Security WebFilterChainProxy
```

`JwtAuthenticationFilter` runs at `Ordered.HIGHEST_PRECEDENCE + 1` , one slot after `CorrelationIdFilter`. This ordering is mandatory: if the JWT filter ran first, a 401 response would be returned before the correlation ID was set, making rejected requests un-traceable in logs. With the current ordering, every response - including rejections - carries `X-Correlation-Id`.

`SecurityConfig` explicitly disables Spring Security's reactive defaults (form login, HTTP Basic, CSRF, block-all rule) so that the JWT filter is the sole access-control mechanism:

```java
// platform/api-gateway/.../security/SecurityConfig.java
return http
    .csrf(ServerHttpSecurity.CsrfSpec::disable)
    .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
    .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
    .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
    .build();
```

Without this, Spring Security's autoconfiguration would convert 401 responses into 302 redirects to `/login` - breaking REST clients.

### Role Rules

```java
// platform/api-gateway/.../security/JwtAuthenticationFilter.java
private static final List<String> READ_METHODS = List.of("GET", "HEAD", "OPTIONS");

if (path.startsWith("/api/") && !READ_METHODS.contains(method) && !"ADMIN".equals(role)) {
    return shortCircuit(exchange, HttpStatus.FORBIDDEN, "ADMIN role required for " + method + " operations");
}
```

| Method | Path | USER token | ADMIN token | No token |
|--------|------|-----------|-------------|---------|
| GET | `/api/**` | ✅ 200 | ✅ 200 | ❌ 401 |
| POST | `/api/**` | ❌ 403 | ✅ 201 | ❌ 401 |
| PUT | `/api/**` | ❌ 403 | ✅ 200 | ❌ 401 |
| DELETE | `/api/**` | ❌ 403 | ✅ 204 | ❌ 401 |
| Any | `/auth/login` | — (public) | — (public) | ✅ |

Tokens are HMAC-SHA256 signed using `JWT_SECRET` (`${JWT_SECRET}` from environment variable). The gateway refuses to start if `JWT_SECRET` is absent. Expiry: 1 hour.

### Known Tradeoff - Local Port Exposure

During local development, domain service ports 8081 and 8082 are open on `localhost`. A client can bypass the gateway by calling `http://localhost:8081/loans` directly, bypassing JWT validation entirely. This would not be acceptable in production.

**How this would be closed in a real deployment:** Domain services would be deployed inside a private network (e.g., a Kubernetes cluster namespace or a VPC subnet) with no public ingress. Only the API Gateway's port would be exposed via a cloud load balancer or Kubernetes `Ingress`. Network-level firewall rules (security groups, Kubernetes `NetworkPolicy`) would drop all traffic to ports 8081/8082 originating outside the cluster. The gateway remains the only reachable entry point - not by convention, but by infrastructure enforcement. The current codebase is structured correctly for this deployment; the only thing absent is the network-level firewall itself, which is an infrastructure concern, not an application concern.

## Alternatives Considered

**Per-service Spring Security** - each service would independently parse and validate JWTs. Rejected because it duplicates the same HMAC-SHA256 parsing logic across every service and means adding a new service requires re-implementing the security rules. A single gateway filter is the canonical API gateway pattern.

**OAuth2 with a separate authorisation server** — correct for multi-tenant systems where third-party applications delegate access on behalf of users. Rejected for this platform: there are two built-in user accounts, no third-party client applications, and adding an authorisation server process increases operational complexity without business value at this scale.

**API key header** - no built-in expiry, no role payload without a database lookup on every request. Rejected.

## Consequences

**Positive:** Security logic in one file. Adding a new route requires no security change. `X-Correlation-Id` on every response including rejections. Domain services are simpler - no security dependencies.

**Negative:** Domain ports open on localhost in dev. Two built-in user accounts are hardcoded in `AuthController` - a production system would use a user store or LDAP.

## Implementation Artefacts

| File | Role |
|------|------|
| `platform/api-gateway/.../security/JwtAuthenticationFilter.java` | `@Order(HIGHEST_PRECEDENCE + 1)`, role from JWT `role` claim |
| `platform/api-gateway/.../security/SecurityConfig.java` | Disables Spring Security defaults |
| `platform/api-gateway/.../filter/CorrelationIdFilter.java` | `@Order(HIGHEST_PRECEDENCE)` — runs before JWT filter |
| `platform/api-gateway/.../auth/AuthController.java` | `POST /auth/login` — issues USER and ADMIN tokens |
| `platform/api-gateway/src/main/resources/application.yml` | `jwt.secret: ${JWT_SECRET}` |

## Report:

- `POST /auth/login` → `{"token":"eyJhbGciOiJIUzI1NiJ9..."}`
- No token → HTTP 401 `{"error":"Authentication required"}`
- USER token on POST → HTTP 403 `{"error":"ADMIN role required for POST operations"}`
- ADMIN token on POST → HTTP 201

## Screencast Timestamps

- `[00:09:36]` : Login as admin and user, show both tokens
- `[00:10:05]` : GET with USER token → 200; POST with USER token → 403
- `[00:10:50]` : POST with ADMIN token → 201; no token → 401
