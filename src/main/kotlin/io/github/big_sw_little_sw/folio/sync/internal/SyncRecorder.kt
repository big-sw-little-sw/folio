package io.github.big_sw_little_sw.folio.sync.internal

import io.github.big_sw_little_sw.folio.configset.ConfigSetSources
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration

/**
 * Records sync attempts. The outcome's event is published in the recording's transaction, so its audit record commits
 * with it or not at all (ADR 0038).
 */
@Service
class SyncRecorder(
    private val states: SyncStateRepository,
    private val configSets: ConfigSetSources,
    private val events: ApplicationEventPublisher,
) {
    /**
     * Records [result] and releases [lease], with the ConfigSet due after [delay]. Returns the outcome, or null if
     * nothing was recorded because [lease] is no longer held.
     */
    @Transactional
    fun record(
        lease: SyncLease,
        result: FetchResult,
        delay: Duration,
    ): AttemptOutcome? {
        val id = lease.configSetId
        val previous =
            when (result) {
                is FetchResult.Fetched -> states.recordSuccess(lease, result.revision, delay)
                is FetchResult.Failed -> states.recordFailure(lease, result.failure, delay)
            } ?: return null
        // The recording locked the state row, so a delete of the ConfigSet, which cascades to it, waits until commit.
        outcomeEvent(id, previous, result) { checkNotNull(configSets.path(id)) }?.let(events::publishEvent)
        return attemptOutcome(previous, result)
    }
}
