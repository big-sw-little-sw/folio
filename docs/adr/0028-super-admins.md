# 0028. Super admins

Status: Accepted (2026-10-01). Amends ADR 0002 and ADR 0008.

## Context

ADR 0002 and ADR 0008 call the configured administrators "bootstrap admins". The name suggests a role for first
setup only, but they keep every right for as long as they are configured and are the recovery path when rules lock
others out.

## Decision

- Bootstrap admins are renamed super admins. The configuration key `folio.bootstrap.admins` becomes
  `folio.super-admins`, with the same entry format (`user:<subject>`, `group:<group>` or `application:<id>`).
- The old key is not read any more and has no alias: Folio is not released yet.
- Explanations report reason `SUPER_ADMIN` instead of `BOOTSTRAP_ADMIN`.
- The semantics of ADR 0008 are unchanged: super admins are allowed every action on every resource, regardless of
  rules, and are not stored as rules.

## Consequences

- A deployment that still sets `folio.bootstrap.admins` has no super admins until it renames the key.
- Accepted ADRs keep the old name in their text; their status lines point here.
