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

fun MeterRegistry.tagKeys(name: String): Set<String> = find(name)
    .meters()
    .flatMap { it.id.tags }
    .map { it.key }
    .toSet()

/** Returns the sum of all counters with [name], for all tag values. */
fun MeterRegistry.total(name: String): Double = find(name).counters().sumOf { it.count() }
