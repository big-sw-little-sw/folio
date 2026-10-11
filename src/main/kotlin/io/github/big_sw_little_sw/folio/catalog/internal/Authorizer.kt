package io.github.big_sw_little_sw.folio.catalog.internal

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.CatalogException
import io.github.big_sw_little_sw.folio.catalog.ResourceId
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.stereotype.Component

/** All authorization decisions of Folio (design 7). It reads the rules on an ancestry and applies [decide]. */
@Component
class Authorizer(private val rules: RuleRepository, private val superAdmins: SuperAdmins) {
    fun decide(caller: Caller, action: Action, ancestry: Ancestry): Decision =
        decide(caller, ancestry.resources, rules.subjects(action, ancestry.resources), superAdmins.entries)

    /**
     * Throws [CatalogException.Unauthenticated] to an anonymous caller and [CatalogException.Forbidden] to others, if
     * the caller cannot do [action]. The error names [target], or the root if it is null.
     */
    fun require(
        caller: Caller,
        action: Action,
        ancestry: Ancestry,
        target: ResourceId? = ancestry.links.lastOrNull()?.resource,
    ) {
        if (decide(caller, action, ancestry).allowed) return
        val name = target?.toApiId() ?: "the root"
        throw when (caller) {
            Caller.Anonymous -> CatalogException.Unauthenticated(action, name)
            is Caller.Authenticated, Caller.System -> CatalogException.Forbidden(action, name)
        }
    }

    /** The [children] of [parent] that the caller can do [action] on. One query reads the rules for all of them. */
    fun <T : ResourceId> allowedChildren(caller: Caller, action: Action, parent: Ancestry, children: List<T>): Set<T> {
        val subjects = rules.subjects(action, parent.resources + children)
        return children
            .filter { decide(caller, parent.resources + it, subjects, superAdmins.entries).allowed }
            .toSet()
    }

    fun isSuperAdmin(caller: Caller): Boolean = superAdmins.match(caller) != null
}
