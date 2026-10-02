# 0029. Credential names

Status: Accepted (2026-10-01). Amends ADR 0017.

## Context

ADR 0017 gave credentials no name. In a list of credentials an administrator then tells them apart only by ID and Git
instance, and cannot see which deploy key or bot account each one stands for.

## Decision

- A credential has a required name, set on create and returned by get and list (for example `payments-bot`).
- Names are unique per Git instance: a unique constraint on `(git_instance, name)`. A duplicate gives 409.
- A name follows the slug rules: 1 to 100 lowercase letters, digits and single inner hyphens, starting and ending
  with a letter or digit. An invalid name gives 400. `CredentialName` repeats the few lines of `Slug` instead of
  using it, so the credential module does not depend on the namespace module, and errors name the credential field.
- Migration V6 adds the column as `not null` without a default. Folio is not released, so a database that already
  holds credentials is recreated rather than migrated.

## Consequences

- There is no rename. It can be added later as its own operation; ConfigSets refer to credentials by ID, so a rename
  would change nothing that refers to one.
