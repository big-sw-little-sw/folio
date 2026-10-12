package io.github.big_sw_little_sw.folio.credential.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.util.Base64

/**
 * The `folio.crypto` properties. This class only binds the values, and [MasterKeyRing] validates them. Spring Boot can
 * show a property value in the report of a failed binding. A failed conversion of a version key reports the previous
 * entry, which is a secret. Thus the versions bind as strings, and a map of strings cannot fail to bind.
 */
@ConfigurationProperties("folio.crypto")
data class CryptoProperties(val masterKeys: Map<String, String> = emptyMap(), val activeKeyVersion: Int? = null) {
    override fun toString() =
        "CryptoProperties(versions=${masterKeys.keys.sorted()}, activeKeyVersion=$activeKeyVersion)"
}

/**
 * The decoded master secrets. Folio does not start if the configuration is not valid. The failure messages name
 * versions and never secrets, because a startup failure goes to the log.
 */
@Component
class MasterKeyRing(properties: CryptoProperties) {
    private val secrets: Map<Int, ByteArray> =
        properties.masterKeys
            .also { check(it.isNotEmpty()) { "folio.crypto.master-keys is empty. Configure one or more versions." } }
            .entries
            .associate { (key, secret) ->
                val version = version(key)
                version to decode(version, secret)
            }

    val activeVersion: Int =
        checkNotNull(properties.activeKeyVersion) { "folio.crypto.active-key-version is missing." }.also {
            check(it in secrets) { "folio.crypto.active-key-version $it is not a version in folio.crypto.master-keys." }
        }

    val versions: Set<Int> get() = secrets.keys

    /** A copy of the secret of [version]. The caller sets the copy to zero after use. */
    fun secret(version: Int): ByteArray =
        checkNotNull(secrets[version]) { "Master-key version $version is not configured." }.copyOf()

    override fun toString() = "MasterKeyRing(versions=${versions.sorted()}, activeVersion=$activeVersion)"

    private companion object {
        const val MIN_SECRET_BYTES = 32

        /** Only the canonical form is a version. Thus `01` and `1` cannot name the same version two times. */
        fun version(key: String): Int = checkNotNull(key.toIntOrNull()?.takeIf { it > 0 && it.toString() == key }) {
            "folio.crypto.master-keys has the version '$key'. A version is a positive integer."
        }

        fun decode(version: Int, secret: String): ByteArray {
            // The decoder message can quote a character of the secret, so this message replaces it.
            val bytes =
                try {
                    Base64.getDecoder().decode(secret.trim())
                } catch (_: IllegalArgumentException) {
                    error("folio.crypto.master-keys.$version is not base64.")
                }
            check(bytes.size >= MIN_SECRET_BYTES) {
                "folio.crypto.master-keys.$version has fewer than $MIN_SECRET_BYTES bytes."
            }
            return bytes
        }
    }
}
