package io.github.big_sw_little_sw.folio.policy

import io.github.big_sw_little_sw.folio.policy.internal.BootstrapProperties
import io.github.big_sw_little_sw.folio.policy.internal.PolicyRuleRepository
import io.github.big_sw_little_sw.folio.security.ApplicationPrincipal
import io.github.big_sw_little_sw.folio.security.CurrentPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Authorizes the current principal and manages rules. This module does not know the resource tree, so
 * callers pass the target's `path`: the namespaces from a root namespace down, then the ConfigSet if the
 * target is one, or an empty list for the root above all namespaces. Expected failures are
 * [PolicyException] subtypes.
 */
@Service
class PolicyService(
    private val repository: PolicyRuleRepository,
    private val currentPrincipal: CurrentPrincipal,
    private val bootstrap: BootstrapProperties,
) {
    @Transactional(readOnly = true)
    fun isAllowed(
        action: Action,
        path: List<ResourceRef>,
    ): Boolean = decision(currentPrincipal.get(), action, path).allowed

    /** Throws [NotAuthenticatedException] for anonymous callers and [PermissionDeniedException] for others. */
    @Transactional(readOnly = true)
    fun requireAllowed(
        action: Action,
        path: List<ResourceRef>,
    ) {
        val principal = currentPrincipal.get()
        if (decision(principal, action, path).allowed) return
        throw when (principal) {
            ApplicationPrincipal.Anonymous -> NotAuthenticatedException(action)
            is ApplicationPrincipal.Authenticated -> PermissionDeniedException(action)
        }
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

    /** Removes the target's rule for [action], if any, so the action inherits again. */
    @Transactional
    fun deleteRule(
        path: List<ResourceRef>,
        action: Action,
    ) {
        val target = ruleTarget(path)
        requireAllowed(Action.POLICY_UPDATE, path)
        repository.delete(target, action)
    }

    /** The resource a rule operation acts on. */
    private fun ruleTarget(path: List<ResourceRef>): ResourceRef {
        require(path.isNotEmpty()) { "Rules attach to namespaces and ConfigSets; the root holds none (ADR 0012)" }
        return path.last()
    }

    private fun decision(
        principal: ApplicationPrincipal,
        action: Action,
        path: List<ResourceRef>,
    ): Decision = decide(principal, path, repository.findSubjects(action, path), bootstrap.adminSubjects)
}
