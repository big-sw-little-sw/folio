package io.github.big_sw_little_sw.folio.catalog.internal

import io.github.big_sw_little_sw.folio.catalog.ResourceId
import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.folio.identity.Subject

/** The result of an authorization decision and its reason. An explanation shows the reason. */
sealed interface Decision {
    val allowed: Boolean

    /** The caller is a super admin. */
    data object SuperAdmin : Decision {
        override val allowed get() = true
    }

    /** The nearest rule is on [resource], and its [subject] matched. */
    data class Granted(val resource: ResourceId, val subject: Subject) : Decision {
        override val allowed get() = true
    }

    /**
     * The nearest rule is on [resource], and no subject of that rule matched. Rules on the Namespaces above [resource]
     * do not count.
     */
    data class NotGranted(val resource: ResourceId) : Decision {
        override val allowed get() = false
    }

    /** No resource from the top level down to the resource has a rule for the action. */
    data object NoRule : Decision {
        override val allowed get() = false
    }
}

/**
 * Applies the nearest-rule algorithm. A super admin can do all actions, and no rule can change that. For other
 * callers, the nearest rule for the action decides, and without a rule Folio denies the action. The function is pure,
 * so unit tests run it without Spring.
 *
 * [chain] runs from the top-level Namespace down to the resource. [chain] is empty for the root, so only a super admin
 * can act there. [rules] has the subjects of each resource in [chain] that has a rule for the action.
 */
fun decide(caller: Caller, chain: List<ResourceId>, rules: Map<ResourceId, Set<Subject>>): Decision {
    if (caller.superAdmin) return Decision.SuperAdmin
    val nearest = chain.lastOrNull { it in rules } ?: return Decision.NoRule
    val names = Subject.matching(caller)
    val subject = rules.getValue(nearest).firstOrNull { it in names }
    return if (subject == null) Decision.NotGranted(nearest) else Decision.Granted(nearest, subject)
}
