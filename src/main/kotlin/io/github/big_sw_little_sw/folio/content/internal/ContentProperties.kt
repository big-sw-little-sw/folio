package io.github.big_sw_little_sw.folio.content.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.time.Duration

/**
 * The settings under `folio.content` (design 12.1):
 *
 * - [maxFileSize] is the largest ConfigItem that Folio serves. Folio refuses a larger ConfigItem with status 422,
 *   before it opens the ConfigItem. Folio streams files, so this limit protects bandwidth, not memory.
 * - [latestMaxAge] is how long caches can keep responses for `latest`, metadata and revision lists. It is also how long
 *   shared caches keep a public exact revision before they revalidate it. Zero makes caches revalidate each time.
 */
@ConfigurationProperties("folio.content")
data class ContentProperties(
    val maxFileSize: DataSize = DataSize.ofMegabytes(DEFAULT_MAX_FILE_SIZE_MEGABYTES),
    val latestMaxAge: Duration = Duration.ofSeconds(DEFAULT_LATEST_MAX_AGE_SECONDS),
) {
    init {
        require(maxFileSize.toBytes() > 0) { "folio.content.max-file-size must be positive" }
        require(!latestMaxAge.isNegative) { "folio.content.latest-max-age must not be negative" }
    }

    private companion object {
        const val DEFAULT_MAX_FILE_SIZE_MEGABYTES = 100L
        const val DEFAULT_LATEST_MAX_AGE_SECONDS = 30L
    }
}
