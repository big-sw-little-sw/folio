package io.github.big_sw_little_sw.folio.namespace

import io.github.big_sw_little_sw.folio.apiid.formatApiId
import io.github.big_sw_little_sw.folio.apiid.parseApiId

// Public so that HTTP layers of other modules, such as ConfigSets, can accept and return namespace IDs (ADR 0015).
private const val PREFIX = "ns_"

fun NamespaceId.toApiId(): String = formatApiId(PREFIX, value)

fun String.toNamespaceId(): NamespaceId =
    NamespaceId(parseApiId(PREFIX, this) ?: throw InvalidNamespaceIdException(this))
