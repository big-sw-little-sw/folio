package io.github.big_sw_little_sw.folio.catalog.internal

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.CatalogException
import io.github.big_sw_little_sw.folio.catalog.Path
import io.github.big_sw_little_sw.folio.catalog.ResourceId
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/** Why Folio permits or denies an action on a resource for a caller (design 7.4). */
data class Explanation(
    val action: Action,
    val decision: Decision,
    /** The rule that decided, or null if a super-admin entry matched or no rule exists. */
    val rule: DecidingRule?,
)

/**
 * The rule that decided: its resource and path. It has no subjects, because the rule is often on a Namespace above,
 * and listing the rules there needs `GRANT` there (design 7.3, 7.4).
 */
data class DecidingRule(val resource: ResourceId, val path: Path)

/** Rules and explanations. All operations need `GRANT` on the resource (design 7.3). */
@Service
class RuleService(
    private val tree: TreeRepository,
    private val rules: RuleRepository,
    private val authorizer: Authorizer,
    private val audit: CatalogAudit,
) {
    /** The own rules of the resource, without inherited rules. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun rules(caller: Caller, resource: ResourceId): List<Rule> {
        authorizer.require(caller, Action.GRANT, ancestry(resource))
        return rules.rules(resource)
    }

    /** Replaces the rule of the resource for the action of [rule]. */
    @Transactional
    fun put(caller: Caller, resource: ResourceId, rule: Rule): Rule {
        if (rule.subjects.isEmpty()) throw CatalogException.NoSubjects()
        tree.lock()
        val ancestry = ancestry(resource)
        authorizer.require(caller, Action.GRANT, ancestry)
        val previous = rule(resource, rule.action)
        if (previous?.subjects != rule.subjects) {
            rules.put(resource, rule)
            audit.rulePut(caller, resource, ancestry.path, rule, previous)
        }
        return rule
    }

    /** Removes the rule, so that the action inherits again. A delete of a rule that does not exist succeeds. */
    @Transactional
    fun delete(caller: Caller, resource: ResourceId, action: Action) {
        tree.lock()
        val ancestry = ancestry(resource)
        authorizer.require(caller, Action.GRANT, ancestry)
        val previous = rule(resource, action) ?: return
        rules.delete(resource, action)
        audit.ruleDeleted(caller, resource, ancestry.path, previous)
    }

    /**
     * How Folio decides [action] on the resource for [explained], a caller that the request describes. [caller] needs
     * `GRANT` on the resource.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun explain(caller: Caller, resource: ResourceId, action: Action, explained: Caller): Explanation {
        val ancestry = ancestry(resource)
        authorizer.require(caller, Action.GRANT, ancestry)
        val decision = authorizer.decide(explained, action, ancestry)
        val decidingResource =
            when (decision) {
                is Decision.Granted -> decision.resource
                is Decision.NotGranted -> decision.resource
                is Decision.SuperAdmin, Decision.NoRule -> null
            }
        return Explanation(action, decision, decidingResource?.let { DecidingRule(it, ancestry.pathOf(it)) })
    }

    private fun rule(resource: ResourceId, action: Action): Rule? = rules.rules(resource).find { it.action == action }

    private fun ancestry(resource: ResourceId): Ancestry = tree.ancestry(resource) ?: throw notFound(resource)
}
