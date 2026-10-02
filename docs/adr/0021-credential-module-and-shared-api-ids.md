# 0021. Credential module and shared API IDs

Status: Accepted (2026-10-01). Amends ADR 0007 and ADR 0015. Amended by ADR 0023: the `cred_` and `key_`
conversions move to the credential module's public API.

## Context

Credentials and crypto need a module. ADR 0015 noted that the `ns_` and `cfg_` conversions repeat a few lines,
and that a shared helper becomes worth a module of its own once `cred_` and `key_` arrive.

## Decision

- A new `credential` module holds credentials, keys, crypto and master-key rotation. Encryption, the key ring,
  the OpenSSH encoding and the repositories are in `credential.internal`; no other module needs them.
- Dependencies: `web` → `credential` → `policy` → `security`. Nothing else depends on `credential` yet; slice 5's
  Git module will use `CredentialKeyPairs`.
- A new `apiid` module holds two functions, `formatApiId(prefix, uuid)` and `parseApiId(prefix, value)`, which
  returns null for anything that is not the prefix plus 32 lowercase hex characters. Each owning module wraps
  them with its prefix, ID type and exception, so the error a client sees still names the resource type.
  Conversion stays at the HTTP edge (ADR 0007).

## Consequences

- The format of all four prefixes is defined once.
- `apiid` depends on nothing, so any module may use it without risking a cycle.
