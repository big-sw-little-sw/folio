# 0014. Authorizing ConfigSet operations

Status: Accepted (2026-09-30)

## Context

ADR 0012 sets how namespace operations are authorized. ConfigSets need the same answers, and design 9.3 and
19.4 let rules attach to ConfigSets as well as namespaces. The policy module must stay independent of the
modules that own resources (ADR 0011).

## Decision

- Slice 3 adds `CONFIG_SET_VIEW`, `CONFIG_SET_CREATE`, `CONFIG_SET_RENAME`, `CONFIG_SET_MOVE` and
  `CONFIG_SET_DELETE`. Design 9.1 has no rename action; it is added because rename is its own operation, as
  for namespaces. Rules on ConfigSets use the existing `POLICY_VIEW` and `POLICY_UPDATE`.
- Create needs `CONFIG_SET_CREATE` on the namespace that will hold the ConfigSet. View, rename and delete
  need the matching action on the ConfigSet.
- Move needs `CONFIG_SET_MOVE` on the ConfigSet and `CONFIG_SET_CREATE` on the target namespace, so a caller
  can move a ConfigSet only to where they could have created it.
- Listing a namespace's ConfigSets returns those the caller may view; it needs no right on the namespace.
- A rule belongs to exactly one namespace or one ConfigSet (`policy_rule.namespace_id` or
  `policy_rule.config_set_id`, with a check constraint). Deleting a ConfigSet deletes its rules by cascade.
- Callers pass policy a path of `ResourceRef`s: the namespaces from the root down, then the ConfigSet if the
  target is one. A ConfigSet's own rule is therefore nearest, then its namespace's, and so on up.
- Explain reports the policy source with its own prefix: `cfg_…` for a ConfigSet's rule, `ns_…` for an
  inherited namespace rule.

## Consequences

- Any action can be granted on either resource type; a namespace action on a ConfigSet has no effect.
- Moving a ConfigSet changes its inherited rules at once; its own rules move with it.
- Later ConfigSet actions (content reads, sync, sources) need no new rule storage.
