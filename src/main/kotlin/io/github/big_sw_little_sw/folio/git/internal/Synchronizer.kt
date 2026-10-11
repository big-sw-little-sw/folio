package io.github.big_sw_little_sw.folio.git.internal

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import org.eclipse.jgit.lib.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID

/** The result of the fetch and the publish step of one sync, before sync records it. */
sealed interface SyncAttempt {
    /** The tip is the last published commit, so sync publishes nothing. */
    data class Unchanged(val tip: ObjectId) : SyncAttempt

    /** The tip is new, and the publish step accepts it. [skipped] counts the file names that are not item paths. */
    data class Publish(val tip: ObjectId, val skipped: Int) : SyncAttempt

    /** The fetch or the publish step failed. [seen] is the tip if the fetch got it, or null if the fetch failed. */
    data class Failed(val code: FailureCode, val seen: ObjectId?) : SyncAttempt
}

/**
 * Sync of Git sources under leases (design 10.6). Each object of this class has its own lease owner, so it acts as one
 * Folio instance. One sync does these steps:
 *
 * 1. [claim] takes the leases of due ConfigSets in one statement.
 * 2. [sync] fetches the branch outside a transaction. If the tip changed, the publish step examines the tip: the root
 *    path must be a directory, and the file list must stay below its limit (design 9).
 * 3. [SyncRecorder] records the result, publishes the tip and writes the audit records in one transaction, but only if
 *    the lease still belongs to the claim.
 */
@Service
class Synchronizer(
    private val syncs: SyncRepository,
    private val fetcher: Fetcher,
    private val cache: RepositoryCache,
    private val recorder: SyncRecorder,
    gitProperties: GitProperties,
) {
    /** The lease owner of this object. The owner is random, so each start of the process has a new owner. */
    val owner = LeaseOwner(UUID.randomUUID())

    private val leaseLength: Duration = gitProperties.fetchDeadline + LEASE_MARGIN

    /** Claims a maximum of [limit] due ConfigSets, never one of [excluding]. */
    fun claim(limit: Int, excluding: Set<ConfigSetId> = emptySet()): List<SyncLease> =
        if (limit > 0) syncs.claim(owner, leaseLength, limit, excluding) else emptyList()

    /**
     * Syncs the ConfigSet of [lease] and records the result. Returns false if the lease no longer belongs to the claim,
     * or if the ConfigSet no longer exists. In both cases, sync records nothing. An unexpected error goes to the
     * caller, which releases the lease.
     *
     * The method runs outside a transaction, because a fetch can take minutes. In a transaction, the fetch would hold
     * a database connection for that time.
     */
    fun sync(lease: SyncLease): Boolean {
        val attempt = attempt(lease)
        val recorded = recorder.record(lease, attempt)
        if (!recorded) {
            log.info("The sync of ConfigSet {} lost its lease and recorded nothing", lease.configSet.toApiId())
        } else if (attempt is SyncAttempt.Publish && attempt.skipped > 0) {
            // The level is INFO, not WARN, because the line must not alert (design 9). The line never names the
            // files, because the repository controls the names.
            log.info(
                "ConfigSet {} has {} files that are not item paths",
                lease.configSet.toApiId(),
                attempt.skipped,
            )
        }
        return recorded
    }

    /**
     * Releases [lease] without a record of an attempt. The ConfigSet is due after [delay]. If the release fails, this
     * method only logs the failure. Thus the release never replaces an exception that the sync threw. The lease then
     * stays until its end time.
     */
    fun release(lease: SyncLease, delay: Duration) {
        try {
            syncs.release(lease, delay)
        } catch (e: DataAccessException) {
            log.error(
                "The release of the lease of ConfigSet {} failed: {}",
                lease.configSet.toApiId(),
                exceptionTypes(e),
            )
        }
    }

    private fun attempt(lease: SyncLease): SyncAttempt {
        val tip =
            try {
                fetcher.fetch(lease.configSet, lease.source)
            } catch (failure: GitFailure) {
                // The fetch step already logged the failure.
                return SyncAttempt.Failed(failure.code, null)
            }
        if (tip == lease.lastPublished) return SyncAttempt.Unchanged(tip)
        val started = System.nanoTime()
        return try {
            SyncAttempt.Publish(tip, publishable(lease, tip))
        } catch (failure: GitFailure) {
            logFailure(log, "publish", lease.configSet, failure, started)
            SyncAttempt.Failed(failure.code, tip)
        }
    }

    /**
     * Examines the new tip before the publish (design 9, 10.3). Throws [GitFailure] with `ROOT_PATH_NOT_FOUND` if the
     * root path is not a directory, or with `TOO_MANY_FILES` above the limit of the file list. Returns the number of
     * file names that are not item paths.
     */
    private fun publishable(lease: SyncLease, tip: ObjectId): Int =
        cache.reading(lease.configSet, { error("The cache lost the repository during the sync") }) { repository ->
            val listing =
                listFiles(repository, commitOf(repository, tip), lease.source.rootPath)
                    ?: throw GitFailure(FailureCode.ROOT_PATH_NOT_FOUND)
            listing.skipped
        }

    private companion object {
        val log = LoggerFactory.getLogger(Synchronizer::class.java)

        /**
         * The lease is the fetch deadline plus this margin. The deadline cannot stop SSH authentication, which sshd
         * limits to 2 minutes, or the open of a channel, which JGit limits to 30 seconds (design 10.7).
         */
        val LEASE_MARGIN: Duration = Duration.ofMinutes(3)
    }
}
