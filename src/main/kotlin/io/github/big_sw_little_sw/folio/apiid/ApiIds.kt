package io.github.big_sw_little_sw.folio.apiid

import java.util.UUID

// An API ID is a type prefix plus the UUID as 32 lowercase hex characters (ADR 0007). Each module that owns an ID
// type wraps these two functions with its prefix, ID type and exception; only HTTP layers call the wrappers.

private const val HEX_LENGTH = 32
private const val HALF = HEX_LENGTH / 2
private const val HEX_RADIX = 16

fun formatApiId(
    prefix: String,
    id: UUID,
): String = prefix + id.toString().replace("-", "")

/** The UUID in [value] if it is [prefix] followed by 32 lowercase hex characters, otherwise null. */
fun parseApiId(
    prefix: String,
    value: String,
): UUID? {
    val hex = value.removePrefix(prefix)
    val valid = value.startsWith(prefix) && hex.length == HEX_LENGTH && hex.all { it in '0'..'9' || it in 'a'..'f' }
    if (!valid) return null
    return UUID(
        java.lang.Long.parseUnsignedLong(hex, 0, HALF, HEX_RADIX),
        java.lang.Long.parseUnsignedLong(hex, HALF, HEX_LENGTH, HEX_RADIX),
    )
}
