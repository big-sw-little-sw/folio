package io.github.big_sw_little_sw.folio.git.internal

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.eclipse.jgit.lib.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID

/** The result of the fetch and the publish step of one sync, before sync records it. */
sealed interface SyncAttempt {
    /** The tip that the fetch got, or null if the fetch failed. */
    val tip: ObjectId?

    /** The tip is the last published commit, so sync publishes nothing. */
    data class Unchanged(override val tip: ObjectId) : SyncAttempt

    /** The tip is new, and the publish step accepts it. [skipped] counts the file names that are not item paths. */
    data class Publish(override val tip: ObjectId, val skipped: Int) : SyncAttempt

    /** The fetch or the publish step failed. */
    data class Failed(val code: FailureCode, override val tip: ObjectId?) : SyncAttempt
}

/**
 * Syncs Git sources under leases. Each object of this class has its own lease owner, so it acts as one Folio instance.
 *
 * The meter `folio.sync.attempts` counts each recorded attempt. An attempt that lost its lease records nothing, so the
 * meter does not count it. The timer `folio.sync.fetch.duration` measures each fetch of sync, also a failed fetch.
 */
@Service
class Synchronizer(
    private val syncRepository: SyncRepository,
    private val fetcher: Fetcher,
    private val cache: RepositoryCache,
    private val recorder: SyncRecorder,
    gitProperties: GitProperties,
    private val meters: MeterRegistry,
) {
    /** The lease owner of this object. The owner is random, so each start of the process has a new owner. */
    val owner = LeaseOwner(UUID.randomUUID())

    private val leaseLength: Duration = gitProperties.fetchDeadline + LEASE_MARGIN

    private val fetchDuration = Timer.builder("folio.sync.fetch.duration").register(meters)

    /** Claims a maximum of [limit] due ConfigSets, never one of [excluding]. */
    fun claim(limit: Int, excluding: Set<ConfigSetId> = emptySet()): List<SyncLease> =
        if (limit > 0) syncRepository.claim(owner, leaseLength, limit, excluding) else emptyList()

    /**
     * Syncs the ConfigSet of [lease] and records the result. Returns false if the lease no longer belongs to the claim,
     * or if the ConfigSet no longer exists. In both cases, sync records nothing. An unexpected error goes to the
     * caller, which releases the lease.
     *
     * The method runs outside a transaction, as [Fetcher.fetch] requires.
     */
    fun sync(lease: SyncLease): Boolean {
        val attempt = attempt(lease)
        if (!recorder.record(lease, attempt)) {
            log.info("The sync of ConfigSet {} lost its lease and recorded nothing", lease.configSet.toApiId())
            return false
        }
        count(attempt)
        if (attempt is SyncAttempt.Publish && attempt.skipped > 0) {
            // The level is INFO, not WARN, because the revision still publishes and no operator must act. The line
            // never names the files, because the repository controls the names.
            log.info(
                "ConfigSet {} has {} files that are not item paths",
                lease.configSet.toApiId(),
                attempt.skipped,
            )
        }
        return true
    }

    /**
     * Releases [lease] without a record of an attempt. The ConfigSet is due after [delay]. If the release fails, this
     * method only logs the failure. Thus the release never replaces an exception that the sync threw. The lease then
     * stays until its end time.
     */
    fun release(lease: SyncLease, delay: Duration) {
        try {
            syncRepository.release(lease, delay)
        } catch (e: DataAccessException) {
            log.error(
                "The release of the lease of ConfigSet {} failed: {}",
                lease.configSet.toApiId(),
                exceptionTypes(e),
            )
        }
    }

    /**
     * A success gets the code tag `none`, so each series of the meter has the same tag keys. Prometheus requires the
     * same tag keys for all series of one meter.
     */
    private fun count(attempt: SyncAttempt) {
        val (outcome, code) =
            when (attempt) {
                is SyncAttempt.Unchanged -> "unchanged" to NO_CODE
                is SyncAttempt.Publish -> "published" to NO_CODE
                is SyncAttempt.Failed -> "failed" to attempt.code.name
            }
        meters.counter(ATTEMPTS, "outcome", outcome, "code", code).increment()
    }

    private fun attempt(lease: SyncLease): SyncAttempt {
        val fetch = Timer.start()
        val tip =
            try {
                fetcher.fetch(lease.configSet, lease.source)
            } catch (failure: GitFailure) {
                // The fetch step already logged the failure.
                return SyncAttempt.Failed(failure.code, null)
            } finally {
                fetch.stop(fetchDuration)
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
     * The publish step. Each published revision must be readable, so the root path must be a directory at the tip, and
     * the file list must stay within its limit. Otherwise the step throws [GitFailure] with `ROOT_PATH_NOT_FOUND` or
     * `TOO_MANY_FILES`. Returns the number of file names that are not item paths.
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

        const val ATTEMPTS = "folio.sync.attempts"
        const val NO_CODE = "none"

        /**
         * The lease is the fetch deadline plus this margin. The deadline cannot stop SSH authentication, which sshd
         * limits to 2 minutes, or the open of a channel, which JGit limits to 30 seconds. Without the margin, a lease
         * could end during its fetch, and another instance could sync the same ConfigSet.
         */
        val LEASE_MARGIN: Duration = Duration.ofMinutes(3)
    }
}
