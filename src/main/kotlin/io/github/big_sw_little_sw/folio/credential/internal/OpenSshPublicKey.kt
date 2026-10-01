package io.github.big_sw_little_sw.folio.credential.internal

import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * An Ed25519 public key in OpenSSH form. [text] is `ssh-ed25519 <base64 blob>` without a comment, and
 * [fingerprint] is `SHA256:<unpadded base64 of SHA-256(blob)>`, as `ssh-keygen -l` prints it. The blob is the
 * SSH wire encoding (RFC 8709): the string `ssh-ed25519`, then the 32-byte key, each with a uint32 length.
 */
data class OpenSshPublicKey(
    val text: String,
    val fingerprint: String,
) {
    companion object {
        private const val ALGORITHM = "Ed25519"
        private const val TYPE = "ssh-ed25519"
        private const val KEY_BYTES = 32

        // DER of SubjectPublicKeyInfo for Ed25519 (RFC 8410) up to the raw key: SEQUENCE { SEQUENCE { OID
        // 1.3.101.112 }, BIT STRING of 33 bytes with no unused bits }. The JDK encodes every Ed25519 key this way.
        private val X509_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

        fun of(publicKey: PublicKey): OpenSshPublicKey {
            val encoded = publicKey.encoded
            require(
                encoded.size == X509_PREFIX.size + KEY_BYTES &&
                    encoded.copyOfRange(0, X509_PREFIX.size).contentEquals(X509_PREFIX),
            ) { "Not an Ed25519 public key" }
            val raw = encoded.copyOfRange(X509_PREFIX.size, encoded.size)
            val type = TYPE.toByteArray(Charsets.US_ASCII)
            val blob =
                ByteBuffer
                    .allocate(Int.SIZE_BYTES + type.size + Int.SIZE_BYTES + raw.size)
                    .putInt(type.size)
                    .put(type)
                    .putInt(raw.size)
                    .put(raw)
                    .array()
            val digest = MessageDigest.getInstance("SHA-256").digest(blob)
            return OpenSshPublicKey(
                text = "$TYPE ${Base64.getEncoder().encodeToString(blob)}",
                fingerprint = "SHA256:${Base64.getEncoder().withoutPadding().encodeToString(digest)}",
            )
        }

        /** The JDK form of an OpenSSH public key [text] without a comment, as [of] produces it. */
        fun decode(text: String): PublicKey {
            val blob = Base64.getDecoder().decode(text.removePrefix("$TYPE "))
            val buffer = ByteBuffer.wrap(blob)
            check(String(readString(buffer), Charsets.US_ASCII) == TYPE) { "Not an Ed25519 key" }
            val raw = readString(buffer)
            check(raw.size == KEY_BYTES && !buffer.hasRemaining()) { "Malformed Ed25519 key" }
            return KeyFactory.getInstance(ALGORITHM).generatePublic(X509EncodedKeySpec(X509_PREFIX + raw))
        }

        private fun readString(buffer: ByteBuffer): ByteArray {
            val length = buffer.getInt()
            check(length in 0..buffer.remaining()) { "Malformed SSH string" }
            return ByteArray(length).also { buffer.get(it) }
        }
    }
}
