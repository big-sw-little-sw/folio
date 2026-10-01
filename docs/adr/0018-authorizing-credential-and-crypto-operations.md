# 0018. Authorizing credential and crypto operations

Status: Accepted (2026-10-01)

## Context

Credentials and the master-key ring are not in the namespace tree. Rules attach only to namespaces and
ConfigSets, and the root holds none (ADR 0012). Each slice adds the actions it uses.

## Decision

- Slice 4 adds `CREDENTIAL_VIEW` (get, list), `CREDENTIAL_MANAGE` (create, regenerate, activate, replace,
  disable) and `CRYPTO_MANAGE` (master-key usage and re-encryption).
- All three are checked against the root, the empty policy path. No rule can attach there, so in v1 only
  bootstrap admins (ADR 0008) pass.
- Lookups come before authorization, as elsewhere (ADR 0010): a missing credential gives 404 to anyone, an
  existing one gives 403 to an authenticated caller without the action.
- `CredentialKeyPairs`, which decrypts a credential's active key for slice 5, does not authorize: sync runs
  without a caller. No HTTP endpoint calls it.

## Consequences

- When root rules arrive (ADR 0012), delegating credential work needs no change at the call sites.
- Granting these actions on a namespace or ConfigSet is accepted and has no effect, like other actions on the
  wrong resource type (ADR 0014).
- Any code in the credential module can obtain a private key without a policy check. Keeping
  `CredentialKeyPairs` out of controllers is a review rule, not an enforced one.
