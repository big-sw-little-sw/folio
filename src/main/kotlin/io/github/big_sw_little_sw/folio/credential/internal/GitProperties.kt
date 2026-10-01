package io.github.big_sw_little_sw.folio.credential.internal

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `folio.git.instances`: the Git service instances credentials may belong to, by name (ADR 0016). Slice 5 adds
 * each instance's trusted host keys here.
 */
@ConfigurationProperties("folio.git")
data class GitProperties(
    val instances: Map<String, GitInstance> = emptyMap(),
) {
    init {
        instances.keys.forEach { name ->
            require(name.length <= MAX_NAME_LENGTH && NAME.matches(name)) {
                "folio.git.instances name '$name' must be lowercase letters, digits and single hyphens"
            }
        }
    }

    data class GitInstance(
        val host: String,
        val port: Int = DEFAULT_SSH_PORT,
    ) {
        init {
            require(host.isNotBlank()) { "folio.git.instances host must not be blank" }
            require(port in 1..MAX_PORT) { "folio.git.instances port $port is not a TCP port" }
        }
    }

    private companion object {
        /** Matches the `credential.git_instance` column. */
        const val MAX_NAME_LENGTH = 100
        const val DEFAULT_SSH_PORT = 22
        const val MAX_PORT = 65535
        val NAME = Regex("[a-z0-9]+(-[a-z0-9]+)*")
    }
}
