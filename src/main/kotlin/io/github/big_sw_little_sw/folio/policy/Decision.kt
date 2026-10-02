package io.github.big_sw_little_sw.folio.policy

import io.github.big_sw_little_sw.folio.security.ApplicationPrincipal

/** The outcome of an authorization check and why (design 9.5). */
sealed interface Decision {
    val allowed: Boolean

    /** Super admins hold every action everywhere, ahead of any rule (ADR 0008, ADR 0028). */
    data class SuperAdmin(
        val subject: Subject,
    ) : Decision {
        override val allowed get() = true
    }

    /** The nearest rule, on [source], grants to [subject]. */
    data class Granted(
        val source: ResourceRef,
        val subject: Subject,
    ) : Decision {
        override val allowed get() = true
    }

    /** The nearest rule, on [source], has no subject that matches; farther rules do not count. */
    data class NotGranted(
        val source: ResourceRef,
    ) : Decision {
        override val allowed get() = false
    }

    /** No resource on the path has a rule for the action: default deny. */
    data object NoRule : Decision {
        override val allowed get() = false
    }
}

/**
 * Nearest-rule resolution (design 9.3). [path] runs from a root namespace to the target and is empty for
 * the root itself. [rules] holds the subjects of each resource that has a rule for the action.
 */
internal fun decide(
    principal: ApplicationPrincipal,
    path: List<ResourceRef>,
    rules: Map<ResourceRef, List<Subject>>,
    superAdmins: Set<Subject>,
): Decision {
    superAdmins.firstOrNull { it.matches(principal) }?.let { return Decision.SuperAdmin(it) }
    val nearest = path.lastOrNull { it in rules } ?: return Decision.NoRule
    val subject = rules.getValue(nearest).firstOrNull { it.matches(principal) }
    return if (subject == null) Decision.NotGranted(nearest) else Decision.Granted(nearest, subject)
}
