# 0038. Audit records

Status: Accepted (2026-10-02). Amends ADR 0031, ADR 0032 and ADR 0034.

## Context

v1-scope asks for audit events persisted to a table for namespace, ConfigSet, policy, credential, key and crypto
operations, manual syncs and sync outcomes. Records carry IDs, the path at the time and key fingerprints, never key
material (design 24.2). It names no read API. The audited operations live in five modules, and an audit module that each
of them called would have to sit below all of them and learn nothing of their types, so each module would format its
own records.

## Decision

- **Module.** A new module `audit` owns the `audit_event` table and `AuditRecorder`. It depends on `namespace`,
  `configset`, `credential` and `sync` for their event types, on `policy` to tell super admins and on `security` for the
  caller. Nothing depends on it.
- **Events.** Each audited module publishes small events after the write, synchronously and inside the operation's
  transaction, as ADR 0031 allows: `NamespaceEvent` and `ConfigSetEvent` (sealed: created, renamed, moved, deleted, rule
  put, rule deleted), `CredentialChanged` (with a `CredentialChange`), `MasterKeysReencrypted` and `SyncEvent` (sealed:
  requested, revision changed, failed, recovered). `ConfigSetCreated` and `ConfigSetDeleted` gain the path, and
  `ConfigSetCreated` the source; sync's existing listeners are unchanged. The listeners are `@EventListener` with
  `Propagation.MANDATORY`.
- **Same transaction.** A record commits with its operation, and a failed insert rolls the operation back. Denied and
  conflicting operations throw before they publish, so they leave no record. Two operations are not one transaction:
  - Re-encryption commits per row (ADR 0020). The pass publishes one event at the end, in a transaction of its own, with
    the number of keys it moved off each master-key version and the remaining usage. If that insert fails, the request
    fails and the keys stay re-encrypted; a re-run records again.
  - Sync records an attempt in one statement outside a transaction (ADR 0032). That statement and the outcome's event now
    run in one transaction, and only while the lease is held. The statement returns the previous synced revision and
    failure code with PostgreSQL 18's `RETURNING old.…`. `ConfigSetSources.path` gives sync the ConfigSet's path without
    authorization.
- **What is audited**, with `resource_type` and `action`:
  - `NAMESPACE`: `NAMESPACE_CREATED`, `_RENAMED`, `_MOVED`, `_DELETED`; `CONFIG_SET`: `CONFIG_SET_CREATED`, `_RENAMED`,
    `_MOVED`, `_DELETED`.
  - `POLICY_RULE_PUT` and `POLICY_RULE_DELETED`, on the namespace or ConfigSet the rule attaches to.
  - `CREDENTIAL`: `CREDENTIAL_CREATED`, `_KEY_REGENERATED`, `_KEY_ACTIVATED`, `_KEY_DISCARDED`, `_KEY_REPLACED`,
    `_DISABLED`. Disabling a disabled credential changes nothing and records nothing.
  - `MASTER_KEY_RING`: `MASTER_KEYS_REENCRYPTED`, once per pass, with counts per version rather than a record per key.
  - `CONFIG_SET`: `SYNC_REQUESTED`, and the outcomes below.
- **Sync outcomes, on change only.** `SYNC_REVISION_CHANGED` when a sync without a preceding failure records a new synced
  revision, including the first; `SYNC_FAILED` when the failure code differs from the previous attempt's, including the
  first failure; `SYNC_RECOVERED` for the first success after failures, with or without a new revision. A success that
  changes nothing and a failure that repeats the previous code record nothing: every attempt would otherwise write a row
  each interval. The sync state and the attempts metric (ADR 0039) still show them.
- **Not audited:** reads of any kind (consumption, gets, listings, rule listings), explanations, onboarding checks
  (`:check`), rules removed by the cascade of a namespace or ConfigSet delete, and attempts that change nothing.
- **Record.** `occurred_at` is `now()`, the database clock at the start of the operation's transaction. The actor is
  `AUTHENTICATED` with the subject and the application ID, `ANONYMOUS`, or `SYSTEM` for sync outcomes; groups are not
  stored. `actor_super_admin` says whether the caller matches `folio.super-admins`; super admins are decided before any
  rule (ADR 0008), so it says whether the decision came from that status. Resources are a type and a UUID, not an API ID;
  IDs inside details are UUIDs too. `resource_path` is the namespace or ConfigSet path after the operation, or before it
  for a delete; renames and moves add `previousPath`. Credentials and the key ring have no path.
- **Details**, a JSON object: rule writes `ruleAction` and, for puts, the sorted `subjects`; ConfigSet creation the
  source's `credentialId`, `repositoryPath`, `branch` and `rootPath`; credential changes `gitInstance`, `name` and the
  `keys` whose status the change set, each as `keyId`, `fingerprint` and new `status`; re-encryption `activeVersion`,
  `reencryptedByVersion` and `keysByVersion`; outcomes the `revision`, `previousRevision`, `failureCode` and
  `previousFailureCode` that apply. Never public or private keys, ciphertext, master secrets, tokens, transport output
  or file contents.
- **Storage.** No foreign keys: records outlive the resources they name. One index, `(resource_id, occurred_at)`, serves
  the obvious query, the history of one resource. No read API: operators query the table.

## Consequences

- An audited write costs one insert and, for most operations, a path lookup.
- A namespace move records the moved namespace only; the paths of everything beneath it follow from that record.
- Records name subjects, which may be personal data, and the table grows without bound.
- A new audited operation needs an event in its module and a mapping in `audit`.

## Open questions

- An audit read API: who may read records, filters and pagination.
- Retention and pruning.
- Auditing the onboarding check and sync's use of credentials (design 23.4).
