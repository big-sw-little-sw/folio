package io.github.big_sw_little_sw.folio.sync

import io.github.big_sw_little_sw.folio.configset.ConfigSetCreated
import io.github.big_sw_little_sw.folio.configset.ConfigSetId
import io.github.big_sw_little_sw.folio.configset.ConfigSetNotFoundException
import io.github.big_sw_little_sw.folio.configset.ConfigSetService
import io.github.big_sw_little_sw.folio.policy.Action
import io.github.big_sw_little_sw.folio.sync.internal.SyncStateRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Sync state and manual sync requests for callers (ADR 0032). The poller does the syncing; nothing here contacts the
 * Git service.
 */
@Service
class SyncService(
    private val configSets: ConfigSetService,
    private val states: SyncStateRepository,
    private val events: ApplicationEventPublisher,
) {
    /**
     * Makes the ConfigSet due now, so that the next poll of any instance syncs it, and returns its state. A sync
     * already running finishes first and the ConfigSet syncs again after it. Requires sync on the ConfigSet. Publishes
     * [SyncRequested].
     */
    @Transactional
    fun request(id: ConfigSetId): SyncState {
        val configSet = configSets.requireAllowed(id, Action.CONFIG_SET_SYNC)
        val state = states.markDue(id) ?: throw ConfigSetNotFoundException(id)
        events.publishEvent(SyncRequested(id, configSets.pathOf(configSet)))
        return state
    }

    /** Requires view on the ConfigSet. */
    @Transactional(readOnly = true)
    fun state(id: ConfigSetId): SyncState {
        configSets.requireAllowed(id, Action.CONFIG_SET_VIEW)
        return states.find(id) ?: throw ConfigSetNotFoundException(id)
    }

    /** Every ConfigSet has a sync state from its creation, due at once (ADR 0034). */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun onConfigSetCreated(event: ConfigSetCreated) {
        states.insert(event.id)
    }
}
