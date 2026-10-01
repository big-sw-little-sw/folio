# 0002. JWT claim mapping

Status: Accepted (2026-10-01)

## Context

Identity providers put user, group and application IDs in different JWT claims. Microsoft Entra
ID's `sub` is pairwise per application. The design doc describes a multi-provider enum.

## Decision

- v1 trusts one issuer, set with Spring's `spring.security.oauth2.resourceserver.jwt.issuer-uri`.
- Claim names are configuration under `folio.security.claims`: `subject` (default `sub`),
  `groups` (default `groups`) and `application-id` (default `azp`).
- Entra ID deployments set `subject: oid`. There is no provider-specific code.
- Bootstrap admins (`folio.bootstrap.admins`) are deployment configuration, not database rows.
  Each entry is `user:<subject>`, `group:<group>` or `application:<id>`, matched against the
  mapped claims.
- The `IdentityProvider` enum is not built in v1.

## Consequences

- Supporting a provider means setting claim names, not writing code.
- Trusting more than one issuer needs a later change.
- Changing bootstrap admins requires a redeploy.
