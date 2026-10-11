package io.github.big_sw_little_sw.folio.git.internal

import io.github.big_sw_little_sw.folio.catalog.ConfigSets
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Records the result of one sync in one transaction (design 10.6, 14): the sync state, the release of the lease, the
 * publish of a new tip and the audit records. Sync writes an audit record only for a change, because otherwise each
 * interval would write a row. The records are these:
 *
 * - `SYNC_FAILED` when the failure code differs from the code of the previous attempt.
 * - `SYNC_RECOVERED` for the first success after a failure.
 * - `SYNC_REVISION_PUBLISHED` when sync publishes the tip. A recovery that also publishes writes both records.
 */
@Service
class SyncRecorder(
    private val syncs: SyncRepository,
    private val revisions: GitSourceRepository,
    private val configSets: ConfigSets,
    private val audit: GitAudit,
    private val properties: SyncProperties,
) {
    /**
     * Records [attempt] and releases [lease]. Returns false, and changes nothing, if the lease no longer belongs to the
     * claim. A failure never changes the last published commit, also when the branch was deleted.
     */
    @Transactional
    fun record(lease: SyncLease, attempt: SyncAttempt): Boolean {
        val previous =
            when (attempt) {
                is SyncAttempt.Failed -> {
                    val delay = properties.delayAfter(lease.consecutiveFailures + 1)
                    syncs.recordFailure(lease, attempt.code, attempt.seen, delay)
                }

                is SyncAttempt.Unchanged -> {
                    syncs.recordSuccess(lease, attempt.tip, properties.delayAfter(0))
                }

                is SyncAttempt.Publish -> {
                    syncs.recordSuccess(lease, attempt.tip, properties.delayAfter(0))
                }
            } ?: return false
        if (attempt is SyncAttempt.Publish) revisions.publish(lease.configSet, attempt.tip)
        val records = auditRecords(lease, attempt, previous)
        if (records.isNotEmpty()) {
            // The record update locked the row of the Git source, so a delete of the ConfigSet waits for this
            // transaction. Thus the ConfigSet still exists here.
            val configSet =
                checkNotNull(configSets.find(lease.configSet)) { "The ConfigSet of a locked Git source exists." }
            records.forEach { (operation, details) -> audit.record(Caller.System, configSet, operation, details) }
        }
        return true
    }

    /** The operations and details of the audit records of [attempt]. Most attempts change nothing and write none. */
    private fun auditRecords(
        lease: SyncLease,
        attempt: SyncAttempt,
        previous: PreviousAttempt,
    ): List<Pair<String, Map<String, Any?>>> {
        val records = mutableListOf<Pair<String, Map<String, Any?>>>()
        when (attempt) {
            is SyncAttempt.Failed -> {
                if (attempt.code != previous.failure) {
                    val details = mapOf("code" to attempt.code.name, "previousCode" to previous.failure?.name)
                    records += "SYNC_FAILED" to details
                }
            }

            is SyncAttempt.Unchanged, is SyncAttempt.Publish -> {
                previous.failure?.let { records += "SYNC_RECOVERED" to mapOf("previousCode" to it.name) }
            }
        }
        if (attempt is SyncAttempt.Publish) {
            val details = mapOf("commitId" to attempt.tip.name, "previousCommitId" to lease.lastPublished?.name)
            records += "SYNC_REVISION_PUBLISHED" to details
        }
        return records
    }
}
