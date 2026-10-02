package io.github.big_sw_little_sw.folio.sync.internal

import io.github.big_sw_little_sw.folio.configset.ConfigSetDeleted
import io.github.big_sw_little_sw.folio.source.SourceCache
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Removes the cached repositories of deleted ConfigSets on this instance (ADR 0034): at once on the instance that
 * deleted the ConfigSet, and by a periodic sweep on every instance.
 */
@Component
class CacheCleanup(
    private val cache: SourceCache,
    private val states: SyncStateRepository,
) {
    /** After commit, so that a delete that rolls back keeps its cache. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onConfigSetDeleted(event: ConfigSetDeleted) {
        cache.delete(event.id.value)
    }

    /**
     * Removes the cached repositories of ConfigSets that no longer exist, such as ones deleted on other instances,
     * then measures the cache for its gauges (ADR 0039).
     */
    fun sweep() {
        // Listed before the query: a listed repository whose ConfigSet still exists is then always found.
        val cached = cache.configSetIds()
        if (cached.isNotEmpty()) {
            (cached - states.existing(cached)).forEach {
                cache.delete(it)
                log.info("Removed the cached repository of deleted ConfigSet {}", it)
            }
        }
        cache.measure()
    }

    private companion object {
        val log = LoggerFactory.getLogger(CacheCleanup::class.java)
    }
}
