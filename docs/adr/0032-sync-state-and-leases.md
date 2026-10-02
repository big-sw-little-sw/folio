# 0032. Sync state and leases

Status: Accepted (2026-10-01). Amends ADR 0015 and ADR 0027. Amended by ADR 0035 (the consumption module depends on
`sync`), ADR 0036 (synced revisions and reads on demand) and ADR 0038 (an attempt and its audit record are recorded in
one transaction).

## Context

Slice 6 builds polling sync (design 14): find due ConfigSets, fetch each, compare the branch tip with the last seen
revision and record sync state. Several Folio instances share one database, each with its own disposable cache
(ADR 0024). v1-scope requires leases through `SELECT … FOR UPDATE SKIP LOCKED`, without ShedLock.

## Decision

- **Module.** A new module `sync` depends on `configset`, `source` and `policy`. Nothing depends on it. `configset`
  gains `ConfigSetSources.find` (a ConfigSet's source, without authorization, for a caller-less sync, like
  `CredentialKeyPairs`) and `ConfigSetService.requireAllowed(id, action)` for modules that act on ConfigSets. The `cfg_`
  conversion moves from `configset.web` to the module's public API, because the sync API accepts and returns ConfigSet
  IDs. This amends ADR 0015.
- **Shared truth, local caches.** The database holds sync state; each instance's cache is disposable. Sync records the
  synced commit ID, and the instance holding the lease fetches into its own cache. Readers on other instances must
  make sure that commit is in their cache, fetching on demand (slice 7). Cache "latest" is not the synced revision: the
  onboarding check also fetches.
- **State.** `sync_state` has one row per ConfigSet, inserted by a synchronous `ConfigSetCreated` listener in the
  create's transaction (ADR 0034) and deleted by cascade. It holds the last seen and last synced revisions, the last
  attempt and success times, the error code and summary, the consecutive failure count, the next due time and the lease.
  In v1, seen and synced are the same commit after a successful fetch: nothing between fetching and serving can reject
  a commit. They differ once a step such as validation can refuse to serve a fetched tip.
- **Change detection.** Fetch-based: a successful fetch records its tip as seen and synced. An unchanged tip is a
  successful attempt that leaves the revisions as they were. A failure keeps the last synced revision, including a
  deleted branch (`BRANCH_NOT_FOUND`).
- **Leases.** A claim is one `UPDATE … FROM (SELECT … FOR UPDATE SKIP LOCKED LIMIT n)` that sets `lease_owner` (a
  random ID per process start) and `lease_until`, and commits. The fetch runs outside any transaction. The result is
  recorded in one statement only if `lease_owner` and `lease_until` still match the claim, so neither another
  instance nor an earlier claim of the same instance can overwrite a newer lease. A lease lasts the fetch deadline plus
  a three-minute margin. The deadline is enforced on the wall clock for the TCP connect, `ls-remote`, the transfer,
  delta resolution and waiting for the fetch lock (ADR 0033). SSH key exchange and authentication (up to 2 minutes)
  and opening a command channel (up to 30 seconds) are not cut, so fetching ends at most 2.5 minutes after the
  deadline. The remaining 30 seconds cover loading the ConfigSet, the size check and recording. While leased,
  `next_due_at` equals `lease_until`,
  so a crashed instance's ConfigSets become due when their leases expire. Times come from the database clock, so
  instance clock skew does not matter.
- **Never twice in one instance.** The poller keeps the leases it is running and excludes those ConfigSets from its
  claims, so even after an expired lease it never starts a second sync of a ConfigSet it is still syncing.
- **Due-ness and backoff.** A ConfigSet is due `folio.sync.interval` (default 1 minute) after its last attempt. Each
  consecutive failure doubles that, up to `folio.sync.max-backoff` (default 30 minutes). A new ConfigSet is due at once.
- **Scheduling.** Every `folio.sync.poll-interval` (default 10 seconds) an instance claims at most as many due
  ConfigSets as it has free slots on its fetch executor, `folio.sync.max-concurrent-fetches` (default 4) threads, so a
  claimed ConfigSet never waits in a queue while its lease runs. The tasks are registered through `SchedulingConfigurer`
  rather than `@Scheduled`, so the delays come from the validated properties instead of repeating their defaults in
  placeholders.
- **Manual sync.** `POST /api/v1/admin/configsets/{id}:sync` needs the new action `CONFIG_SET_SYNC` on the ConfigSet.
  It makes the ConfigSet due now and answers 202 with its sync state; the next poll of any instance syncs it. A request
  during a running sync moves `next_due_at` before the lease end, and recording keeps it, so the ConfigSet syncs again
  at once. `GET /api/v1/admin/configsets/{id}/sync` needs `CONFIG_SET_VIEW`.
- **Credential failures.** For sync, `SourceAccess.fetch` turns an unusable credential into failure codes instead of
  exceptions: `CREDENTIAL_DISABLED`, `GIT_INSTANCE_NOT_CONFIGURED` and `NO_ACTIVE_KEY`. The onboarding check still
  refuses them with 409 before connecting (ADR 0027). This adds to ADR 0027's codes.
- **Logging.** Sync logs at most a ConfigSet ID, a failure code and a duration.

## Consequences

- One ConfigSet's failure never stops others; each records its own code.
- An unexpected exception during a sync records no attempt. The poller releases the lease so the ConfigSet is due again
  after the interval, with no failure code and no backoff, and logs the ConfigSet ID and the exception's type. If the
  release fails too (a database outage, say), the lease expires instead. Either way the fetch slot is returned and the
  ConfigSet is no longer counted as running, so the instance keeps syncing.
- On shutdown, the poller waits ten seconds for running syncs, then releases their leases due at once before
  interrupting them, so an interrupted sync records neither a failure nor backoff. Sync threads are daemons.
- A sync can outlast its lease only if the size check and recording take longer than the 30 seconds the margin leaves
  them, such as during a long database or JVM pause. Another instance may then fetch the same ConfigSet into its own
  cache at the same time; the late result is not recorded.
- Per-ConfigSet intervals, `ls-remote` polling and webhooks are not built; each changes how `next_due_at` is set, not
  the lease.
