# 0023. Using credentials for sources

Status: Accepted (2026-10-01)

## Context

A ConfigSet's source names a credential (ADR 0022). Attaching a credential lets the ConfigSet's content come from any
repository that credential can read, so `CONFIG_SET_CREATE` on a namespace is not enough. Credentials sit at the root,
where only bootstrap admins hold actions (ADR 0018). The onboarding check (ADR 0027) also connects with the credential.

## Decision

- A new action `CREDENTIAL_USE`, checked at the root like the other credential actions, so only bootstrap admins hold
  it in v1.
- Creating a ConfigSet needs `CONFIG_SET_CREATE` on the namespace and `CREDENTIAL_USE`. The credential must exist
  (404 otherwise, like any missing resource named in a request body) and be enabled (409). The check is
  `CredentialService.requireUsable`, so the credential module keeps its own authorization.
- `POST /api/v1/admin/configsets/{id}:check` needs `CONFIG_SET_VIEW` on the ConfigSet and `CREDENTIAL_USE`. Running the
  check uses the credential just as attaching it does; viewing alone must not let a caller probe repositories with it.
- The `cred_` and `key_` conversions move from `credential.web` to the credential module's public API, because the
  ConfigSet API accepts and returns credential and key IDs. This amends ADR 0021, as ADR 0015 did for `ns_`.

## Consequences

- In v1 only bootstrap admins can create ConfigSets or run checks; namespace administrators can rename, move, delete
  and grant on ConfigSets that exist.
- The planned next step is to give credentials an owning namespace and check `CREDENTIAL_USE` there, so namespace
  administrators can manage and use their own credentials. Call sites then pass the credential's namespace path
  instead of the root; nothing else changes.
