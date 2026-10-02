# 0036. Synced revisions and reads on demand

Status: Accepted (2026-10-02). Amends ADR 0024, ADR 0032 and ADR 0033.

## Context

ADR 0032 makes the database the truth about sync and each instance's cache disposable, and leaves reads on an instance
whose cache lacks the synced commit to slice 7. A cache's branch tip is not the synced revision: the onboarding check
fetches too, and a fetched tip may not be recorded. The consumption API needs `latest`, a rule for which exact
revisions it serves, and a revision listing. Reads can come from anonymous callers under a `public` rule, so a read that
fetches must not let callers make an instance fetch without limit.

## Decision

- `latest` is `sync_state.last_synced_revision`. A ConfigSet that never synced gives 404, "The ConfigSet has not synced
  yet".
- A table `synced_revision` (ConfigSet ID, commit ID, synced at; primary key ConfigSet and commit; cascade from
  `sync_state`) holds every commit that became a ConfigSet's synced revision. The statement that records a successful
  sync writes it too, as a data-modifying CTE, and only when the synced revision changes. A commit that becomes the
  synced revision again, after a branch reset, gets a new time, so the newest entry is always `latest`. The migration
  adds the current synced revisions.
- Exact reads accept only commits in that table, otherwise 404. Older commits that arrived with a fetch are not served:
  consumers see only what Folio synced.
- `SyncedRevisions` is the sync module's API for this. Like `ConfigSetSources`, it does not authorize, and no HTTP layer
  may use it.
- The consumption API always reads an exact commit, never `RevisionRef.Latest`. If this instance's cache lacks it, the
  read fetches on demand, bounded as follows, and reads again:
  - **One fetch per ConfigSet.** The per-ConfigSet fetch lock is now taken before the ls-remote, not only around the
    transfer, so sync, the onboarding check and reads never open SSH sessions for the same ConfigSet at once.
    `SourceAccess.fetchIfMissing` checks the cache again once it holds the lock, so a read that waited for another
    fetch usually finds the commit and fetches nothing.
  - **Short waits.** A read waits at most 5 seconds for another fetch of the same ConfigSet, then gets 503 with
    `Retry-After`. Sync and the check still wait up to their deadline (ADR 0033).
  - **Capped.** At most `folio.consumption.max-concurrent-fetches` (default 2) on-demand fetches run at once per
    instance. A read beyond that gets 503 at once rather than queueing on a request thread.
  - **Missing commits are remembered.** A commit still missing after a fetch, such as one force-pushed away, is not
    fetched for again on this instance for a minute, the default sync interval; reads get 404 "no longer available"
    meanwhile. The memory is per instance and keeps only unexpired entries.
  - **No fetch for a repository over the size limit.** While the last sync failed with `REPOSITORY_TOO_LARGE`, a read
    that needs a fetch fails with that code (502) without fetching: the fetch would download the repository and discard
    it again (ADR 0033).

## Consequences

- Each instance runs at most `max-concurrent-fetches` on-demand fetches plus its sync fetches, and at most one fetch per
  ConfigSet at a time.
- Under load from many ConfigSets with cold caches, reads get 503 until fetches complete; clients retry after
  `Retry-After`.
- A synced commit that leaves the branch stays readable on instances that still hold it; elsewhere it is 404, with at
  most one fetch per minute per instance for each such commit: N such commits allow N fetches a minute, run one at a
  time per ConfigSet and within the cap.
- Reads waiting for another fetch of the same ConfigSet hold a slot of `max-concurrent-fetches`, so a long fetch of one
  ConfigSet can briefly make cold reads of other ConfigSets answer 503.
- A fetch that fails, rather than finding the commit missing, is not remembered: the next read fetches again, within the
  cap.
- The table keeps one row per synced revision and is not pruned in v1.
