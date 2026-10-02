package io.github.big_sw_little_sw.folio.namespace

import io.github.big_sw_little_sw.folio.policy.Action
import io.github.big_sw_little_sw.folio.policy.Decision
import io.github.big_sw_little_sw.folio.policy.PolicyService
import io.github.big_sw_little_sw.folio.policy.ResourceRef
import io.github.big_sw_little_sw.folio.policy.Rule
import io.github.big_sw_little_sw.folio.security.ApplicationPrincipal
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Rules on namespaces. The policy module owns rules but not the tree, so this service supplies each
 * namespace's path. [PolicyService] authorizes every call. Rule writes publish a [NamespaceEvent].
 */
@Service
class NamespacePolicyService(
    private val tree: NamespaceTree,
    private val policy: PolicyService,
    private val events: ApplicationEventPublisher,
) {
    @Transactional(readOnly = true)
    fun rules(id: NamespaceId): List<Rule> = policy.rules(path(id))

    /**
     * Rule writes take the tree lock like namespace writes, so a concurrent move cannot change the path
     * being authorized and a concurrent delete cannot remove the namespace the rule references.
     */
    @Transactional
    fun putRule(
        id: NamespaceId,
        rule: Rule,
    ): Rule {
        tree.lock()
        val put = policy.putRule(path(id), rule)
        events.publishEvent(NamespaceRulePut(id, tree.slugPath(id), put))
        return put
    }

    @Transactional
    fun deleteRule(
        id: NamespaceId,
        action: Action,
    ) {
        tree.lock()
        policy.deleteRule(path(id), action)
        events.publishEvent(NamespaceRuleDeleted(id, tree.slugPath(id), action))
    }

    @Transactional(readOnly = true)
    fun explain(
        id: NamespaceId,
        principal: ApplicationPrincipal,
        action: Action,
    ): Decision = policy.explain(principal, action, path(id))

    private fun path(id: NamespaceId): List<ResourceRef> = tree.policyPath(id)
}
