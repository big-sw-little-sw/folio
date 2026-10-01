package io.github.big_sw_little_sw.folio.configset.web

import io.github.big_sw_little_sw.folio.configset.ConfigSetId
import io.github.big_sw_little_sw.folio.configset.InvalidConfigSetIdException
import java.util.UUID

// API IDs are `cfg_` plus the UUID as 32 lowercase hex characters (ADR 0007).
private const val PREFIX = "cfg_"
private const val HEX_LENGTH = 32
private const val HALF = HEX_LENGTH / 2
private const val HEX_RADIX = 16
private val API_ID = Regex("$PREFIX[0-9a-f]{$HEX_LENGTH}")

fun ConfigSetId.toApiId(): String = PREFIX + value.toString().replace("-", "")

fun String.toConfigSetId(): ConfigSetId {
    if (!API_ID.matches(this)) throw InvalidConfigSetIdException(this)
    val hex = removePrefix(PREFIX)
    return ConfigSetId(
        UUID(
            java.lang.Long.parseUnsignedLong(hex, 0, HALF, HEX_RADIX),
            java.lang.Long.parseUnsignedLong(hex, HALF, HEX_LENGTH, HEX_RADIX),
        ),
    )
}
