# 0017. Credential key lifecycle

Status: Accepted (2026-10-01). Amended by ADR 0029: a credential has a name. Amended by ADR 0030: a pending key
can be discarded without activating it.

## Context

v1-scope defines key versions (`PENDING`, `ACTIVE`, `RETIRED`), two-step regeneration, emergency replacement
and disabling. It leaves open what activation names, what replacement does with a pending key, whether
disabling can be undone, how concurrent changes behave and whether a credential has a name.

## Decision

- A credential has an ID, a Git instance name (ADR 0016) and a status, `ENABLED` or `DISABLED`. It has no name
  or description: v1-scope does not ask for one.
- Creating a credential generates its first key and activates it at once.
- Regenerating adds a `PENDING` key and returns its public key. A second regeneration while a key is pending
  gives 409.
- Activation names the pending key (`{"keyId": "key_…"}`). If that key is not the credential's pending key,
  it gives 409. An administrator registers one specific public key with the Git service; naming it stops them
  from activating a different key that someone generated in the meantime.
- Activation retires the previous active key: its status becomes `RETIRED` and its five encryption columns are
  set to null. The public key and fingerprint stay.
- Emergency replacement retires the active key and any pending key, then generates and activates a new key.
  A pending key generated before an emergency is not trusted afterwards.
- Disabling is idempotent and, in v1, final: there is no enable. A disabled credential refuses regeneration,
  activation and replacement (409), and gives no key pair to sync. Its encrypted keys stay, so re-enabling can
  be added later.
- Every key change locks the credential row (`select … for update`) before it reads the keys, so changes to one
  credential run one at a time. Partial unique indexes allow one `ACTIVE` and one `PENDING` key per credential
  as a backstop.
- The API returns the public key as an OpenSSH line whose comment is the key's API ID, so an administrator can
  match a deploy key in the Git service to the Folio key. The stored public key has no comment.
- The optional `ls-remote` check on activation moves to slice 5, which builds Git access.

## Consequences

- Discarding a pending key without activating it needs an emergency replacement, which also replaces the
  active key. A "discard pending" operation is additive if needed.
- There are no timestamps on credentials or keys; key IDs are uuidv7 and so record creation order. Slice 8's
  audit events record when each change happened.
