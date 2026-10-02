# 0039. Metrics

Status: Accepted (2026-10-02)

## Context

v1-scope asks for Prometheus metrics for sync, authorization decisions and content reads; design 24.1 lists more.
Prometheus stores a series per tag combination, so a tag holding a ConfigSet ID, a path or a subject would grow without
bound. `micrometer-registry-prometheus` is already a runtime dependency, and Micrometer comes with the actuator starter.

## Decision

Folio's own meters, under `folio.`. Prometheus names add `_total` to counters and `_seconds` to timers.

| Meter | Type | Tags |
|---|---|---|
| `folio.sync.attempts` | counter | `outcome`: `synced`, `unchanged`, `failed`; `code`: a source failure code, or `none` |
| `folio.sync.fetch.duration` | timer | none |
| `folio.consumption.fetches` | counter | `result`: `fetched`, `missing`, `busy`, `too_large`, `failed` |
| `folio.authorization.decisions` | counter | `action`; `result`: `allowed`, `denied`; `reason`: `super_admin`, `rule`, `not_granted`, `no_rule` |

Content reads use Spring's `http.server.requests` timer, which every request already records, tagged `method`, `uri`,
`status`, `outcome` and `exception`. Its consumption series have these `uri` values:

| Route | `uri` |
|---|---|
| Metadata | `/api/v1/configsets/{id}` |
| File listing | `/api/v1/configsets/{id}/files` |
| Raw file | `/api/v1/configsets/{id}/files/{*path}` |
| Revision listing | `/api/v1/configsets/{id}/revisions` |

- **Sync.** Every sync fetch is timed, failed ones included. An attempt is counted once recorded; one that is not
  (the ConfigSet was deleted, or the lease was lost) has no known outcome. `unchanged` is a success whose tip is the
  synced revision, including a recovery to the same revision.
- **On-demand fetches** (ADR 0036), one count per read that needed one. `fetched`: the cache now holds the commit,
  whether this read fetched it or waited for another fetch. `missing`: still missing after a fetch, or found missing
  within the last minute. `busy`: too many on-demand fetches, or another fetch of the ConfigSet held the lock too long.
  `too_large`: refused without fetching after the last sync found the repository too large. `failed`: the fetch failed.
- **Authorization decisions** about the current caller, from `requireAllowed` and `isAllowed`. Listing filters decide
  per listed item, so each counts. Explanations and the `public` check for caching decide for someone else and are not
  counted. No subject or resource tags.
- **Content reads** are Spring's series above. `uri` is the route template, never the ID or file path, and `status` is
  the exact status sent, such as `200`, `304`, `401` or `404`, so both stay bounded. Latest and exact-revision reads
  are not told apart: the revision is a query parameter, and a tag for it would need code of Folio's own for a
  distinction no alert needs yet.
- No ConfigSet IDs, paths, subjects or commit IDs in tags. The largest meter, decisions, has at most one series per
  action, result and reason.

## Consequences

- Per-ConfigSet health and rates are not visible in metrics; the sync state API and the audit table hold them.
- Design 24.1's other metrics (fetch bytes, polling lag, policy latency, response sizes, validation results, cache hit
  rate, credential age) are not built.
- Content-read rates per route and status come for free, but not split by `latest` and exact revisions.
