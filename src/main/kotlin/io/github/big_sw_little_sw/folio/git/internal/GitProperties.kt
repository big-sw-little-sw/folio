package io.github.big_sw_little_sw.folio.git.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.nio.file.Path
import java.time.Duration

/**
 * `folio.git`: the cache directory, the fetch limits and the Git instances. `application.yaml` describes the settings.
 * The binder takes the [instances] as plain text, and [GitInstances] validates them. Thus no binder error message
 * shows a host key.
 */
@ConfigurationProperties("folio.git")
data class GitProperties(
    val cacheDirectory: Path,
    val fetchDeadline: Duration = Duration.ofMinutes(DEFAULT_FETCH_DEADLINE_MINUTES),
    val maxRepositorySize: DataSize = DataSize.ofGigabytes(1),
    val instances: Map<String, Instance> = emptyMap(),
) {
    init {
        require(cacheDirectory.isAbsolute) { "folio.git.cache-directory must be an absolute path" }
        require(fetchDeadline.isPositive) { "folio.git.fetch-deadline must be positive" }
        require(maxRepositorySize.toBytes() > 0) { "folio.git.max-repository-size must be positive" }
    }

    data class Instance(
        val host: String = "",
        val port: Int = DEFAULT_PORT,
        val user: String = DEFAULT_USER,
        val hostKeys: List<String> = emptyList(),
    )

    private companion object {
        const val DEFAULT_FETCH_DEADLINE_MINUTES = 5L
        const val DEFAULT_PORT = 22
        const val DEFAULT_USER = "git"
    }
}
