package io.github.big_sw_little_sw.folio.configset

import io.github.big_sw_little_sw.folio.policy.Action
import io.github.big_sw_little_sw.folio.policy.Rule
import io.github.big_sw_little_sw.folio.source.SourceDefinition

/**
 * Published synchronously inside the transaction of each ConfigSet and ConfigSet rule write, after the write; a
 * listener that throws rolls the write back. Audit records them (ADR 0038). [path] is the ConfigSet's path after the
 * write, or before it for a delete.
 */
sealed interface ConfigSetEvent {
    val id: ConfigSetId
    val path: ConfigSetPath
}

/** Sync also uses it to create the ConfigSet's sync state with it (ADR 0034). */
data class ConfigSetCreated(
    override val id: ConfigSetId,
    override val path: ConfigSetPath,
    val source: SourceDefinition,
) : ConfigSetEvent

data class ConfigSetRenamed(
    override val id: ConfigSetId,
    override val path: ConfigSetPath,
    val previousPath: ConfigSetPath,
) : ConfigSetEvent

data class ConfigSetMoved(
    override val id: ConfigSetId,
    override val path: ConfigSetPath,
    val previousPath: ConfigSetPath,
) : ConfigSetEvent

/**
 * Sync also listens after commit, to act only on a delete that happened: it removes the cached repository (ADR 0034).
 */
data class ConfigSetDeleted(
    override val id: ConfigSetId,
    override val path: ConfigSetPath,
) : ConfigSetEvent

data class ConfigSetRulePut(
    override val id: ConfigSetId,
    override val path: ConfigSetPath,
    val rule: Rule,
) : ConfigSetEvent

data class ConfigSetRuleDeleted(
    override val id: ConfigSetId,
    override val path: ConfigSetPath,
    val action: Action,
) : ConfigSetEvent
