package io.github.big_sw_little_sw.folio.web

import java.util.UUID

/**
 * The API form of one ID type: a type prefix and the UUID as 32 lowercase hex characters, for example
 * `ns_0192f0c1e8a47c3b9d2e5f6a7b8c9d0e`. With the prefix, a person can see the resource type from the ID alone. The
 * module that owns an ID type declares one instance next to its value class, and its HTTP layer uses it:
 *
 * ```
 * @JvmInline value class NamespaceId(val value: UUID)
 * val NAMESPACE_ID = ApiId("ns", ::NamespaceId, NamespaceId::value)
 * ```
 */
class ApiId<T : Any>(type: String, private val toId: (UUID) -> T, private val toUuid: (T) -> UUID) {
    private val prefix = "${type}_"

    init {
        require(type.isNotEmpty() && type.all { it in 'a'..'z' }) { "An API ID type is lowercase letters: $type" }
    }

    fun format(id: T): String = prefix + toUuid(id).toString().replace("-", "")

    /** Throws [InvalidApiId] (status 400) if [value] is not this prefix and then 32 lowercase hex characters. */
    fun parse(value: String): T {
        val hex = value.removePrefix(prefix)
        val valid = value.startsWith(prefix) && hex.length == HEX_LENGTH && hex.all { it in '0'..'9' || it in 'a'..'f' }
        if (!valid) throw InvalidApiId(prefix)
        return toId(
            UUID(
                java.lang.Long.parseUnsignedLong(hex, 0, HALF, HEX_RADIX),
                java.lang.Long.parseUnsignedLong(hex, HALF, HEX_LENGTH, HEX_RADIX),
            ),
        )
    }

    private companion object {
        const val HEX_LENGTH = 32
        const val HALF = HEX_LENGTH / 2
        const val HEX_RADIX = 16
    }
}

/** The detail does not repeat the value, because a client can send any text and the error page would show it. */
class InvalidApiId(prefix: String) :
    FolioException(
        ErrorKind.INVALID,
        "INVALID_ID",
        "The ID is not valid. Expected $prefix followed by 32 lowercase hexadecimal characters.",
    )
