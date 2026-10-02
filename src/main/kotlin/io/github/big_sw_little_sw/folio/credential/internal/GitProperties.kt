package io.github.big_sw_little_sw.folio.credential.internal

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `folio.git.instances`: the Git service instances credentials may belong to, by name (ADR 0016), with the SSH
 * user and trusted host keys of each (ADR 0025). Other modules read them through `GitInstances`.
 */
@ConfigurationProperties("folio.git")
data class GitProperties(
    val instances: Map<String, Instance> = emptyMap(),
) {
    init {
        instances.forEach { (name, instance) ->
            require(name.length <= MAX_NAME_LENGTH && NAME.matches(name)) {
                "folio.git.instances name '$name' must be lowercase letters, digits and single hyphens"
            }
            require(instance.hostKeys.isNotEmpty()) {
                "folio.git.instances.$name.host-keys must list at least one trusted host key"
            }
        }
    }

    /** [hostKeys] are OpenSSH public key lines (`ssh-ed25519 AAAA…`); the source module parses them at startup. */
    data class Instance(
        val host: String,
        val port: Int = DEFAULT_SSH_PORT,
        val user: String = DEFAULT_USER,
        val hostKeys: List<String> = emptyList(),
    ) {
        init {
            require(HOST.matches(host)) { "folio.git.instances host '$host' is not a host name or IP address" }
            require(port in 1..MAX_PORT) { "folio.git.instances port $port is not a TCP port" }
            require(USER.matches(user)) { "folio.git.instances user '$user' is not a valid SSH user name" }
        }
    }

    private companion object {
        /** Matches the `credential.git_instance` column. */
        const val MAX_NAME_LENGTH = 100
        const val DEFAULT_SSH_PORT = 22
        const val DEFAULT_USER = "git"
        const val MAX_PORT = 65535
        val NAME = Regex("[a-z0-9]+(-[a-z0-9]+)*")

        // Host and user go into the SSH URL, so neither may contain `@` or `/`; a host with `:` is an IPv6 address.
        val HOST = Regex("[A-Za-z0-9.-]+|[0-9A-Fa-f:.]+")
        val USER = Regex("[A-Za-z0-9._-]{1,100}")
    }
}
