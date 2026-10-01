# 0009. One rule per action, with at least one subject

Status: Accepted (2026-09-30)

## Context

Design 19.4 asks for one active rule per resource and action. Under nearest-rule inheritance a rule
without subjects would stop inheritance and deny everyone below it. That is an explicit deny in disguise,
and v1 has grants only.

## Decision

- A rule is a namespace, an action and a non-empty set of subjects.
- `PUT …/rules/{action}` adds the rule or replaces the namespace's rule for that action.
  `DELETE …/rules/{action}` removes it, so the action inherits again; deleting a missing rule succeeds.
- A rule with no subjects is rejected with 400.
- Subjects use the text form of bootstrap admins (ADR 0002) plus `public` and `authenticated`.

## Consequences

- Rule identity is the namespace and action, so the API needs no rule IDs.
- Replacing a rule replaces all its subjects; adding one subject means sending the full list.
- Restricting access below a broad grant means adding a nearer rule with a narrower set of subjects.
