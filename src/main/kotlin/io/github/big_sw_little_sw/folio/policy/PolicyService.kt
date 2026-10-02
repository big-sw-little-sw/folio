package io.github.big_sw_little_sw.folio.policy

import io.github.big_sw_little_sw.folio.policy.internal.PolicyRuleRepository
import io.github.big_sw_little_sw.folio.policy.internal.SuperAdminProperties
import io.github.big_sw_little_sw.folio.security.ApplicationPrincipal
import io.github.big_sw_little_sw.folio.security.CurrentPrincipal
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Authorizes the current principal and manages rules. This module does not know the resource tree, so
 * callers pass the target's `path`: the namespaces from a root namespace down, then the ConfigSet if the
 * target is one, or an empty list for the root above all namespaces. Expected failures are
 * [PolicyException] subtypes.
 *
 * Decisions about the current caller are counted by action, result and reason; explanations and [isPublic] are not
 * (ADR 0039).
 */
@Service
class PolicyService(
    private val repository: PolicyRuleRepository,
    private val currentPrincipal: CurrentPrincipal,
    private val superAdmins: SuperAdminProperties,
    private val meters: MeterRegistry,
) {
    @Transactional(readOnly = true)
    fun isAllowed(
        action: Action,
        path: List<ResourceRef>,
    ): Boolean = authorize(currentPrincipal.get(), action, path)

    /** Throws [NotAuthenticatedException] for anonymous callers and [PermissionDeniedException] for others. */
    @Transactional(readOnly = true)
    fun requireAllowed(
        action: Action,
        path: List<ResourceRef>,
    ) {
        val principal = currentPrincipal.get()
        if (authorize(principal, action, path)) return
        throw when (principal) {
            ApplicationPrincipal.Anonymous -> NotAuthenticatedException(action)
            is ApplicationPrincipal.Authenticated -> PermissionDeniedException(action)
        }
    }

    /**
     * Throws [NotAuthenticatedException] for an anonymous caller, for lookups that answer a missing and a hidden
     * resource alike: an anonymous caller gets the same 401 for both, as when [requireAllowed] denies it (ADR 0035).
     */
    fun requireAuthenticated(action: Action) {
        if (currentPrincipal.get() == ApplicationPrincipal.Anonymous) throw NotAuthenticatedException(action)
    }

    /** Whether [principal] is a configured super admin, whose every decision comes from that status (ADR 0028). */
    fun isSuperAdmin(principal: ApplicationPrincipal): Boolean = superAdmins.subjects.any { it.matches(principal) }

    /** Whether everyone, anonymous callers included, may do [action]: a `public` rule grants it (ADR 0035). */
    @Transactional(readOnly = true)
    fun isPublic(
        action: Action,
        path: List<ResourceRef>,
    ): Boolean = decision(ApplicationPrincipal.Anonymous, action, path).allowed

    /**
     * For a move of [moved] under the resource whose path is [targetPath]: if any of [moved] has rules of its own,
     * requires [Action.POLICY_UPDATE] on the target. Moved rules sit nearer than the target's and could lock out its
     * policy administrators (ADR 0031).
     */
    @Transactional(readOnly = true)
    fun requireAllowedToMoveRules(
        moved: Collection<ResourceRef>,
        targetPath: List<ResourceRef>,
    ) {
        if (repository.existsOnAny(moved)) requireAllowed(Action.POLICY_UPDATE, targetPath)
    }

    /** How [action] on the target would be decided for [principal]. Requires [Action.POLICY_VIEW]. */
    @Transactional(readOnly = true)
    fun explain(
        principal: ApplicationPrincipal,
        action: Action,
        path: List<ResourceRef>,
    ): Decision {
        requireAllowed(Action.POLICY_VIEW, path)
        return decision(principal, action, path)
    }

    /** The rules on the target itself, not inherited ones. Requires [Action.POLICY_VIEW]. */
    @Transactional(readOnly = true)
    fun rules(path: List<ResourceRef>): List<Rule> {
        val target = ruleTarget(path)
        requireAllowed(Action.POLICY_VIEW, path)
        return repository.findByResource(target)
    }

    /** Adds the rule to the target, replacing its rule for the same action. */
    @Transactional
    fun putRule(
        path: List<ResourceRef>,
        rule: Rule,
    ): Rule {
        val target = ruleTarget(path)
        requireAllowed(Action.POLICY_UPDATE, path)
        if (rule.subjects.isEmpty()) throw RuleWithoutSubjectsException(rule.action)
        repository.put(target, rule)
        return rule
    }

    /** Removes the target's rule for [action], if any, so the action inherits again. Returns whether there was one. */
    @Transactional
    fun deleteRule(
        path: List<ResourceRef>,
        action: Action,
    ): Boolean {
        val target = ruleTarget(path)
        requireAllowed(Action.POLICY_UPDATE, path)
        return repository.delete(target, action)
    }

    /** The resource a rule operation acts on. */
    private fun ruleTarget(path: List<ResourceRef>): ResourceRef {
        require(path.isNotEmpty()) { "Rules attach to namespaces and ConfigSets; the root holds none (ADR 0012)" }
        return path.last()
    }

    /** Decides for the current caller and counts the decision. */
    private fun authorize(
        principal: ApplicationPrincipal,
        action: Action,
        path: List<ResourceRef>,
    ): Boolean {
        val decision = decision(principal, action, path)
        val result = if (decision.allowed) "allowed" else "denied"
        meters.counter(DECISIONS, "action", action.name, "result", result, "reason", decision.reasonTag()).increment()
        return decision.allowed
    }

    private fun Decision.reasonTag() =
        when (this) {
            is Decision.SuperAdmin -> "super_admin"
            is Decision.Granted -> "rule"
            is Decision.NotGranted -> "not_granted"
            Decision.NoRule -> "no_rule"
        }

    private fun decision(
        principal: ApplicationPrincipal,
        action: Action,
        path: List<ResourceRef>,
    ): Decision = decide(principal, path, repository.findSubjects(action, path), superAdmins.subjects)

    private companion object {
        const val DECISIONS = "folio.authorization.decisions"
    }
}
