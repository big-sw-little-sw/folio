# 0036. Synced revisions and reads on demand

Status: Accepted (2026-10-02). Amends ADR 0032.

## Context

ADR 0032 makes the database the truth about sync and each instance's cache disposable, and leaves reads on an instance
whose cache lacks the synced commit to slice 7. A cache's branch tip is not the synced revision: the onboarding check
fetches too, and a fetched tip may not be recorded. The consumption API needs `latest`, a rule for which exact
revisions it serves, and a revision listing.

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
  read fetches the branch with `SourceAccess.fetch`, under the per-ConfigSet lock and the fetch deadline, and reads
  again. A commit still missing gives 404, "no longer available".

## Consequences

- Any caller allowed to read, anonymous ones under a `public` rule included, can cause a fetch on an instance whose
  cache lacks the revision. Concurrent reads queue on the fetch lock.
- A synced commit that leaves the branch, such as after a force push, stays readable on instances that still hold it.
  Elsewhere it is 404, and every read of it fetches again.
- The table keeps one row per synced revision and is not pruned in v1.
