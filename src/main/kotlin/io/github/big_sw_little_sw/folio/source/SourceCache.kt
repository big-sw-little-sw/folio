package io.github.big_sw_little_sw.folio.source

import io.github.big_sw_little_sw.folio.source.internal.RepositoryCache
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.BaseUnits
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Removal of cached repositories on this instance, for ConfigSets that no longer exist (ADR 0034), and the gauges of
 * how much the cache holds (ADR 0039). The cache is disposable, so removing a repository costs at most a full fetch.
 */
@Service
class SourceCache(
    private val cache: RepositoryCache,
    meters: MeterRegistry,
) {
    // Walking the cache can take a while, so scrapes read the last measurement instead; zero until the first.
    private val bytes = AtomicLong()
    private val repositories = AtomicLong()

    init {
        Gauge
            .builder("folio.source.cache.size", bytes) { it.get().toDouble() }
            .baseUnit(BaseUnits.BYTES)
            .description("Bytes the cached repositories on this instance held when last measured")
            .register(meters)
        Gauge
            .builder("folio.source.cache.repositories", repositories) { it.get().toDouble() }
            .description("Cached repositories on this instance when last measured")
            .register(meters)
    }

    /** The IDs of the ConfigSets that have a cached repository on this instance. */
    fun configSetIds(): Set<UUID> = cache.ids()

    /** Deletes the cached repository of [configSetId], if there is one. */
    fun delete(configSetId: UUID) {
        cache.delete(configSetId)
    }

    /**
     * Measures the cached repositories for the gauges. A repository removed during the walk counts what was left of
     * it; one added during the walk may be missed until the next measurement.
     */
    fun measure() {
        val ids = cache.ids()
        bytes.set(ids.sumOf { cache.size(it) })
        repositories.set(ids.size.toLong())
    }
}
