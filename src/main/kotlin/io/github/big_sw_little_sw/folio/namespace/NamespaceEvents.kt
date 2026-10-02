package io.github.big_sw_little_sw.folio.namespace

import io.github.big_sw_little_sw.folio.policy.Action
import io.github.big_sw_little_sw.folio.policy.Rule

/**
 * Published synchronously inside the transaction of each namespace and namespace rule write, after the write; a
 * listener that throws rolls the write back. Audit records them (ADR 0038). [path] holds the slugs from the root down
 * to the namespace after the write, or before it for a delete.
 */
sealed interface NamespaceEvent {
    val id: NamespaceId
    val path: List<Slug>
}

data class NamespaceCreated(
    override val id: NamespaceId,
    override val path: List<Slug>,
) : NamespaceEvent

data class NamespaceRenamed(
    override val id: NamespaceId,
    override val path: List<Slug>,
    val previousPath: List<Slug>,
) : NamespaceEvent

data class NamespaceMoved(
    override val id: NamespaceId,
    override val path: List<Slug>,
    val previousPath: List<Slug>,
) : NamespaceEvent

data class NamespaceDeleted(
    override val id: NamespaceId,
    override val path: List<Slug>,
) : NamespaceEvent

data class NamespaceRulePut(
    override val id: NamespaceId,
    override val path: List<Slug>,
    val rule: Rule,
) : NamespaceEvent

data class NamespaceRuleDeleted(
    override val id: NamespaceId,
    override val path: List<Slug>,
    val action: Action,
) : NamespaceEvent
