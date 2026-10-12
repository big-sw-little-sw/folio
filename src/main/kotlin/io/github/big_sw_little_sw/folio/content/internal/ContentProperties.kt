package io.github.big_sw_little_sw.folio.content.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.time.Duration

/** `folio.content`: the size limit and the cache time of content reads. `application.yaml` describes the settings. */
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
