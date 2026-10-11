package io.github.big_sw_little_sw.folio.git.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.nio.file.Path
import java.time.Duration

/**
 * The settings under `folio.git` (design 10):
 *
 * - [cacheDirectory] contains one bare repository for each ConfigSet and the empty SSH home directory (design 10.4,
 *   10.5). The setting is mandatory and must be an absolute path. Each Folio instance needs its own directory.
 * - [fetchDeadline] limits each fetch by the wall clock, from before the `ls-remote` to the end of the transfer
 *   (design 10.7).
 * - [maxRepositorySize] is the largest size on disk of a cached repository after a fetch (design 10.7).
 * - [instances] gives the Git instances by name (design 10.1). The binder takes them as plain text. [GitInstances]
 *   validates them and parses the host keys at startup, so no error message shows a property value.
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

    /** [hostKeys] are OpenSSH public key lines, for example `ssh-ed25519 AAAA…`. */
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
