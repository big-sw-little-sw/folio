package io.github.big_sw_little_sw.folio.git.internal

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.function.Supplier

/**
 * Runs [fetch] in the span of one Git fetch (design 15.1). The only attribute of the span is the ConfigSet ID. The span
 * has no repository path, no branch and no server output. Each exception of [fetch], also a [GitFailure], marks the
 * span as an error. The span records the exception, but the exporters of the `web` module keep only the exception
 * type. Thus an unexpected exception, for example an `IOException` of the cache, exports no message.
 */
fun <T> ObservationRegistry.fetchSpan(configSet: ConfigSetId, fetch: () -> T): T = Observation
    .createNotStarted(FETCH_OBSERVATION, this)
    .contextualName("git fetch")
    .highCardinalityKeyValue("folio.config_set.id", configSet.toApiId())
    .observe(Supplier(fetch))

/**
 * Micrometer makes a timer and a long task timer for each observation. The fetch observation exists only for its span.
 * Without this filter, the timers of the fetch observation would repeat `folio.sync.fetch.duration`. They would also
 * add meters that design 15.1 does not name.
 */
@Configuration(proxyBeanMethods = false)
class FetchSpanConfiguration {
    @Bean
    fun fetchSpanMeters(): MeterFilter =
        MeterFilter.deny { it.name == FETCH_OBSERVATION || it.name == "$FETCH_OBSERVATION.active" }
}

private const val FETCH_OBSERVATION = "folio.git.fetch"
