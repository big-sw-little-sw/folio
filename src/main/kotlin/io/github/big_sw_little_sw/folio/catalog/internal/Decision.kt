package io.github.big_sw_little_sw.folio.catalog.internal

import io.github.big_sw_little_sw.folio.catalog.ResourceId
import io.github.big_sw_little_sw.folio.identity.Caller

/** The result of an authorization decision and its reason. An explanation shows it. */
sealed interface Decision {
    val allowed: Boolean

    /** A super-admin entry matched. Rules did not count. */
    data class SuperAdmin(val entry: Subject) : Decision {
        override val allowed get() = true
    }

    /** The nearest rule is on [resource], and its [subject] matched. */
    data class Granted(val resource: ResourceId, val subject: Subject) : Decision {
        override val allowed get() = true
    }

    /** The nearest rule is on [resource], and none of its subjects matched. Rules further up do not count. */
    data class NotGranted(val resource: ResourceId) : Decision {
        override val allowed get() = false
    }

    /** No resource from the top level down to the resource has a rule for the action. */
    data object NoRule : Decision {
        override val allowed get() = false
    }
}

/**
 * The nearest-rule algorithm (design 7.2). [chain] runs from the top-level Namespace down to the resource. It is
 * empty for the root, where only super admins can act. [rules] has the subjects of each resource in [chain] that has a
 * rule for the action.
 */
fun decide(
    caller: Caller,
    chain: List<ResourceId>,
    rules: Map<ResourceId, Set<Subject>>,
    superAdmins: Set<Subject>,
): Decision {
    superAdmins.firstOrNull { it.matches(caller) }?.let { return Decision.SuperAdmin(it) }
    val nearest = chain.lastOrNull { it in rules } ?: return Decision.NoRule
    val subject = rules.getValue(nearest).firstOrNull { it.matches(caller) }
    return if (subject == null) Decision.NotGranted(nearest) else Decision.Granted(nearest, subject)
}
