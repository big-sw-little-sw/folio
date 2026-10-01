# 0012. Authorizing namespace operations

Status: Accepted (2026-09-30)

## Context

Design 9.1 lists namespace actions but does not say which resource each operation is checked against,
what a move needs, or who acts above the root namespaces. Rules attach to namespaces, so the root has
no place for a rule.

## Decision

- Create needs `NAMESPACE_CREATE` on the parent. Rename, delete and view need the matching action on the
  namespace itself.
- Move needs `NAMESPACE_MOVE` on the namespace and `NAMESPACE_CREATE` on the new parent, so a caller can
  move a namespace only to where they could have created it.
- Listing children returns those the caller may view, by `NAMESPACE_VIEW` on each child.
- The root holds no rules in v1. Creating root namespaces, moving to the root and the credential and
  crypto actions are therefore for bootstrap admins only.
- Explaining a decision needs `POLICY_VIEW`, the same action as listing rules, because it shows no more
  than the rules do.

## Consequences

- Delegating root-level work to non-admins needs root rules later, with a new table column or a root
  resource. That change is additive.
- A moved subtree inherits from its new ancestors at once; the caller needed rights at both places.
