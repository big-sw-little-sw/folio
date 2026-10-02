# Folio v1 plan

Slices of [`v1-scope.md`](v1-scope.md), built in order. One slice per branch and PR; tick it off in the
same PR. "Done" means every bullet holds and `./gradlew check` is green.

- [x] **0. Foundation**
  - Build: version catalog, JGit, foojay toolchain resolver, Testcontainers pinned to `postgres:18`.
  - `test` runs without Docker; `integrationTest` runs tests tagged `integration`; `check` runs both.
  - ktlint and detekt fail the build; `ModularityTests` verifies Spring Modulith boundaries.
  - Claude Code Stop hook runs `check`; CI runs `check` on pushes to main and on pull requests.
  - `CLAUDE.md` and this plan exist.

- [x] **1. Namespaces**
  - Domain model: `Namespace` with stable ID, slug, parent; sibling-slug uniqueness, including at the root.
  - Flyway migration for namespaces and the closure table; `JdbcClient` repositories.
  - Create, rename, move and delete rules, with closure rows kept consistent on move.
  - Moving a namespace into its own subtree is rejected.
  - Deleting a namespace that has child namespaces is rejected (ADR 0001).
  - Unit tests for slug rules; integration tests for the tree rules and closure-table queries.

- [x] **2. Security and policy**
  - JWT validation through the OAuth2 resource server; claims mapped to `ApplicationPrincipal`
    (user, groups, application ID) (ADR 0002).
  - Action-based grant rules on namespaces (ConfigSets follow in slice 3); subjects: public, any
    authenticated, user, group, application.
  - Nearest-rule resolution with default deny, enforced in application services.
  - Decision explanation endpoint for administrators.
  - Bootstrap admins from `folio.bootstrap.admins` hold every action at the root and below, ahead of
    rules (ADR 0002, ADR 0008).
  - Namespace admin API (create, rename, move, delete) behind policy checks.
  - Errors are RFC 9457 problem details; namespace API IDs are `ns_…` (ADR 0007).
  - Only `NAMESPACE_*` and `POLICY_*` actions exist; slices 3 to 7 add the actions of the features they
    build (ADR 0012).

- [x] **3. ConfigSets**
  - Create, rename, move and delete ConfigSets. They have no source yet; slice 5 adds it.
  - ConfigSet IDs stay stable across renames and moves of the ConfigSet and its ancestors.
  - Deleting a namespace that contains ConfigSets is rejected (ADR 0001, ADR 0015).
  - ConfigSet writes take the namespace tree lock, so a concurrent namespace delete fails with
    NamespaceNotEmptyException rather than a foreign-key error (ADR 0015).
  - `GET /api/v1/configsets:resolve?path=…` resolves a path to a ConfigSet; the last segment is the
    ConfigSet (ADR 0013).
  - Policy rules attach to ConfigSets as well as namespaces; a ConfigSet's own rule is nearest, then its
    namespace path (`policy_rule.config_set_id`, design 19.4, ADR 0014).
  - Admin API behind policy checks; API IDs are prefixed strings (`ns_…`, `cfg_…`).

- [x] **4. Credentials and crypto**
  - Credentials scoped to a Git service instance from `folio.git.instances` and referenced by stable ID;
    disable a credential (ADR 0016, ADR 0017).
  - Ed25519 key generation; only the OpenSSH public key is ever returned.
  - Private keys encrypted with AES-256-GCM under HKDF-SHA256 keys from the configured master-key ring;
    fresh salt and nonce per encryption; associated data `credentialId | keyId | masterKeyVersion` (ADR 0019).
  - Two-step key regeneration (`PENDING` then activate) and emergency replacement; partial unique indexes
    allow one `ACTIVE` and one `PENDING` key; retired keys lose their ciphertext (ADR 0017).
  - Master-key rotation: `POST /api/v1/admin/crypto:reencrypt` re-encrypts per row with an optimistic check,
    is safe to re-run and reports rows per master-key version (ADR 0020).
  - Startup fails clearly if a row references an unconfigured master-key version.
  - Credential and crypto operations are authorized at the root, so only bootstrap admins in v1 (ADR 0018).
  - API IDs are `cred_…` and `key_…`; all prefixes share one helper (ADR 0021).

- [x] **5. Git source**
  - Each ConfigSet maps to exactly one Git source, set at creation and required: credential, repository path on the
    credential's Git instance, branch and root path (ADR 0022). Changing a source is not built yet.
  - Attaching a credential needs `CREDENTIAL_USE` at the root, so bootstrap admins only in v1 (ADR 0023).
  - New `source` module: JGit SSH transport with mandatory host-key verification against each instance's trusted host
    keys, added to its `folio.git.instances` entry with the SSH user (ADR 0016, ADR 0025). Nothing is read from
    `~/.ssh`, an SSH agent or the system and user Git config.
  - Apache sshd gets Ed25519 from Bouncy Castle; Folio's own crypto stays JDK-only (ADR 0026).
  - Disposable local bare-repository cache per ConfigSet under `folio.git.cache-directory` (ADR 0024).
  - File listing and reads at latest or an exact commit, beneath the ConfigSet root path.
  - Path normalisation rejects absolute paths, traversal and empty segments, backslashes and NUL.
  - Onboarding check, `POST /api/v1/admin/configsets/{id}:check`: `ls-remote` and fetch show that the credential
    reaches the repository and the branch and root path exist; failures are stable codes with safe summaries
    (ADR 0027).
  - The same check with a pending `keyId` verifies a pending key before activation (moved from slice 4, ADR 0017).
  - Sync gets the credential's active key pair from `CredentialKeyPairs.active`.
  - Integration tests run against an SSH Git server in Testcontainers (ADR 0004).

- [ ] **6. Sync**
  - Polling scheduler requests sync for due ConfigSets; fetches run on a bounded executor.
  - Per-source leases with `SELECT … FOR UPDATE SKIP LOCKED`; a source is never fetched concurrently.
  - Fetch-based change detection against the last seen revision.
  - Sync state: last seen and synced revisions, attempt and success times, error code, safe summary,
    consecutive failures.
  - Manual sync through the admin API.
  - Remove the cache directories of deleted ConfigSets (ADR 0024).
  - Bound each fetch: total deadline, repository size limit, and a cap on concurrent fetches.

- [ ] **7. Consumption API**
  - ConfigSet metadata, file listing, raw file reads and revision listing; `latest` and exact-revision reads.
  - ETags with `If-None-Match` / `304`; immutable caching for exact revisions, short caching for latest.
  - `X-Config-Revision` and `X-Config-Validation-Status` headers.
  - Validation status for YAML, JSON and properties files; malformed files are still served raw.
  - Reads enforce policy.
  - Consumption routes, including `configsets:resolve` from slice 3, accept anonymous callers so that
    `public` rules apply; until then every route needs a token. For anonymous callers too, resolve must
    answer a missing path and a path they may not view identically (ADR 0013).

- [ ] **8. Audit and metrics**
  - Audit events table for namespace, ConfigSet, policy, credential, key and crypto operations,
    manual syncs and sync outcomes.
  - Audit records carry IDs, the path at the time and key fingerprints, never key material.
  - Actuator health and Prometheus metrics for sync, authorization decisions and content reads.
