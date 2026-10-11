package io.github.big_sw_little_sw.folio.catalog.internal

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.CatalogException
import io.github.big_sw_little_sw.folio.catalog.ResourceId
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.stereotype.Component

/**
 * The Authorizer makes all authorization decisions of Folio (design 7). It reads the rules on an ancestry and applies
 * [decide]. Each caller carries its super-admin status, so Folio needs no query to find super admins.
 */
@Component
class Authorizer(private val rules: RuleRepository) {
    fun decide(caller: Caller, action: Action, ancestry: Ancestry): Decision =
        decide(caller, ancestry.resources, rules.subjects(action, ancestry.resources))

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
     * decision (design 7.2). One query reads the rules for all links. For a super admin, the method returns all links
     * without a query.
     */
    fun readable(caller: Caller, ancestries: Collection<Ancestry>): Set<ResourceId> {
        val links = ancestries.flatMapTo(mutableSetOf()) { it.resources }
        if (caller.superAdmin) return links
        val subjects = rules.subjects(Action.READ, links)
        return ancestries.flatMapTo(mutableSetOf()) { ancestry ->
            val chain = ancestry.resources
            chain.filterIndexed { index, _ -> decide(caller, chain.take(index + 1), subjects).allowed }
        }
    }

    /**
     * The [children] of [parent] that the caller can READ. Lists show only these children (design 7.4). One query reads
     * the rules of the ancestry of [parent] and of all [children].
     */
    fun readable(caller: Caller, parent: Ancestry, children: List<ResourceId>): Set<ResourceId> {
        val subjects = rules.subjects(Action.READ, parent.resources + children)
        return children
            .filter { decide(caller, parent.resources + it, subjects).allowed }
            .toSet()
    }
}
