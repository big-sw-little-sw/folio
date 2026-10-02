package io.github.big_sw_little_sw.folio.source.internal

import io.github.big_sw_little_sw.folio.credential.GitInstance
import io.github.big_sw_little_sw.folio.credential.GitInstances
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver
import org.apache.sshd.common.util.security.SecurityUtils
import org.springframework.stereotype.Component
import java.security.PublicKey

/**
 * The trusted host keys of every Git instance, parsed once at startup so that a bad line fails startup rather than
 * the first sync (ADR 0025). Messages name the instance and the line's position, never the key.
 */
@Component
class TrustedHostKeys(
    instances: GitInstances,
) {
    private val keys: Map<String, List<PublicKey>>

    init {
        check(SecurityUtils.isEDDSACurveSupported()) {
            "Apache sshd has no Ed25519 provider; Bouncy Castle must be on the classpath (ADR 0026)"
        }
        keys = instances.all().associate { it.name to parse(it) }
    }

    /** The trusted keys of [instance], which must be configured. */
    fun of(instance: GitInstance): List<PublicKey> =
        checkNotNull(keys[instance.name]) { "Git instance '${instance.name}' is not configured" }

    companion object {
        /** Each line is `<type> <base64>`, optionally followed by a comment, as in a `.pub` file. */
        fun parse(instance: GitInstance): List<PublicKey> =
            instance.hostKeys.mapIndexed { index, line ->
                // sshd's messages quote the line, so they are not passed on.
                runCatching {
                    PublicKeyEntry
                        .parsePublicKeyEntry(line)
                        ?.resolvePublicKey(null, emptyMap(), PublicKeyEntryResolver.FAILING)
                }.getOrNull() ?: throw IllegalArgumentException(
                    "folio.git.instances.${instance.name}.host-keys[$index] is not an OpenSSH public key line",
                )
            }
    }
}
