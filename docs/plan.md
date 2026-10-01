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

- [ ] **3. ConfigSets**
  - Create, move and delete ConfigSets; each maps to exactly one Git source.
  - ConfigSet IDs stay stable across renames and moves of the ConfigSet and its ancestors.
  - Deleting a namespace that contains ConfigSets is rejected (ADR 0001).
  - ConfigSet writes that require their namespace to exist take the namespace tree lock, so a concurrent
    namespace delete fails with NamespaceNotEmptyException rather than a foreign-key error.
  - `GET /api/v1/configsets:resolve?path=…` resolves a path to a ConfigSet.
  - Policy rules attach to ConfigSets as well as namespaces; a ConfigSet's own rule is nearest, then its
    namespace path (`policy_rule.config_set_id`, design 19.4).
  - Admin API behind policy checks; API IDs are prefixed strings (`ns_…`, `cfg_…`).

- [ ] **4. Credentials and crypto**
  - Credentials scoped to a Git service instance and referenced by stable ID; disable a credential.
  - Ed25519 key generation; only the OpenSSH public key is ever returned.
  - Private keys encrypted with AES-256-GCM under HKDF-SHA256 keys from the configured master-key ring;
    fresh salt and nonce per encryption; associated data `credentialId | keyId | masterKeyVersion`.
  - Two-step key regeneration (`PENDING` then activate, optional `ls-remote` check) and emergency replacement;
    partial unique indexes allow one `ACTIVE` and one `PENDING` key; retired keys lose their ciphertext.
  - Master-key rotation: `POST /api/v1/admin/crypto:reencrypt` re-encrypts per row with an optimistic check,
    is safe to re-run and reports rows per master-key version.
  - Startup fails clearly if a row references an unconfigured master-key version.

- [ ] **5. Git source**
  - JGit SSH transport with mandatory host-key verification against `folio.git.known-hosts`.
  - Disposable local bare-repository cache per source.
  - File listing and reads at latest or an exact commit, beneath the ConfigSet root path.
  - Path normalisation rejects absolute paths and traversal segments.
  - Onboarding check with `ls-remote`: credential reaches the repository; ref and root path exist.
  - Integration tests run against an SSH Git server in Testcontainers (ADR 0004).

- [ ] **6. Sync**
  - Polling scheduler requests sync for due ConfigSets; fetches run on a bounded executor.
  - Per-source leases with `SELECT … FOR UPDATE SKIP LOCKED`; a source is never fetched concurrently.
  - Fetch-based change detection against the last seen revision.
  - Sync state: last seen and synced revisions, attempt and success times, error code, safe summary,
    consecutive failures.
  - Manual sync through the admin API.

- [ ] **7. Consumption API**
  - ConfigSet metadata, file listing, raw file reads and revision listing; `latest` and exact-revision reads.
  - ETags with `If-None-Match` / `304`; immutable caching for exact revisions, short caching for latest.
  - `X-Config-Revision` and `X-Config-Validation-Status` headers.
  - Validation status for YAML, JSON and properties files; malformed files are still served raw.
  - Reads enforce policy.

- [ ] **8. Audit and metrics**
  - Audit events table for namespace, ConfigSet, policy, credential, key and crypto operations,
    manual syncs and sync outcomes.
  - Audit records carry IDs, the path at the time and key fingerprints, never key material.
  - Actuator health and Prometheus metrics for sync, authorization decisions and content reads.
