package io.github.big_sw_little_sw.folio.consumption.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.time.Duration

/**
 * `folio.consumption` (ADR 0035, ADR 0036):
 * - [latestMaxAge]: how long caches may keep responses about `latest`, which moves with every sync, and how long
 *   shared caches may keep exact revisions before they revalidate. Zero makes them revalidate every time.
 * - [maxConcurrentFetches]: on-demand fetches this instance runs at once; reads beyond it get 503 rather than wait.
 * - [maxFileSize]: the largest file a read serves.
 */
@ConfigurationProperties("folio.consumption")
data class ConsumptionProperties(
    val latestMaxAge: Duration = Duration.ofSeconds(DEFAULT_LATEST_MAX_AGE_SECONDS),
    val maxConcurrentFetches: Int = DEFAULT_MAX_CONCURRENT_FETCHES,
    val maxFileSize: DataSize = DataSize.ofMegabytes(DEFAULT_MAX_FILE_SIZE_MEGABYTES),
) {
    init {
        require(!latestMaxAge.isNegative) { "folio.consumption.latest-max-age must not be negative" }
        require(maxConcurrentFetches >= 1) { "folio.consumption.max-concurrent-fetches must be at least 1" }
        // Files are loaded into a byte array, so the limit must fit one.
        require(maxFileSize.toBytes() in 1..Int.MAX_VALUE) {
            "folio.consumption.max-file-size must be positive and at most ${Int.MAX_VALUE} bytes"
        }
    }

    /** [maxFileSize] in bytes. */
    val maxFileBytes: Int get() = maxFileSize.toBytes().toInt()

    private companion object {
        const val DEFAULT_LATEST_MAX_AGE_SECONDS = 30L
        const val DEFAULT_MAX_CONCURRENT_FETCHES = 2
        const val DEFAULT_MAX_FILE_SIZE_MEGABYTES = 10L
    }
}
