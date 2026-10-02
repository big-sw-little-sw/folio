# 0015. ConfigSet module boundaries

Status: Accepted (2026-09-30). The shared ID helper it anticipated is ADR 0021. Amended by ADR 0032: the `cfg_`
conversion moves to the ConfigSet module's public API.

## Context

ConfigSets live in namespaces and are authorized by policy, so a new `configset` module depends on
`namespace` and `policy`. Spring Modulith forbids cycles, so `namespace` cannot ask about ConfigSets. Yet
deleting a namespace that holds ConfigSets must be rejected (ADR 0001), ConfigSet writes must not interleave
with namespace moves and deletes, and the ConfigSet API must accept and return `ns_` IDs and serve the same
rule and explain bodies as the namespace API.

## Decision

Dependencies point one way: `web` → `configset` → `namespace` → `policy` → `security`. This extends ADR 0011.

- The foreign key `config_set_namespace_fk` (no cascade) stops a namespace delete while ConfigSets reference
  it. `NamespaceRepository.delete` translates a violation of that constraint, recognized by SQLState 23503
  (foreign-key violation) and the constraint name, into `NamespaceNotEmptyException`. No SQL in `namespace`
  reads `config_set`.
- `NamespaceTree` is the namespace module's API for modules that place resources in namespaces. It takes
  the tree lock, returns a namespace's policy path and finds a namespace by slugs. It does not authorize.
  Namespace services take the lock through it too, so there is one way to take it.
- Every ConfigSet write, including rule writes, takes the tree lock first. A concurrent namespace delete then
  runs entirely before it (the write fails as namespace not found) or after it (the delete fails as not
  empty), never with a foreign-key error. The lock also keeps the authorized path current.
- The `ns_` ID conversion moves from `namespace.web` to the namespace module's public API so other modules'
  HTTP layers can use it. Only HTTP layers call it. This amends ADR 0007, which kept conversion in the owning
  module's HTTP layer. `cfg_` conversion stays in `configset.web`; nothing else needs it yet.
- The rule and explain request and response bodies move to the policy module, which owns rules, subjects
  and decisions. The namespace and ConfigSet modules still serve the endpoints, because they know the paths.

## Consequences

- The namespace module names one ConfigSet constraint in a string. Renaming that constraint needs a change
  in both places; the API test for deleting a non-empty namespace catches a mismatch.
- The constraint name is read from the driver's error message, because the PostgreSQL driver is a runtime
  dependency only.
- ConfigSet writes and namespace writes run one at a time. Both are rare administrative actions.
- The `ns_` and `cfg_` conversions repeat a few lines. When `cred_` and `key_` arrive, a shared helper
  becomes worth a module of its own.
