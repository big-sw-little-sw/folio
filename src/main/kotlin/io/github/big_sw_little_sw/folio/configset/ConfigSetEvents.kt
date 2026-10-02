package io.github.big_sw_little_sw.folio.configset

/**
 * Published synchronously inside the transaction that creates the ConfigSet. A listener that throws rolls the create
 * back. Sync uses it to create the ConfigSet's sync state with it (ADR 0034).
 */
data class ConfigSetCreated(
    val id: ConfigSetId,
)

/**
 * Published inside the transaction that deletes the ConfigSet; listen after commit to act only on a delete that
 * happened. Sync uses it to remove the ConfigSet's cached repository (ADR 0034).
 */
data class ConfigSetDeleted(
    val id: ConfigSetId,
)
