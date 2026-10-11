package io.github.big_sw_little_sw.folio.credential.internal

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.util.Base64

/**
 * `folio.crypto`: the master-key ring (design 11.3). [masterKeys] maps a positive integer version to a base64 secret
 * of 32 bytes or more. [activeKeyVersion] names the version for new encryptions.
 *
 * This class only binds the values. [MasterKeyRing] parses and validates them. Spring Boot can show the value of a
 * property in the report of a failed binding. A failed conversion of a version key reports the previous entry, which
 * is a secret. Thus the versions bind as strings, and a map of strings cannot fail to bind.
 */
@ConfigurationProperties("folio.crypto")
data class CryptoProperties(val masterKeys: Map<String, String> = emptyMap(), val activeKeyVersion: Int? = null) {
    override fun toString() =
        "CryptoProperties(versions=${masterKeys.keys.sorted()}, activeKeyVersion=$activeKeyVersion)"
}

/**
 * The decoded master secrets. Folio does not start if the ring is empty, if a version is not a positive integer, if the
 * active version is missing or if a secret is not valid. The messages name versions and never secrets.
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
            // The message of the decoder can quote a character of the secret, so this message replaces it.
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

/**
 * Stops the startup if a stored key uses a master-key version that the ring does not have (design 11.3). Without the
 * check, Folio would fail later, at the first sync with that key. The check runs after all singletons exist, so
 * Flyway has already migrated the schema. The web server starts after the check, so no request comes before it.
 */
@Component
class MasterKeyCheck(private val keys: KeyRepository, private val ring: MasterKeyRing) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        val missing = keys.countByVersion().keys - ring.versions
        check(missing.isEmpty()) {
            "Stored keys use the master-key versions ${missing.sorted()}, but folio.crypto.master-keys does not have " +
                "them. Add these versions again, run the re-encryption, and then remove them."
        }
    }
}
