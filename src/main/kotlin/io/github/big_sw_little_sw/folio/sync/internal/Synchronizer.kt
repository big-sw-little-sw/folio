package io.github.big_sw_little_sw.folio.sync.internal

import io.github.big_sw_little_sw.folio.configset.ConfigSetId
import io.github.big_sw_little_sw.folio.configset.ConfigSetSources
import io.github.big_sw_little_sw.folio.source.SourceAccess
import io.github.big_sw_little_sw.folio.source.SourceAccessFailedException
import io.github.big_sw_little_sw.folio.source.SourceDefinition
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
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
 * Logs carry at most a ConfigSet ID, a failure code and a duration. Metrics count recorded attempts by outcome and
 * failure code and time every fetch (ADR 0039).
 */
@Service
class Synchronizer(
    private val states: SyncStateRepository,
    private val configSets: ConfigSetSources,
    private val sources: SourceAccess,
    private val recorder: SyncRecorder,
    private val properties: SyncProperties,
    private val meters: MeterRegistry,
) {
    /** The lease owner for this process; a new one on every start. */
    val instanceId: UUID = UUID.randomUUID()

    private val leaseDuration: Duration = leaseDuration(sources.fetchDeadline)

    private val fetches: Timer =
        Timer.builder("folio.sync.fetch.duration").description("Sync fetches, failed ones included").register(meters)

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
        val result = fetches.record<FetchResult> { fetch(lease, source) }
        val delay =
            when (result) {
                is FetchResult.Fetched -> delayAfter(0)
                is FetchResult.Failed -> delayAfter(lease.consecutiveFailures + 1)
            }
        val outcome = recorder.record(lease, result, delay)
        val millis = Duration.ofNanos(System.nanoTime() - started).toMillis()
        if (outcome == null) {
            // Deleted during the fetch, or the lease was lost; the two look the same here.
            log.info("Sync of ConfigSet {} was not recorded after {} ms", lease.configSetId.value, millis)
            return false
        }
        count(outcome, result)
        log.debug("Sync of ConfigSet {} took {} ms", lease.configSetId.value, millis)
        return true
    }

    private fun fetch(
        lease: SyncLease,
        source: SourceDefinition,
    ): FetchResult =
        try {
            FetchResult.Fetched(sources.fetch(lease.configSetId.value, source))
        } catch (e: SourceAccessFailedException) {
            FetchResult.Failed(e.failure)
        }

    private fun count(
        outcome: AttemptOutcome,
        result: FetchResult,
    ) {
        val code = (result as? FetchResult.Failed)?.failure?.name ?: NO_FAILURE
        meters.counter(ATTEMPTS, "outcome", outcome.tag, "code", code).increment()
    }

    private fun delayAfter(consecutiveFailures: Int) =
        nextSyncDelay(properties.interval, properties.maxBackoff, consecutiveFailures)

    private companion object {
        val log = LoggerFactory.getLogger(Synchronizer::class.java)
        const val ATTEMPTS = "folio.sync.attempts"
        const val NO_FAILURE = "none"
    }
}
