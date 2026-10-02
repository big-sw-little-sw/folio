package io.github.big_sw_little_sw.folio.source.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path

/**
 * `folio.git.cache-directory`: where the disposable bare-repository cache lives (ADR 0024). Required and absolute.
 * Shares the `folio.git` prefix with the instances that the credential module binds.
 */
@ConfigurationProperties("folio.git")
data class SourceProperties(
    val cacheDirectory: Path,
) {
    init {
        require(cacheDirectory.isAbsolute) { "folio.git.cache-directory must be an absolute path" }
    }
}
