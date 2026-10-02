# 0031. Moves that carry their own rules

Status: Accepted (2026-10-01). Amends ADR 0012 and ADR 0014.

## Context

A moved ConfigSet or namespace subtree keeps its own rules, which then sit nearer than the target's (ADR 0014). A
caller with move and create rights could therefore move a resource whose own `POLICY_*` rules lock out the target's
policy administrators. ADR 0014 left open whether such a move needs more.

## Decision

- Moving a ConfigSet that has rules of its own additionally needs `POLICY_UPDATE` on the target namespace.
- Moving a namespace additionally needs `POLICY_UPDATE` on the new parent if any rule exists on the namespace itself,
  on a descendant, or on a ConfigSet in any of them. Moving to the root already needs super admin rights (ADR 0012).
- Any rule counts, for any action: a rule for one action can be as restrictive as a `POLICY_*` rule.
- The check runs under the tree lock, which rule writes also take, so no rule can appear between check and move.
- `PolicyService.requireAllowedToMoveRules(moved, targetPath)` holds the rule. Callers pass the moved resources as
  `ResourceRef`s, so policy still depends on neither `namespace` nor `configset` (ADR 0011).
- The namespace module cannot see ConfigSets (ADR 0015). Its move publishes a `NamespaceMoving` event with the subtree
  and the target path, synchronously and inside the move's transaction, before anything changes. `ConfigSetService`
  listens, finds the ConfigSets in the subtree and makes the same check; a denial propagates and rolls the move back.
  An interface in `namespace` implemented by `configset` would do the same, but with a single implementation.

## Consequences

- Delegated administrators can still reorganize resources without their own rules using move and create alone.
- Moving a resource with its own rules is a policy change at the target, and needs the right to make one there.
- Synchronous events are a second way for `namespace` to reach modules that depend on it. They are for checks and
  reactions that must run in the move's transaction; anything else keeps to direct calls.
