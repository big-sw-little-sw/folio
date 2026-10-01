# 0008. Bootstrap admins ahead of rules

Status: Accepted (2026-09-30)

## Context

ADR 0002 says bootstrap admins hold every action at the root. Under nearest-rule inheritance (design 9.3)
a grant at the root is the farthest rule, so any nearer rule for the same action would override it. A
`POLICY_UPDATE` rule on one namespace would then lock bootstrap admins out of fixing that namespace's
policy, which defeats their purpose as the recovery path.

## Decision

- Authorization checks bootstrap admins first. A caller who matches `folio.bootstrap.admins` is allowed
  every action on every resource, whatever the rules say.
- Explanations report this as reason `BOOTSTRAP_ADMIN` with the matched admin entry.
- Bootstrap admins are not stored as rules and cannot be changed through the API.

## Consequences

- Rules cannot lock bootstrap admins out.
- Bootstrap admins are effectively superusers. Deployments should list few subjects, ideally a group.
- The root above all namespaces has no rules (ADR 0012), so only bootstrap admins act there.
