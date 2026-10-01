# 0011. Security and policy modules

Status: Accepted (2026-09-30)

## Context

Namespace operations need authorization, and authorization needs the namespace tree for inheritance.
Spring Modulith forbids cycles, so `policy` and `namespace` cannot depend on each other. The HTTP error
mapping must see the exceptions of every module.

## Decision

Dependencies point one way: `web` → `namespace` → `policy` → `security`.

- `security`: the filter chain, the JWT claim mapping and `CurrentPrincipal`, which reads the
  `ApplicationPrincipal` of the current request from Spring's security context.
- `policy`: actions, subjects, rules, nearest-rule resolution, explanations and bootstrap admins.
  Its API takes the target's namespace path (root first) from the caller. `policy_rule` references
  `namespace` with `on delete cascade`, so deleting a namespace removes its rules without code in either
  module.
- `namespace`: authorizes each operation through `PolicyService`, and serves the rule and explain
  endpoints for namespaces because it is the module that knows the path.
- `web`: the single `@RestControllerAdvice` (ADR 0006).

## Consequences

- Policy stays independent of the resource tree; slice 3 passes ConfigSet paths the same way.
- Services read the principal from the thread, so work moved to another thread must carry the
  security context.
- Each new module with expected failures adds a handler to the advice in `web`.
