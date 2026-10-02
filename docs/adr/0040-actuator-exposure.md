# 0040. Actuator exposure

Status: Accepted (2026-10-02)

## Context

Prometheus scrapes `/actuator/prometheus` without a bearer token, and every other route outside the consumption API
needs one. Metrics show operational detail: failure codes, action names, request rates. Actuator's `env` and
`configprops` endpoints would show configuration, including `folio.crypto.*` (ADR 0019).

## Decision

- Expose `health` and `prometheus` over HTTP, nothing else. `health` stays as it was: permitted without a token, status
  only, the default database check, no per-ConfigSet health.
- Permit `/actuator/prometheus` without a token in the security rules.
- Deployments set `management.server.port` to serve the actuator on a port of its own and keep that port off the public
  network. The application port then serves no actuator endpoint. Without it, the metrics are public on the
  application port.

## Options considered

- A bearer token for scrapers: needs an OAuth client for each Prometheus and token refresh in the scraper.
- HTTP basic authentication for the endpoint: a second authentication mechanism, which v1 avoids, and a shared secret.
- Only the separate port, enforced by Folio: would refuse to start without it, which makes local runs awkward.

## Consequences

- Protecting the endpoint is a deployment duty, stated in `application.yaml`.
- Metric tags hold no IDs, paths or subjects (ADR 0039), so a deployment that exposes them by mistake leaks rates and
  failure codes, not names.
