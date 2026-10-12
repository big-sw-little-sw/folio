package io.github.big_sw_little_sw.folio.git.internal

import io.github.big_sw_little_sw.folio.catalog.Slug
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver
import org.apache.sshd.common.util.security.SecurityUtils
import org.springframework.stereotype.Component
import java.security.PublicKey

/**
 * A Git server that Folio can connect to. [user] is the SSH login name on the server. The [hostKeys] let Folio
 * authenticate the server, and the key of the credential lets the server authenticate Folio.
 */
class GitInstance(val name: Slug, val host: String, val port: Int, val user: String, val hostKeys: List<PublicKey>) {
    /** The SSH URL of [repository], for example `ssh://git@github.com:22/org/repo.git`. It contains no secret. */
    fun url(repository: RepositoryPath): String {
        val address = if (':' in host) "[$host]" else host
        return "ssh://$user@$address:$port/$repository"
    }
}

/**
 * The Git instances of `folio.git.instances`. This bean validates them and parses each host key at startup. Thus a
 * line that is not valid stops the start of Folio. Without the validation at startup, only the first fetch finds a
 * line that is not valid. A message names the property, for example `folio.git.instances.github.host-keys[0]`, and
 * never shows a key.
 *
 * The bean also makes sure that Apache sshd can sign with Ed25519 keys. sshd needs Bouncy Castle for Ed25519, and
 * Bouncy Castle is only a runtime dependency. Without this startup check, a build without Bouncy Castle starts, and
 * each fetch fails.
 */
@Component
class GitInstances(properties: GitProperties) {
    private val instances: Map<Slug, GitInstance>

    init {
        check(SecurityUtils.isEDDSACurveSupported()) {
            "Apache sshd has no Ed25519 support. Bouncy Castle must be on the runtime classpath."
        }
        instances = properties.instances.map { (name, instance) -> parse(name, instance) }.associateBy { it.name }
    }

    /** Returns null if the configuration has no instance with [name], for example after a deployment removed it. */
    fun find(name: Slug): GitInstance? = instances[name]

    private companion object {
        const val MAX_PORT = 65535

        // The host and the user go into the SSH URL, so neither can contain '@', '/' or a space. A host with ':' is an
        // IPv6 address.
        val HOST = Regex("[A-Za-z0-9.-]+|[0-9A-Fa-f:.]+")
        val USER = Regex("[A-Za-z0-9._-]{1,100}")

        fun parse(name: String, instance: GitProperties.Instance): GitInstance {
            val property = "folio.git.instances.$name"
            require(Slug.isValid(name)) { "$property: an instance name follows the slug rules" }
            require(HOST.matches(instance.host)) { "$property.host must be a host name or an IP address" }
            require(instance.port in 1..MAX_PORT) { "$property.port must be a TCP port" }
            require(USER.matches(instance.user)) { "$property.user must be an SSH user name" }
            require(instance.hostKeys.isNotEmpty()) { "$property.host-keys must have one or more lines" }
            val keys = instance.hostKeys.mapIndexed { index, line -> hostKey(line, "$property.host-keys[$index]") }
            return GitInstance(Slug(name), instance.host, instance.port, instance.user, keys)
        }

        /** A line is `<type> <base64>` with an optional comment, as in a `.pub` file. */
        fun hostKey(line: String, property: String): PublicKey {
            // The exception messages of sshd quote the line, so this function does not include them in its own message.
            val key =
                runCatching {
                    PublicKeyEntry
                        .parsePublicKeyEntry(line)
                        ?.resolvePublicKey(null, emptyMap(), PublicKeyEntryResolver.FAILING)
                }.getOrNull()
            return requireNotNull(key) { "$property is not an OpenSSH public key line" }
        }
    }
}
