package io.github.big_sw_little_sw.folio.catalog.internal

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.CatalogException
import io.github.big_sw_little_sw.folio.catalog.Path
import io.github.big_sw_little_sw.folio.catalog.ResourceId
import io.github.big_sw_little_sw.folio.catalog.SnapshotRead
import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.folio.identity.Subject
import io.github.big_sw_little_sw.folio.identity.SuperAdmins
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** The reason why Folio permits or denies an action on a resource for a caller. */
data class Explanation(
    val action: Action,
    val decision: Decision,
    /** The super-admin entry or the subject of the rule that matched the caller, or null if none matched. */
    val matchedSubject: Subject?,
    /** The rule that decided, or null if a super-admin entry matched or no rule exists. */
    val rule: DecidingRule?,
)

/**
 * The resource and the path of the rule that decided. The value has no subjects, because the rule is often on a
 * Namespace above the resource. A caller needs `GRANT` on that Namespace to list its rules. Without this limit, an
 * explanation would show subjects that the caller cannot list.
 */
data class DecidingRule(val resource: ResourceId, val path: Path)

/** Lists, puts and deletes rules, and explains decisions. Each operation needs `GRANT` on the resource. */
@Service
class RuleService(
    private val ruleRepository: RuleRepository,
    private val authorizer: Authorizer,
    private val audit: CatalogAudit,
    private val superAdmins: SuperAdmins,
) {
    /** The own rules of the resource, without inherited rules. */
    @SnapshotRead
    fun rules(caller: Caller, resource: ResourceId): List<Rule> {
        authorizer.authorize(caller, Action.GRANT, resource)
        return ruleRepository.rules(resource)
    }

    /** Replaces the rule of the resource for the action of [rule]. */
    @Transactional
    fun put(caller: Caller, resource: ResourceId, rule: Rule): Rule {
        val ancestry = authorizer.authorize(caller, Action.GRANT, resource)
        val previous = rule(resource, rule.action)
        if (previous?.subjects != rule.subjects) {
            ruleRepository.put(resource, rule)
            audit.rulePut(caller, resource, ancestry.path, rule, previous)
        }
        return rule
    }

    /** Removes the rule, so that the action inherits again. A delete of a rule that does not exist succeeds. */
    @Transactional
    fun delete(caller: Caller, resource: ResourceId, action: Action) {
        val ancestry = authorizer.authorize(caller, Action.GRANT, resource)
        val previous = rule(resource, action) ?: return
        ruleRepository.delete(resource, action)
        audit.ruleDeleted(caller, resource, ancestry.path, previous)
    }

    /**
     * Explains how Folio decides [action] on the resource for the caller that a request describes. A null [described]
     * is an anonymous caller. The method ignores `described.superAdmin` and takes the super-admin status from the
     * configuration. Thus a request cannot give a caller super-admin status.
     */
    @SnapshotRead
    fun explain(caller: Caller, resource: ResourceId, action: Action, described: Caller.Authenticated?): Explanation {
        val ancestry = authorizer.authorize(caller, Action.GRANT, resource)
        val explained =
            if (described == null) {
                Caller.Anonymous
            } else {
                superAdmins.caller(described.subject, described.groups, described.applicationId)
            }
        val decision = authorizer.decide(explained, action, ancestry)
        val (matched, decidingResource) =
            when (decision) {
                Decision.SuperAdmin -> superAdmins.entryFor(explained) to null
                is Decision.Granted -> decision.subject to decision.resource
                is Decision.NotGranted -> null to decision.resource
                Decision.NoRule -> null to null
            }
        return Explanation(action, decision, matched, decidingResource?.let { DecidingRule(it, ancestry.pathOf(it)) })
    }

    private fun rule(resource: ResourceId, action: Action): Rule? =
        ruleRepository.rules(resource).find { it.action == action }
}
