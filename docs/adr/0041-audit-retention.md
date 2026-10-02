# 0041. Audit retention

Status: Accepted (2026-10-02). Amends ADR 0038.

## Context

ADR 0038 left retention open: the `audit_event` table grows without bound, and its records name subjects, which may be
personal data. The project owner chose to keep records for 90 days.

## Decision

- **Retention.** `folio.audit.retention`, default `90d`, must be positive. Records whose `occurred_at` is older than
  `now() - retention`, by the database clock, are deleted.
- **Schedule.** Every instance prunes once a day, first 10 minutes after start, so instances restarted more often than
  daily still prune. The interval is fixed: a daily run keeps the table within a day of the retention.
- **Batches.** A run deletes at most 1,000 records per statement, oldest first, each statement committing on its own,
  until a batch comes back short. A large first run never holds one long transaction.
- **Concurrency.** Each batch selects its rows `for update skip locked`, so instances pruning at the same time skip
  each other's rows instead of waiting. A run that finds nothing deletes nothing. No lease is needed.
- **Index.** A new index on `occurred_at` serves the delete; the `(resource_id, occurred_at)` index cannot.
- **Logging.** A run logs only the number of records it deleted.

## Consequences

- Records older than the retention are gone. Deployments that need longer records export them to a log store or SIEM;
  Folio does not build that export.
- Shortening the retention deletes the older records at the next run.
