# 0001. Reject deleting non-empty namespaces

Status: Accepted (2026-09-30)

## Context

v1 lets administrators delete namespaces. The scope and design documents do not say what happens
when a namespace still has child namespaces or ConfigSets. Consumers read ConfigSets by stable ID,
so a ConfigSet that disappears breaks them.

## Decision

Deleting a namespace is rejected while it has child namespaces or ConfigSets.
There is no cascading delete.

## Consequences

- A cascade cannot silently delete ConfigSets that consumers depend on.
- To remove a subtree, an administrator first deletes or moves its contents. Each step is a
  deliberate action with its own audit event.
- The API needs a specific error for a non-empty namespace.
