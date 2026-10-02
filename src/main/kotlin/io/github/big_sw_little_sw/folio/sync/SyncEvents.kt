package io.github.big_sw_little_sw.folio.sync

import io.github.big_sw_little_sw.folio.configset.ConfigSetId
import io.github.big_sw_little_sw.folio.configset.ConfigSetPath

/**
 * Published synchronously inside the transaction that records a sync request or a sync outcome; a listener that throws
 * rolls the recording back. Audit records them (ADR 0038). Outcomes are published only when they change something
 * worth recording: no-change successes and repeated identical failures publish nothing. Failure codes are source
 * failure names.
 */
sealed interface SyncEvent {
    val id: ConfigSetId
    val path: ConfigSetPath
}

/** A caller asked for a sync now. */
data class SyncRequested(
    override val id: ConfigSetId,
    override val path: ConfigSetPath,
) : SyncEvent

/** A sync without a preceding failure synced a new revision; [previousRevision] is null on the first sync. */
data class SyncRevisionChanged(
    override val id: ConfigSetId,
    override val path: ConfigSetPath,
    val revision: String,
    val previousRevision: String?,
) : SyncEvent

/** A sync failed with a code other than the previous attempt's; [previousFailureCode] is null on the first failure. */
data class SyncFailed(
    override val id: ConfigSetId,
    override val path: ConfigSetPath,
    val failureCode: String,
    val previousFailureCode: String?,
) : SyncEvent

/** A sync succeeded after failures; [revision] may equal [previousRevision]. */
data class SyncRecovered(
    override val id: ConfigSetId,
    override val path: ConfigSetPath,
    val revision: String,
    val previousRevision: String?,
    val previousFailureCode: String,
) : SyncEvent
