# 0012. Authorizing namespace operations

Status: Accepted (2026-09-30). Amended by ADR 0031: moving a subtree with rules of its own also needs `POLICY_UPDATE`
on the new parent.

## Context

Design 9.1 lists namespace actions but does not say which resource each operation is checked against,
what a move needs, or who acts above the root namespaces. Rules attach to namespaces, so the root has
no place for a rule.

## Decision

- Actions exist only for features that are built. Slice 2 defines the `NAMESPACE_*` and `POLICY_*`
  actions; the slices that build ConfigSets, content reads, sources, sync, credentials and crypto add
  their own.
- Create needs `NAMESPACE_CREATE` on the parent. Rename, delete and view need the matching action on the
  namespace itself.
- Move needs `NAMESPACE_MOVE` on the namespace and `NAMESPACE_CREATE` on the new parent, so a caller can
  move a namespace only to where they could have created it.
- Listing children returns those the caller may view, by `NAMESPACE_VIEW` on each child.
- The root holds no rules in v1. Creating root namespaces and moving to the root are therefore for
  bootstrap admins only.
- Explaining a decision needs `POLICY_VIEW`, the same action as listing rules. `POLICY_VIEW` on a
  namespace therefore shows its effective policy, not only its own rules: for any action and principal,
  explain reveals the inherited rule's source namespace ID, the matched subject and whether a bootstrap
  admin entry matched.

## Consequences

- Delegating root-level work to non-admins needs root rules later, with a new table column or a root
  resource. That change is additive.
- A moved subtree inherits from its new ancestors at once; the caller needed rights at both places.
- Granting `POLICY_VIEW` low in the tree exposes the IDs of ancestor namespaces whose rules apply there,
  and which bootstrap admin entries exist when the explained principal matches one.
