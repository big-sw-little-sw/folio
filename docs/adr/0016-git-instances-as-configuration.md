# 0016. Git service instances as configuration

Status: Accepted (2026-10-01)

## Context

Credentials are scoped to a Git service instance (v1-scope, design 13.3). Slice 5 needs each instance's host,
port and trusted host keys. Design 19.7 stores the instance as free text on the credential. Nothing says where
the list of instances lives.

## Decision

- Git service instances are deployment configuration, not database rows: `folio.git.instances` maps an
  instance name to its `host` and `port` (default 22).
- Names are lowercase letters, digits and single hyphens, at most 100 characters, so they bind as map keys
  without brackets and fit `credential.git_instance`.
- A credential stores the instance name. Creating a credential for a name that is not configured gives 400.
- Slice 5 adds each instance's trusted host keys to the same entry, in place of the separate
  `folio.git.known-hosts` that `plan.md` named.
- The properties live in `credential.internal` until slice 5 needs them elsewhere.

## Consequences

- Adding or changing an instance needs a redeploy, like bootstrap admins (ADR 0002). Host keys are
  security-relevant and belong with deployment review anyway.
- Removing an instance from configuration leaves credentials that name it. Nothing checks this at startup in
  this slice; slice 5 decides what sync does with such a credential.
