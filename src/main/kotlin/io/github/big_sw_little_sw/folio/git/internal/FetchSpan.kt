package io.github.big_sw_little_sw.folio.git.internal

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.function.Supplier

/**
 * Runs [fetch] in the span of one Git fetch. The only attribute of the span is the ConfigSet ID, so the span has no
 * repository path, no branch and no server output. Each exception of [fetch], also a [GitFailure], marks the span as
 * an error. The exporters of the `web` module keep only the exception type. Thus the message of an unexpected
 * exception, for example an `IOException` with a cache path, never leaves Folio.
 */
fun <T> ObservationRegistry.fetchSpan(configSet: ConfigSetId, fetch: () -> T): T = Observation
    .createNotStarted(FETCH_OBSERVATION, this)
    .contextualName("git fetch")
    .highCardinalityKeyValue("folio.config_set.id", configSet.toApiId())
    .observe(Supplier(fetch))

/**
 * The fetch observation exists only for its span, but Micrometer also makes a timer and a long task timer for it.
 * Without this filter, Folio would have two more `folio.*` meters that repeat `folio.sync.fetch.duration`.
 */
@Configuration(proxyBeanMethods = false)
class FetchSpanConfiguration {
    @Bean
    fun fetchSpanMeters(): MeterFilter =
        MeterFilter.deny { it.name == FETCH_OBSERVATION || it.name == "$FETCH_OBSERVATION.active" }
}

private const val FETCH_OBSERVATION = "folio.git.fetch"
