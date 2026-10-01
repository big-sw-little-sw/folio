package io.github.big_sw_little_sw.folio.namespace

import java.util.UUID

// API IDs are `ns_` plus the UUID as 32 lowercase hex characters (ADR 0007). Public so that HTTP layers of
// other modules, such as ConfigSets, can accept and return namespace IDs (ADR 0015).
private const val PREFIX = "ns_"
private const val HEX_LENGTH = 32
private const val HALF = HEX_LENGTH / 2
private const val HEX_RADIX = 16
private val API_ID = Regex("$PREFIX[0-9a-f]{$HEX_LENGTH}")

fun NamespaceId.toApiId(): String = PREFIX + value.toString().replace("-", "")

fun String.toNamespaceId(): NamespaceId {
    if (!API_ID.matches(this)) throw InvalidNamespaceIdException(this)
    val hex = removePrefix(PREFIX)
    return NamespaceId(
        UUID(
            java.lang.Long.parseUnsignedLong(hex, 0, HALF, HEX_RADIX),
            java.lang.Long.parseUnsignedLong(hex, HALF, HEX_LENGTH, HEX_RADIX),
        ),
    )
}
