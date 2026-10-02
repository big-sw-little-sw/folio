package io.github.big_sw_little_sw.folio.sync.internal

import io.github.big_sw_little_sw.folio.configset.ConfigSetId
import io.github.big_sw_little_sw.folio.configset.ConfigSetSources
import io.github.big_sw_little_sw.folio.source.SourceAccess
import io.github.big_sw_little_sw.folio.source.SourceAccessFailedException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID

/**
 * Syncs ConfigSets under leases (ADR 0032): claim due ConfigSets in a short transaction, fetch each outside any
 * transaction into this instance's cache, then record the outcome only if the lease is still held. A failure is
 * recorded as its source failure code and never stops other ConfigSets.
 *
 * Logs carry at most a ConfigSet ID, a failure code and a duration.
 */
@Service
class Synchronizer(
    private val states: SyncStateRepository,
    private val configSets: ConfigSetSources,
    private val sources: SourceAccess,
    private val properties: SyncProperties,
) {
    /** The lease owner for this process; a new one on every start. */
    val instanceId: UUID = UUID.randomUUID()

    private val leaseDuration: Duration = leaseDuration(sources.fetchDeadline)

    /** Leases up to [limit] due ConfigSets to this instance, most overdue first, never one of [excluding]. */
    @Transactional
    fun claimDue(
        limit: Int,
        excluding: Set<ConfigSetId> = emptySet(),
    ): List<SyncLease> = if (limit > 0) states.claimDue(instanceId, leaseDuration, limit, excluding) else emptyList()

    /** Releases [lease] without recording an attempt; the ConfigSet is due after [delay]. */
    fun release(
        lease: SyncLease,
        delay: Duration,
    ) {
        states.release(lease, delay)
    }

    /**
     * Fetches the leased ConfigSet's branch and records the outcome. Returns false if nothing was recorded: the
     * ConfigSet was deleted, or the lease expired and another claim took it over.
     *
     * Deliberately not transactional: the fetch talks to the Git service, which must not hold a database connection.
     */
    fun sync(lease: SyncLease): Boolean {
        val started = System.nanoTime()
        // A ConfigSet deleted since the claim took its state with it.
        val source = configSets.find(lease.configSetId) ?: return false
        val recorded =
            try {
                val revision = sources.fetch(lease.configSetId.value, source)
                states.recordSuccess(lease, revision, delayAfter(0))
            } catch (e: SourceAccessFailedException) {
                states.recordFailure(lease, e.failure, delayAfter(lease.consecutiveFailures + 1))
            }
        val millis = Duration.ofNanos(System.nanoTime() - started).toMillis()
        if (recorded) {
            log.debug("Sync of ConfigSet {} took {} ms", lease.configSetId.value, millis)
        } else {
            // Deleted during the fetch, or the lease was lost; the two look the same here.
            log.info("Sync of ConfigSet {} was not recorded after {} ms", lease.configSetId.value, millis)
        }
        return recorded
    }

    private fun delayAfter(consecutiveFailures: Int) =
        nextSyncDelay(properties.interval, properties.maxBackoff, consecutiveFailures)

    private companion object {
        val log = LoggerFactory.getLogger(Synchronizer::class.java)
    }
}
