package io.github.big_sw_little_sw.folio.configset

import io.github.big_sw_little_sw.folio.apiid.formatApiId
import io.github.big_sw_little_sw.folio.apiid.parseApiId

// Public so that HTTP layers of other modules, such as sync, can accept and return ConfigSet IDs (ADR 0032).
private const val PREFIX = "cfg_"

fun ConfigSetId.toApiId(): String = formatApiId(PREFIX, value)

fun String.toConfigSetId(): ConfigSetId =
    ConfigSetId(parseApiId(PREFIX, this) ?: throw InvalidConfigSetIdException(this))
