# ADR 001 — Gateway-Centred Security

## Status
Accepted

## Context

The platform exposes five processes (Eureka, Config Server, API Gateway, loan-service, inventory-service). All external clients must be prevented from calling domain service ports directly. JWT was chosen over session cookies because microservice-to-microservice calls do not share a session store, and because the assignment targets a stateless API rather than a browser-session application.

## Decision

All authentication and role enforcement is handled exclusively by `JwtAuthenticationFilter` in the API Gateway. Domain services (loan-service on 8081, inventory-service on 8082) carry no security configuration and trust any request they receive.

The filter runs as a `WebFilter` at `Ordered.HIGHEST_PRECEDENCE + 1`, one position after `CorrelationIdFilter` (at `HIGHEST_PRECEDENCE`). This ordering guarantees that `X-Correlation-Id` is present on 401 and 403 responses as well as on forwarded requests, because the correlation filter always runs first.

Role rules enforced at the filter:
- `GET /api/**` — any valid token (USER or ADMIN).
- `POST`, `PUT`, `DELETE /api/**` — ADMIN role only.
- `POST /auth/login` — unauthenticated, handled by `AuthController`.

Tokens are HMAC-SHA256 signed using `JWT_SECRET` (a base64-encoded 32-byte key supplied as an environment variable). The gateway refuses to start if `JWT_SECRET` is absent — there is no silent fallback to an insecure default.

## Alternatives Considered

**Per-service Spring Security** — rejected because it duplicates configuration across services and means every new service must re-implement the same rules. A single filter at the gateway boundary is the canonical approach for an API gateway pattern.

**API key header** — rejected because API keys have no expiry mechanism and no role payload, making role-based access control impossible without a database lookup on every request.

## Consequences

**Positive:** Security logic lives in one file (`JwtAuthenticationFilter`). Adding a new route requires no change to the security configuration. Correlation IDs and trace IDs are present on every response including rejections.

**Negative:** Domain service ports (8081, 8082) are open on localhost during local development. A marker running their own test suite can bypass the gateway entirely by hitting those ports directly. In production, these ports would be firewalled at the network level, leaving the gateway as the only reachable entry point. This is noted in the README.

## Implementation Evidence

- `platform/api-gateway/src/.../security/JwtAuthenticationFilter.java` — `@Order(Ordered.HIGHEST_PRECEDENCE + 1)`, roles from `role` JWT claim.
- `platform/api-gateway/src/.../filter/CorrelationIdFilter.java` — `@Order(Ordered.HIGHEST_PRECEDENCE)`.
- `platform/api-gateway/src/.../auth/AuthController.java` — `POST /auth/login`.
- `platform/api-gateway/src/main/resources/application.yml` — `jwt.secret: ${JWT_SECRET}`.
