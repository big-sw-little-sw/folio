# 0027. Onboarding check and failure codes

Status: Accepted (2026-10-01)

## Context

v1-scope asks for an onboarding check with `ls-remote` (the credential reaches the repository; the ref and root path
exist) and an optional `ls-remote` check before activating a pending key (ADR 0017). Git failures carry server output,
URLs and exception messages that must never reach responses or logs (CLAUDE.md, design 23.2). Slice 6 stores an error
code and a safe summary in sync state.

## Decision

- The check is an explicit call, `POST /api/v1/admin/configsets/{id}:check`, not part of create: creating a ConfigSet
  does not contact the Git service. Authorization is in ADR 0023.
- The check runs `ls-remote` for the branch, fetches it into the cache, and verifies that the root path is a directory
  at the tip. It answers 200 with `{"ok": true, "commitId": …}` or `{"ok": false, "code": …, "summary": …}`; a failed
  check is a result, not an HTTP error.
- An optional body `{"keyId": "key_…"}` checks with that pending key of the credential instead of the active one, so
  an administrator can verify a newly registered key before activating it. A key that is not the credential's pending
  key gives 409. `CredentialKeyPairs.pending` decrypts it; like `active`, no HTTP layer may use it, which
  `ModularityTests` checks.
- Failures have stable codes with fixed summaries: `HOST_KEY_REJECTED`, `AUTH_FAILED`, `REPOSITORY_NOT_FOUND`,
  `BRANCH_NOT_FOUND`, `ROOT_PATH_NOT_FOUND`, `UNREACHABLE`, `TRANSPORT_FAILURE`. Classification looks only at types:
  the key database's rejection flag; `NoRemoteRepositoryException`; sshd's disconnect code 14 (no more authentication
  methods); `ConnectException`, `NoRouteToHostException`, `UnknownHostException` and `SocketTimeoutException` in the
  cause chain; the `ls-remote` result and the tree walk. Everything else is `TRANSPORT_FAILURE`.
- Exception messages and transport output never reach responses or logs. Folio logs one line per failure with the
  ConfigSet ID and code. Fetch failures outside the check surface as `SourceAccessFailedException`, mapped to 502 with
  the summary and code.

## Consequences

- An operator who needs the underlying error must turn on JGit or sshd logging (ADR 0025).
- A Git service that answers "not found" for a repository the key cannot read gives `REPOSITORY_NOT_FOUND`, not
  `AUTH_FAILED`; the summary says either may apply.
- A credential whose Git instance was removed from configuration fails the check with the existing 400 for an unknown
  instance (ADR 0016).
