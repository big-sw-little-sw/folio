# 0033. Fetch bounds

Status: Accepted (2026-10-01). Amends ADR 0027.

## Context

Design 23.2 and slice 6 bound each fetch by a total deadline, a repository size limit and a cap on concurrent fetches.
JGit's 30-second timeout applies to connecting and to each idle read, not to a whole fetch (ADR 0024).

## Decision

- **Deadline.** `folio.git.fetch-deadline` (default 5 minutes) bounds each fetch, from before its `ls-remote` to the end
  of the transfer. The source module implements it, so the onboarding check is bounded too, which is why it sits under
  `folio.git` with the cache directory rather than under `folio.sync`. A `FetchDeadline` progress monitor answers
  `isCancelled()` with true once the deadline has passed; JGit asks while it negotiates and after each object it
  receives, and then fails the fetch. The classifier reports such a fetch as `DEADLINE_EXCEEDED`, a new code, rather
  than the transport failure JGit reports. `ls-remote` takes no monitor; JGit's timeouts bound it, and a fetch that
  starts after the deadline is cancelled at its first check.
- **Concurrency.** Each instance runs at most `folio.sync.max-concurrent-fetches` sync fetches at once (ADR 0032).
  Onboarding checks are administrator requests and are not counted.
- **Size.** Not built. JGit's fetch has no client-side bound on the received pack: `PackParser` has an object size
  limit, but the fetch connection does not set it, and nothing counts received bytes. A server sending one large pack
  is bounded only by the deadline.

## Consequences

- A server that stalls without sending anything is cut off by JGit's 30-second idle timeout, not the deadline.
- Open question: how to bound repository size. Options are counting objects through the monitor's "Receiving objects"
  task (an object count, not bytes, and it relies on JGit's task title), checking the cached repository's size after a
  fetch and discarding it (bounds disk, not transfer), wrapping the SSH channel's input stream, or relying on limits at
  the Git service.
