package io.github.big_sw_little_sw.folio.consumption.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `folio.consumption.latest-max-age`: how long caches may keep responses about `latest`, which moves with every sync
 * (ADR 0035). Zero makes them revalidate every time.
 */
@ConfigurationProperties("folio.consumption")
data class ConsumptionProperties(
    val latestMaxAge: Duration = Duration.ofSeconds(DEFAULT_LATEST_MAX_AGE_SECONDS),
) {
    init {
        require(!latestMaxAge.isNegative) { "folio.consumption.latest-max-age must not be negative" }
    }

    private companion object {
        const val DEFAULT_LATEST_MAX_AGE_SECONDS = 30L
    }
}
