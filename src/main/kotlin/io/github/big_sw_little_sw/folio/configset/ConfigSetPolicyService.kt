package io.github.big_sw_little_sw.folio.configset

import io.github.big_sw_little_sw.folio.configset.internal.ConfigSetRepository
import io.github.big_sw_little_sw.folio.namespace.NamespaceTree
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
 * Rules on ConfigSets. The policy module owns rules but not the tree, so this service supplies each
 * ConfigSet's path. [PolicyService] authorizes every call. Rule writes publish a [ConfigSetEvent].
 */
@Service
class ConfigSetPolicyService(
    private val configSets: ConfigSetRepository,
    private val tree: NamespaceTree,
    private val policy: PolicyService,
    private val events: ApplicationEventPublisher,
) {
    @Transactional(readOnly = true)
    fun rules(id: ConfigSetId): List<Rule> = policy.rules(policyPath(existing(id)))

    /**
     * Rule writes take the tree lock like ConfigSet writes, so a concurrent move cannot change the path
     * being authorized and a concurrent delete cannot remove the ConfigSet the rule references.
     */
    @Transactional
    fun putRule(
        id: ConfigSetId,
        rule: Rule,
    ): Rule {
        tree.lock()
        val configSet = existing(id)
        val put = policy.putRule(policyPath(configSet), rule)
        events.publishEvent(ConfigSetRulePut(id, path(configSet), put))
        return put
    }

    /** Deleting a rule that does not exist succeeds and publishes nothing. */
    @Transactional
    fun deleteRule(
        id: ConfigSetId,
        action: Action,
    ) {
        tree.lock()
        val configSet = existing(id)
        if (policy.deleteRule(policyPath(configSet), action)) {
            events.publishEvent(ConfigSetRuleDeleted(id, path(configSet), action))
        }
    }

    @Transactional(readOnly = true)
    fun explain(
        id: ConfigSetId,
        principal: ApplicationPrincipal,
        action: Action,
    ): Decision = policy.explain(principal, action, policyPath(existing(id)))

    private fun existing(id: ConfigSetId): ConfigSet = configSets.findById(id) ?: throw ConfigSetNotFoundException(id)

    private fun policyPath(configSet: ConfigSet): List<ResourceRef> =
        tree.policyPath(configSet.namespaceId) + ResourceRef.ConfigSetRef(configSet.id.value)

    private fun path(configSet: ConfigSet) = ConfigSetPath(tree.slugPath(configSet.namespaceId), configSet.slug)
}
