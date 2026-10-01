package io.github.big_sw_little_sw.folio.policy

import io.github.big_sw_little_sw.folio.policy.internal.BootstrapProperties
import io.github.big_sw_little_sw.folio.policy.internal.PolicyRuleRepository
import io.github.big_sw_little_sw.folio.security.ApplicationPrincipal
import io.github.big_sw_little_sw.folio.security.CurrentPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Authorizes the current principal and manages rules. This module does not know the namespace tree, so
 * callers pass the target's `namespacePath`: namespace IDs from a root namespace down to the target,
 * or an empty list for the root above all namespaces. Expected failures are [PolicyException] subtypes.
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
        namespacePath: List<UUID>,
    ): Boolean = decision(currentPrincipal.get(), action, namespacePath).allowed

    /** Throws [NotAuthenticatedException] for anonymous callers and [PermissionDeniedException] for others. */
    @Transactional(readOnly = true)
    fun requireAllowed(
        action: Action,
        namespacePath: List<UUID>,
    ) {
        val principal = currentPrincipal.get()
        if (decision(principal, action, namespacePath).allowed) return
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
        namespacePath: List<UUID>,
    ): Decision {
        requireAllowed(Action.POLICY_VIEW, namespacePath)
        return decision(principal, action, namespacePath)
    }

    /** The rules on the target namespace itself, not inherited ones. Requires [Action.POLICY_VIEW]. */
    @Transactional(readOnly = true)
    fun rules(namespacePath: List<UUID>): List<Rule> {
        val namespaceId = ruleTarget(namespacePath)
        requireAllowed(Action.POLICY_VIEW, namespacePath)
        return repository.findByNamespace(namespaceId)
    }

    /** Adds the rule to the target namespace, replacing its rule for the same action. */
    @Transactional
    fun putRule(
        namespacePath: List<UUID>,
        rule: Rule,
    ): Rule {
        val namespaceId = ruleTarget(namespacePath)
        requireAllowed(Action.POLICY_UPDATE, namespacePath)
        if (rule.subjects.isEmpty()) throw RuleWithoutSubjectsException(rule.action)
        repository.put(namespaceId, rule)
        return rule
    }

    /** Removes the target namespace's rule for [action], if any, so the action inherits again. */
    @Transactional
    fun deleteRule(
        namespacePath: List<UUID>,
        action: Action,
    ) {
        val namespaceId = ruleTarget(namespacePath)
        requireAllowed(Action.POLICY_UPDATE, namespacePath)
        repository.delete(namespaceId, action)
    }

    /** The namespace a rule operation acts on. */
    private fun ruleTarget(namespacePath: List<UUID>): UUID {
        require(namespacePath.isNotEmpty()) { "Rules attach to namespaces; the root holds none (ADR 0012)" }
        return namespacePath.last()
    }

    private fun decision(
        principal: ApplicationPrincipal,
        action: Action,
        namespacePath: List<UUID>,
    ): Decision =
        decide(principal, namespacePath, repository.findSubjects(action, namespacePath), bootstrap.adminSubjects)
}
