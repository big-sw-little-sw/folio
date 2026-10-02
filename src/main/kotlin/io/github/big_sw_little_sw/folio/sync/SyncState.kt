package io.github.big_sw_little_sw.folio.sync

import io.github.big_sw_little_sw.folio.configset.ConfigSetId
import java.time.Instant

/**
 * How a ConfigSet's source last synced (design 14.4, ADR 0032). Revisions are commit IDs. [lastSeenRevision] is the
 * branch tip at the most recent successful fetch and [lastSyncedRevision] the revision served as latest; in v1 a
 * successful fetch sets both. [errorCode] and [errorSummary] describe the most recent attempt if it failed: a source
 * failure code and its fixed summary, never transport output. A failure keeps the last synced revision.
 */
data class SyncState(
    val configSetId: ConfigSetId,
    val lastSeenRevision: String?,
    val lastSyncedRevision: String?,
    val lastAttemptAt: Instant?,
    val lastSuccessAt: Instant?,
    val errorCode: String?,
    val errorSummary: String?,
    val consecutiveFailures: Int,
    val nextDueAt: Instant,
)
