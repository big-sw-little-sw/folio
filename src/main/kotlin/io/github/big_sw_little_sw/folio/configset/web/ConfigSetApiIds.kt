package io.github.big_sw_little_sw.folio.configset.web

import io.github.big_sw_little_sw.folio.apiid.formatApiId
import io.github.big_sw_little_sw.folio.apiid.parseApiId
import io.github.big_sw_little_sw.folio.configset.ConfigSetId
import io.github.big_sw_little_sw.folio.configset.InvalidConfigSetIdException

private const val PREFIX = "cfg_"

fun ConfigSetId.toApiId(): String = formatApiId(PREFIX, value)

fun String.toConfigSetId(): ConfigSetId =
    ConfigSetId(parseApiId(PREFIX, this) ?: throw InvalidConfigSetIdException(this))
