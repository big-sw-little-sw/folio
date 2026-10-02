# 0033. Fetch bounds

Status: Accepted (2026-10-01). Amends ADR 0027.

## Context

Design 23.2 and slice 6 bound each fetch by a total deadline, a repository size limit and a cap on concurrent fetches.
JGit's 30-second timeout applies to connecting and to each idle read, not to a whole fetch (ADR 0024). A server that
keeps sending, even slowly, never trips it, and neither does one whose progress messages arrive often enough. A sync
that outlives its lease lets another instance fetch the same ConfigSet at the same time (ADR 0032).

## Decision

- **Deadline.** `folio.git.fetch-deadline` (default 5 minutes) is a wall-clock bound on each fetch, from before its
  `ls-remote` to the end of the transfer, including waiting for another fetch of the same ConfigSet in this process.
  The source module enforces it, so the onboarding check is bounded too; that is why it sits under `folio.git` with
  the cache directory rather than under `folio.sync`.
  - Each operation gets its own session factory wrapper. Connects get no more than the time left.
  - A watchdog fires at the deadline and closes the input and error streams of every SSH command the operation started,
    then destroys it. JGit's blocked read ends at once, whatever the server sends or withholds. Commands started after
    the deadline are cut as they start, and sessions are no longer handed out.
  - Closing the session would be simpler, but JGit's `SshdSession.disconnect` clears its fields without
    synchronization and is not safe from another thread, and `SshdSessionFactory.close` does not close live sessions.
    `Process.destroy` alone closes the channel gracefully, which waits for the server.
  - Waiting for the per-ConfigSet fetch lock uses `tryLock` with the time left.
  - All of these report `DEADLINE_EXCEEDED`, a new code, and count as a failure with backoff. A distinct "busy" outcome
    that releases the lease without a failure was considered; with the poller never claiming a ConfigSet it is still
    syncing (ADR 0032), the lock is only contended by an onboarding check, which is rare enough not to need it.
- **Size.** `folio.git.max-repository-size` (default 1GB) bounds the ConfigSet's cached repository on disk. After each
  fetch, still holding the fetch lock, Folio sums the sizes of the repository's regular files, without following
  symbolic links. A repository over the limit is deleted and the fetch fails with `REPOSITORY_TOO_LARGE`, a new code; the
  last synced revision stays as it was. Together with the deadline this bounds both disk and time.
- **Concurrency.** Each instance runs at most `folio.sync.max-concurrent-fetches` sync fetches at once (ADR 0032).
  Onboarding checks are administrator requests and are not counted.

## Options considered for size

- JGit's fetch has no client-side bound on the received pack: `PackParser` has an object size limit, but the fetch
  connection does not set it, and nothing counts received bytes.
- Counting objects through a progress monitor's "Receiving objects" task: an object count, not bytes, and it relies on
  JGit's task title.
- Wrapping the SSH channel's input stream to count bytes: bounds the transfer itself, but needs JGit and sshd internals.
- Limits at the Git service: outside Folio's control.

## Consequences

- A fetch, including the onboarding check, ends within the deadline plus the time to close its streams and record the
  result, whatever the server does.
- A large repository can still be transferred in full before the size check discards it; the deadline bounds how long
  that takes. Each retry, after backoff, transfers it again.
- Once a repository is discarded for size, this instance's cache no longer holds the last synced revision. A reader
  that fetches on demand (slice 7) meets the same limit and gets the same failure.
