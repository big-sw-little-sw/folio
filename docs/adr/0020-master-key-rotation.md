# 0020. Master-key rotation and the startup check

Status: Accepted (2026-10-01)

## Context

v1-scope asks for a re-encryption pass, `POST /api/v1/admin/crypto:reencrypt`, with one transaction per row and
an optimistic check, safe to re-run, reporting keys per master-key version; and for startup to fail if a row
references an unconfigured version.

## Decision

- The pass selects every key whose `master_key_version` is not the active one, then for each: decrypts,
  encrypts with a new salt and nonce under the active version, and runs
  `update … where id = ? and master_key_version = <version read>`. The service method is not transactional,
  so each update commits on its own.
- If another pass moved the row first, or a retirement wiped it (version null), the update matches nothing and
  the row is skipped. Concurrent passes and re-runs are therefore safe, and a re-run with nothing to do changes
  nothing.
- A row that fails to decrypt stops the pass with a server error; rows already done stay done.
- `GET /api/v1/admin/crypto` and the re-encryption response both return the active version and, for every
  configured version, the number of stored keys under it. A version with zero keys can be removed from
  configuration.
- The startup check runs as a `SmartInitializingSingleton`: after every singleton exists, so after Flyway's
  initializer has migrated, and before the web server accepts requests. Its message names the missing versions
  and the property, never a secret.

## Consequences

- Rotation is: add the new version, make it active, redeploy, re-encrypt, check the counts, remove the old
  version, redeploy. SSH keys and Git services are unaffected.
- The check makes removing a version that still has keys fail at startup instead of at the next sync.
