# Folio v1 scope

This document narrows [`folio-design.md`](folio-design.md) to what the first version builds.
The design document describes where Folio may go; this document decides what is built now.

**Rule:** if something is not listed under *In scope*, it is not built in v1 — even if the design
document describes it. Extension points named in the design document may exist as interfaces only
where v1 has a real implementation behind them.

---

## Decisions

| Topic | Decision |
|---|---|
| Language and runtime | Kotlin, Java 25 toolchain, Spring Boot 4.1 |
| Persistence | PostgreSQL with Spring `JdbcClient` and plain SQL. No Spring Data JDBC, no jOOQ, no JPA |
| Migrations | Flyway |
| Identifiers | UUID in the database. API IDs are prefixed strings (`ns_…`, `cfg_…`, `cred_…`, `key_…`) |
| Git access | JGit (`org.eclipse.jgit`, `org.eclipse.jgit.ssh.apache`). No dependency on a system `git` binary |
| Module boundaries | One Gradle module. Spring Modulith application modules, verified by a test |
| Authentication | OAuth2 resource server (JWT bearer tokens) only |
| Client for v1 | Admin and consumption HTTP APIs, used through curl, checked-in `.http` files and springdoc's Swagger UI |
| Leases | PostgreSQL `SELECT … FOR UPDATE SKIP LOCKED`. No ShedLock |
| Crypto | JDK only: HKDF-SHA256 (Java 25 KDF API) and AES-256-GCM. No Bouncy Castle |

---

## In scope

### Namespaces and ConfigSets
- Create, rename, move and delete namespaces, with a closure table for ancestor queries.
- Sibling-slug uniqueness, including at the root.
- Create, move and delete ConfigSets; each maps to exactly one Git source.
- Resolve a path to a ConfigSet (`GET /api/v1/configsets:resolve?path=…`).
- ConfigSet IDs stay stable across renames and moves.

### Policy
- Action-based rules on namespaces and ConfigSets, grants only.
- Subjects: public, any authenticated, user, group, application.
- Nearest-rule inheritance, default deny.
- Decision explanation endpoint for administrators.
- **Bootstrap admins** from configuration (`folio.bootstrap.admins`): these subjects hold every action
  at the root, so the first namespaces and policies can be created without a UI.

### Security
- JWT validation through the Spring OAuth2 resource server.
- Mapping JWT claims to `ApplicationPrincipal` (user, groups, application ID).
- Authorization enforced in application services, not only by URL rules.

### Git source
- SSH transport through JGit with **mandatory host-key verification**.
- Trusted host keys come from deployment configuration (`folio.git.known-hosts`), per Git instance.
- Local bare-repository cache per source; the cache is disposable.
- File listing and reads at latest or an exact commit, beneath the ConfigSet root path.
- Path normalisation; absolute paths and traversal segments are rejected.
- Onboarding check with `ls-remote`: the credential can reach the repository, and the configured ref
  and root path exist.

### Credentials
- Credentials are scoped to a Git service instance and referenced by ConfigSets by stable ID.
- **Folio generates Ed25519 key pairs.** Only the public key (OpenSSH format) is ever returned.
  Private keys are never uploaded, downloaded or written to disk unencrypted.
- A credential holds key pairs as versions: `PENDING`, `ACTIVE` or `RETIRED`. At most one `ACTIVE`
  and one `PENDING` key per credential, enforced by partial unique indexes.
- **SSH key regeneration**, two-step:
  1. Generate a `PENDING` key pair and return its public key; sync keeps using the `ACTIVE` key.
  2. Activate it, optionally verifying access with `ls-remote` first. The previous key becomes
     `RETIRED` and its ciphertext is wiped; its public key and fingerprint are kept for audit.
- Emergency replacement: generate and activate immediately.
- Disable a credential.

### Encryption at rest
- Each private key is encrypted with AES-256-GCM under a key derived by HKDF-SHA256 from:
  the master secret, a fresh 32-byte random salt per encryption, and a fixed context label.
- A fresh 96-bit random nonce per encryption.
- Associated data binds each ciphertext to its row: `credentialId | keyId | masterKeyVersion`.
- Stored per row: ciphertext, nonce, salt, master-key version, algorithm name.
- Master secrets come from deployment configuration as a versioned key ring
  (`folio.crypto.master-keys`, `folio.crypto.active-key-version`). They are never stored in the
  database or the repository.

### Master-key rotation
- New encryptions always use the active master-key version; decryption uses the version on the row.
  Adding a new version and switching the active one requires no downtime.
- **Re-encryption pass** (`POST /api/v1/admin/crypto:reencrypt`): every key whose master-key version
  is not the active one is decrypted and re-encrypted with a new salt and nonce under the active
  version. One transaction per row with an optimistic version check; safe to re-run.
- The endpoint reports how many rows still use each master-key version, so an operator knows when an
  old version can be removed from configuration.
- Startup fails clearly if a row references a master-key version that is not configured.
- SSH key pairs do not change during master-key rotation; nothing changes in Git.

### Synchronization
- Polling scheduler that requests sync for due ConfigSets; fetches run on a bounded executor.
- Per-source leases so a source is never fetched concurrently.
- Fetch-based change detection: compare the resolved ref with the last seen revision.
- Sync state: last seen and synced revisions, last attempt and success times, error code and safe
  summary, consecutive failure count.
- Manual sync request through the admin API.

### Consumption API
- ConfigSet metadata, file listing, raw file reads, revision listing.
- `latest` and exact-revision reads.
- ETags and `If-None-Match` / `304`; immutable caching for exact revisions, short caching for latest.
- Headers: `X-Config-Revision`, `X-Config-Validation-Status`.

### Validation
- Validation status as metadata for YAML, JSON and properties files.
- Malformed files are still synced and served raw.

### Operations
- Audit events persisted to a table: namespace, ConfigSet, policy, credential, key and crypto
  operations, manual syncs and sync outcomes. Audit records carry IDs, the path at the time, and key
  fingerprints — never key material.
- Actuator health and Prometheus metrics for sync, authorization decisions and content reads.

---

## Out of scope for v1

- Administrative web UI, OIDC browser login, sessions and CSRF handling.
- LDAP authentication.
- CLI and SDK.
- Importing existing SSH private keys.
- KMS, Vault or other external key management.
- HTTPS Git transport and provider-native onboarding (GitHub Apps, GitLab OAuth).
- Webhook triggers.
- `ls-remote` as a polling optimisation.
- Bundle download as ZIP.
- Policy-decision caching (add only when measurements justify it).
- Database-backed ConfigSets and editing content through Folio.
- Views, profiles, rendering and composition.
- Push delivery and Spring Cloud Config compatibility.
- Explicit deny rules and policy expressions.

---

## Open questions

None. The deploy-key question is resolved by [ADR 0003](adr/0003-ssh-key-registration.md).
