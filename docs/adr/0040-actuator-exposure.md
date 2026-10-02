# 0040. Actuator exposure

Status: Accepted (2026-10-02)

## Context

Prometheus scrapes `/actuator/prometheus` without a bearer token, and every other route outside the consumption API
needs one. Metrics show operational detail: failure codes, action names, request rates. Actuator's `env` and
`configprops` endpoints would show configuration, including `folio.crypto.*` (ADR 0019).

## Decision

- Expose `health` and `prometheus` over HTTP, nothing else. `health` stays permitted without a token, status only, with
  the default database check and no per-ConfigSet health.
- Permit `/actuator/prometheus` without a token in the security rules.
- Fail closed: `management.server.port` defaults to 8081, so the actuator has a port of its own and the application
  port serves no actuator endpoint, health included. Deployments keep the management port off the public network and
  may set another port. Liveness and readiness probes must target the management port.
- Tests set the management port to the application port so that MockMvc reaches the actuator; one test runs with a
  port of its own, as deployed, and checks that the production configuration sets 8081.

## Options considered

- A bearer token for scrapers: needs an OAuth client for each Prometheus and token refresh in the scraper.
- HTTP basic authentication for the endpoint: a second authentication mechanism, which v1 avoids, and a shared secret.
- The actuator on the application port by default: the metrics would be public wherever a deployment forgot to move
  them.

## Consequences

- Keeping the management port private is a deployment duty, stated in `application.yaml`. A deployment that sets
  `management.server.port` to the application port puts the metrics there, in public.
- Probes that used `/actuator/health` on the application port must move to the management port.
- Metric tags hold no IDs, paths or subjects (ADR 0039), so a deployment that exposes them by mistake leaks rates and
  failure codes, not names.
