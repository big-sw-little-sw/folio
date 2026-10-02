package io.github.big_sw_little_sw.folio.credential

import io.github.big_sw_little_sw.folio.credential.internal.GitProperties
import org.springframework.stereotype.Component

/**
 * A Git service instance from `folio.git.instances` (ADR 0016). [hostKeys] are the trusted host keys as OpenSSH
 * public key lines; the source module parses and enforces them (ADR 0025).
 */
data class GitInstance(
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
    val hostKeys: List<String>,
)

/** The configured Git service instances. */
@Component
class GitInstances(
    properties: GitProperties,
) {
    private val instances =
        properties.instances.mapValues { (name, it) -> GitInstance(name, it.host, it.port, it.user, it.hostKeys) }

    fun find(name: String): GitInstance? = instances[name]

    fun all(): Collection<GitInstance> = instances.values
}
