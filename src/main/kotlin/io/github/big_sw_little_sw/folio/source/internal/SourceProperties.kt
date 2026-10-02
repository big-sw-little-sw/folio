package io.github.big_sw_little_sw.folio.source.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.nio.file.Path
import java.time.Duration

/**
 * `folio.git.cache-directory`: where the disposable bare-repository cache lives (ADR 0024). Required and absolute.
 * `folio.git.fetch-deadline`: how long one fetch, including its `ls-remote`, may take (ADR 0033).
 * `folio.git.max-repository-size`: the most a ConfigSet's cached repository may hold on disk after a fetch (ADR 0033).
 * Shares the `folio.git` prefix with the instances that the credential module binds.
 */
@ConfigurationProperties("folio.git")
data class SourceProperties(
    val cacheDirectory: Path,
    val fetchDeadline: Duration = Duration.ofMinutes(DEFAULT_FETCH_DEADLINE_MINUTES),
    val maxRepositorySize: DataSize = DataSize.ofGigabytes(1),
) {
    init {
        require(cacheDirectory.isAbsolute) { "folio.git.cache-directory must be an absolute path" }
        require(fetchDeadline.isPositive) { "folio.git.fetch-deadline must be positive" }
        require(maxRepositorySize.toBytes() > 0) { "folio.git.max-repository-size must be positive" }
    }

    private companion object {
        const val DEFAULT_FETCH_DEADLINE_MINUTES = 5L
    }
}
