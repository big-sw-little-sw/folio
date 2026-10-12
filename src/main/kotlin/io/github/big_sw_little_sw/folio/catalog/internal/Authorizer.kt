package io.github.big_sw_little_sw.folio.catalog.internal

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.CatalogException
import io.github.big_sw_little_sw.folio.catalog.ResourceId
import io.github.big_sw_little_sw.folio.catalog.SnapshotRead
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Makes all authorization decisions of Folio, because only the catalog reads rules. Each caller carries its super-admin
 * status, so a decision for a super admin needs no query.
 */
@Component
class Authorizer(private val tree: TreeRepository, private val ruleRepository: RuleRepository) {
    /**
     * Takes the tree lock in a write, finds the ancestry of [resource] (404) and then requires [action] on it (403 or
     * 401). The calling service examines the tree only after this method, so a caller without permission gets 403 or
     * 401, never a conflict. A read-only transaction is a [SnapshotRead] and takes no lock.
     */
    fun authorize(caller: Caller, action: Action, resource: ResourceId): Ancestry {
        if (!TransactionSynchronizationManager.isCurrentTransactionReadOnly()) tree.lock()
        val ancestry = tree.requireAncestry(resource)
        require(caller, action, ancestry)
        return ancestry
    }

    fun decide(caller: Caller, action: Action, ancestry: Ancestry): Decision =
        decide(caller, ancestry.resources, ruleRepository.subjects(action, ancestry.resources))

    /**
     * Throws [CatalogException.Unauthenticated] to an anonymous caller if the caller cannot do [action]. Throws
     * [CatalogException.Forbidden] to the other callers. The Forbidden error names [target], or the root if [target] is
     * null.
     */
    fun require(
        caller: Caller,
        action: Action,
        ancestry: Ancestry,
        target: ResourceId? = ancestry.links.lastOrNull()?.resource,
    ) {
        if (decide(caller, action, ancestry).allowed) return
        throw when (caller) {
            Caller.Anonymous -> CatalogException.Unauthenticated(action)
            is Caller.Authenticated, Caller.System -> CatalogException.Forbidden(action, target)
        }
    }

    /**
     * The links of [ancestries] that the caller can READ. A nearer rule can take `READ` away, so each link gets its own
     * decision. One query reads the rules for all links.
     */
    fun readable(caller: Caller, ancestries: Collection<Ancestry>): Set<ResourceId> {
        val links = ancestries.flatMapTo(mutableSetOf()) { it.resources }
        if (caller.superAdmin) return links
        val subjects = ruleRepository.subjects(Action.READ, links)
        return ancestries.flatMapTo(mutableSetOf()) { ancestry ->
            val chain = ancestry.resources
            chain.filterIndexed { index, _ -> decide(caller, chain.take(index + 1), subjects).allowed }
        }
    }

    /**
     * The [children] of [parent] that the caller can READ. Lists show only these children. One query reads the rules of
     * the ancestry of [parent] and of all [children].
     */
    fun readable(caller: Caller, parent: Ancestry, children: List<ResourceId>): Set<ResourceId> {
        val subjects = ruleRepository.subjects(Action.READ, parent.resources + children)
        return children
            .filter { decide(caller, parent.resources + it, subjects).allowed }
            .toSet()
    }
}
