package io.github.big_sw_little_sw.folio.git.internal

import io.github.big_sw_little_sw.folio.catalog.ConfigSets
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Records the result of one sync in one transaction, so the sync state, the published revisions and the audit records
 * always agree. Sync writes an audit record only for a change: a new failure code, a recovery or a publish. Without
 * this rule, each interval would write a row.
 */
@Service
class SyncRecorder(
    private val syncRepository: SyncRepository,
    private val gitSourceRepository: GitSourceRepository,
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
                    syncRepository.record(lease, attempt.code, attempt.tip, delay)
                }

                is SyncAttempt.Unchanged, is SyncAttempt.Publish -> {
                    syncRepository.record(lease, null, attempt.tip, properties.delayAfter(0))
                }
            } ?: return false
        if (attempt is SyncAttempt.Publish) gitSourceRepository.publish(lease.configSet, attempt.tip)
        // The record update locked the row of the Git source, so a delete of the ConfigSet waits for this transaction.
        // Thus the ConfigSet still exists here. Most attempts write no audit record, so they do not read the ConfigSet.
        val configSet by lazy {
            checkNotNull(configSets.find(lease.configSet)) { "The ConfigSet of a locked Git source exists." }
        }
        val write = { operation: String, details: Map<String, Any?> ->
            audit.record(Caller.System, configSet, operation, details)
        }
        when (attempt) {
            is SyncAttempt.Failed -> {
                if (attempt.code != previous.failure) {
                    write("SYNC_FAILED", mapOf("code" to attempt.code.name, "previousCode" to previous.failure?.name))
                }
            }

            is SyncAttempt.Unchanged, is SyncAttempt.Publish -> {
                previous.failure?.let { write("SYNC_RECOVERED", mapOf("previousCode" to it.name)) }
            }
        }
        if (attempt is SyncAttempt.Publish) {
            val details = mapOf("commitId" to attempt.tip.name, "previousCommitId" to lease.lastPublished?.name)
            write("SYNC_REVISION_PUBLISHED", details)
        }
        return true
    }
}
