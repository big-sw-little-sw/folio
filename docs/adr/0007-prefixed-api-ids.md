# 0007. Prefixed API IDs

Status: Accepted (2026-09-30)

## Context

`v1-scope.md` says API IDs are prefixed strings (`ns_…`, `cfg_…`) and the database uses UUIDs. It does not
say how the UUID is written after the prefix.

## Decision

- An API ID is the type prefix plus the UUID as 32 lowercase hex characters without hyphens, for example
  `ns_0192f3a45b6c7d8e9fa0b1c2d3e4f5a6`.
- Conversion happens only in the HTTP layer of the module that owns the type. Services, domain types and
  SQL use UUIDs and ID value classes.
- Anything else, including uppercase hex, hyphens or another prefix, is rejected with 400.

## Consequences

- One canonical form per ID, so IDs compare as strings and are safe in URLs.
- The prefix tells a reader the resource type, for example in a decision's `policySource`.
- Converting is a reversible hex encoding; no lookup table is needed.
