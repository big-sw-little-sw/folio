package io.github.big_sw_little_sw.testing

import io.micrometer.core.instrument.MeterRegistry

/**
 * Measures the increase of a counter after the creation of this object. Test classes share the registry of a cached
 * context, so a test must compare the counts before and after. A counter that does not exist yet counts as zero.
 */
class CounterGrowth(private val meters: MeterRegistry, private val name: String, private vararg val tags: String) {
    private val before = count()

    fun added(): Double = count() - before

    private fun count(): Double = meters
        .find(name)
        .tags(*tags)
        .counter()
        ?.count() ?: 0.0
}

/**
 * The attempts of sync for all tag values, and the fetches of sync that the timer measured. A test compares the
 * counts before and after a step to see what the step added.
 */
data class SyncCounts(val attempts: Double, val fetches: Long)

fun MeterRegistry.syncCounts() = SyncCounts(
    find("folio.sync.attempts").counters().sumOf { it.count() },
    get("folio.sync.fetch.duration").timer().count(),
)
