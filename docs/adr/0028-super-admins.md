# 0028. Super admins

Status: Accepted (2026-10-01). Amends ADR 0002 and ADR 0008.

## Context

ADR 0002 and ADR 0008 call the configured administrators "bootstrap admins". The name suggests a role for first
setup only, but they keep every right for as long as they are configured and are the recovery path when rules lock
others out.

## Decision

- Bootstrap admins are renamed super admins. The configuration key `folio.bootstrap.admins` becomes
  `folio.super-admins`, with the same entry format (`user:<subject>`, `group:<group>` or `application:<id>`).
- The old key has no alias: Folio is not released yet. Startup fails if it is still set, naming the new key, so a
  deployment cannot silently lose its super admins.
- Explanations report reason `SUPER_ADMIN` instead of `BOOTSTRAP_ADMIN`.
- The semantics of ADR 0008 are unchanged: super admins are allowed every action on every resource, regardless of
  rules, and are not stored as rules.

## Consequences

- A deployment that still sets `folio.bootstrap.admins` does not start until it renames the key.
- ADRs 0002 and 0008 point here; other accepted ADRs keep the old name.
