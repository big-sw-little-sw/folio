package io.github.big_sw_little_sw.folio.sync.internal

import io.github.big_sw_little_sw.folio.configset.ConfigSetId
import io.github.big_sw_little_sw.folio.configset.ConfigSetPath
import io.github.big_sw_little_sw.folio.source.SourceFailure
import io.github.big_sw_little_sw.folio.sync.SyncEvent
import io.github.big_sw_little_sw.folio.sync.SyncFailed
import io.github.big_sw_little_sw.folio.sync.SyncRecovered
import io.github.big_sw_little_sw.folio.sync.SyncRevisionChanged

/** What a sync fetch gave: the branch tip, or why it failed. */
sealed interface FetchResult {
    data class Fetched(
        val revision: String,
    ) : FetchResult

    data class Failed(
        val failure: SourceFailure,
    ) : FetchResult
}

/** The sync state that recording an attempt replaced: the synced revision and the last attempt's failure code. */
data class PreviousSync(
    val syncedRevision: String?,
    val failureCode: String?,
)

/** How a recorded attempt turned out; the `outcome` tag of the attempts metric (ADR 0039). */
enum class AttemptOutcome(
    val tag: String,
) {
    SYNCED("synced"),
    UNCHANGED("unchanged"),
    FAILED("failed"),
}

fun attemptOutcome(
    previous: PreviousSync,
    result: FetchResult,
): AttemptOutcome =
    when (result) {
        is FetchResult.Fetched -> {
            if (result.revision == previous.syncedRevision) AttemptOutcome.UNCHANGED else AttemptOutcome.SYNCED
        }

        is FetchResult.Failed -> {
            AttemptOutcome.FAILED
        }
    }

/**
 * The event for a recorded attempt if its outcome changed (ADR 0038): a new synced revision, a failure code other than
 * the previous attempt's, or a success after failures. Otherwise null. [path] is read only for an event.
 */
fun outcomeEvent(
    id: ConfigSetId,
    previous: PreviousSync,
    result: FetchResult,
    path: () -> ConfigSetPath,
): SyncEvent? =
    when (result) {
        is FetchResult.Fetched -> {
            val failureCode = previous.failureCode
            when {
                failureCode != null -> {
                    SyncRecovered(id, path(), result.revision, previous.syncedRevision, failureCode)
                }

                result.revision != previous.syncedRevision -> {
                    SyncRevisionChanged(id, path(), result.revision, previous.syncedRevision)
                }

                else -> {
                    null
                }
            }
        }

        is FetchResult.Failed -> {
            val code = result.failure.name
            if (code == previous.failureCode) null else SyncFailed(id, path(), code, previous.failureCode)
        }
    }
