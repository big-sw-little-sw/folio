package io.github.big_sw_little_sw.folio.credential.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.util.Base64

/**
 * The master-key ring (ADR 0019): `folio.crypto.master-keys` maps a positive version number to a base64 secret
 * of at least 32 bytes, and `folio.crypto.active-key-version` names the version new encryptions use.
 * Validation messages name versions, never secrets. Not a data class: its `toString` must not print secrets.
 */
@ConfigurationProperties("folio.crypto")
class CryptoProperties(
    masterKeys: Map<Int, String> = emptyMap(),
    val activeKeyVersion: Int = 0,
) {
    private val secrets: Map<Int, ByteArray> = masterKeys.mapValues { (version, secret) -> decode(version, secret) }

    init {
        require(secrets.isNotEmpty()) { "folio.crypto.master-keys must configure at least one master-key version" }
        require(activeKeyVersion in secrets) {
            "folio.crypto.active-key-version $activeKeyVersion is not configured in folio.crypto.master-keys"
        }
    }

    val versions: Set<Int> get() = secrets.keys

    /** A copy of the secret of [version], which the caller zeroes after use. */
    fun secret(version: Int): ByteArray =
        checkNotNull(secrets[version]) { "Master-key version $version is not configured" }.copyOf()

    override fun toString() = "CryptoProperties(versions=$versions, activeKeyVersion=$activeKeyVersion)"

    private companion object {
        const val MIN_SECRET_BYTES = 32

        fun decode(
            version: Int,
            secret: String,
        ): ByteArray {
            require(version > 0) { "folio.crypto.master-keys versions must be positive, not $version" }
            // The decoder's own message can quote a character of the secret, so it is not passed on.
            val bytes =
                runCatching { Base64.getDecoder().decode(secret.trim()) }
                    .getOrElse { throw IllegalArgumentException("folio.crypto.master-keys.$version is not base64") }
            require(bytes.size >= MIN_SECRET_BYTES) {
                "folio.crypto.master-keys.$version must decode to at least $MIN_SECRET_BYTES bytes"
            }
            return bytes
        }
    }
}
