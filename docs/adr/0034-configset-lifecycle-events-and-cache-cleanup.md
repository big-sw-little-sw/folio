# 0034. ConfigSet lifecycle events and cache cleanup

Status: Accepted (2026-10-01). Amends ADR 0024 and ADR 0031. Amended by ADR 0038: the events carry the ConfigSet's path,
and audit also listens to them in the transaction.

## Context

Every ConfigSet needs a sync state row from its creation, and the cached repositories of deleted ConfigSets must go
(ADR 0024). `sync` depends on `configset`, so `configset` cannot call it. Each instance has its own cache, and a delete
runs on one instance only. ADR 0031 kept events to checks and reactions that must run in the publisher's transaction.

## Decision

- `ConfigSetService.create` publishes `ConfigSetCreated` inside its transaction. Sync listens synchronously with
  `Propagation.MANDATORY` and inserts the state row, so a ConfigSet never exists without one. This is the kind of
  reaction ADR 0031 allows.
- `ConfigSetService.delete` publishes `ConfigSetDeleted`. Sync listens with `@TransactionalEventListener(AFTER_COMMIT)`
  and deletes that ConfigSet's cached repository on this instance, so a delete that rolls back keeps its cache. This
  extends ADR 0031: an after-commit reaction in a downstream module is the other use of events.
- Every `folio.sync.interval`, each instance sweeps its cache: it lists the entries named `<uuid>.git` (canonical
  lowercase UUID) and deletes those whose ConfigSet has no sync state row. It lists before it queries, so a repository
  whose ConfigSet exists is always found. Nothing else under the cache directory, such as the SSH home directory, is
  ever deleted.
- Deleting a repository waits for reads but not for a running fetch. A fetch of a deleted ConfigSet may fail or leave a
  repository behind; the next sweep removes it.
- The database migration creates state rows for ConfigSets that already exist. An instance without this release
  creates ConfigSets without state rows, which then never sync, so all instances must be upgraded together. Folio is
  pre-release and runs no mixed versions.
- The sweep never lists symbolic links, and deleting a repository does not follow them.

## Consequences

- Other instances remove a deleted ConfigSet's repository within one sync interval.
- The sweep reads the cache directory and runs one query per interval, whatever the number of ConfigSets.
- Test contexts share one cache directory but not their databases, so tests run the sweep only explicitly; the test
  configuration sets the interval to an hour.
