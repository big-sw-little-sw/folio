package io.github.big_sw_little_sw.folio.policy

import io.github.big_sw_little_sw.folio.security.ApplicationPrincipal
import java.util.UUID

/** The outcome of an authorization check and why (design 9.5). */
sealed interface Decision {
    val allowed: Boolean

    /** Bootstrap admins hold every action everywhere, ahead of any rule (ADR 0008). */
    data class BootstrapAdmin(
        val subject: Subject,
    ) : Decision {
        override val allowed get() = true
    }

    /** The nearest rule, on [namespaceId], grants to [subject]. */
    data class Granted(
        val namespaceId: UUID,
        val subject: Subject,
    ) : Decision {
        override val allowed get() = true
    }

    /** The nearest rule, on [namespaceId], has no subject that matches; farther rules do not count. */
    data class NotGranted(
        val namespaceId: UUID,
    ) : Decision {
        override val allowed get() = false
    }

    /** No namespace on the path has a rule for the action: default deny. */
    data object NoRule : Decision {
        override val allowed get() = false
    }
}

/**
 * Nearest-rule resolution (design 9.3). [namespacePath] runs from a root namespace to the target and is
 * empty for the root itself. [rules] holds the subjects of each namespace that has a rule for the action.
 */
internal fun decide(
    principal: ApplicationPrincipal,
    namespacePath: List<UUID>,
    rules: Map<UUID, List<Subject>>,
    bootstrapAdmins: Set<Subject>,
): Decision {
    bootstrapAdmins.firstOrNull { it.matches(principal) }?.let { return Decision.BootstrapAdmin(it) }
    val nearest = namespacePath.lastOrNull { it in rules } ?: return Decision.NoRule
    val subject = rules.getValue(nearest).firstOrNull { it.matches(principal) }
    return if (subject == null) Decision.NotGranted(nearest) else Decision.Granted(nearest, subject)
}
