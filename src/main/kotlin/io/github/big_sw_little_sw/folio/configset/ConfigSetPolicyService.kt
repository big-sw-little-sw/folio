package io.github.big_sw_little_sw.folio.configset

import io.github.big_sw_little_sw.folio.configset.internal.ConfigSetRepository
import io.github.big_sw_little_sw.folio.namespace.NamespaceTree
import io.github.big_sw_little_sw.folio.policy.Action
import io.github.big_sw_little_sw.folio.policy.Decision
import io.github.big_sw_little_sw.folio.policy.PolicyService
import io.github.big_sw_little_sw.folio.policy.ResourceRef
import io.github.big_sw_little_sw.folio.policy.Rule
import io.github.big_sw_little_sw.folio.security.ApplicationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Rules on ConfigSets. The policy module owns rules but not the tree, so this service supplies each
 * ConfigSet's path. [PolicyService] authorizes every call.
 */
@Service
class ConfigSetPolicyService(
    private val configSets: ConfigSetRepository,
    private val tree: NamespaceTree,
    private val policy: PolicyService,
) {
    @Transactional(readOnly = true)
    fun rules(id: ConfigSetId): List<Rule> = policy.rules(path(id))

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
        return policy.putRule(path(id), rule)
    }

    @Transactional
    fun deleteRule(
        id: ConfigSetId,
        action: Action,
    ) {
        tree.lock()
        policy.deleteRule(path(id), action)
    }

    @Transactional(readOnly = true)
    fun explain(
        id: ConfigSetId,
        principal: ApplicationPrincipal,
        action: Action,
    ): Decision = policy.explain(principal, action, path(id))

    private fun path(id: ConfigSetId): List<ResourceRef> {
        val configSet = configSets.findById(id) ?: throw ConfigSetNotFoundException(id)
        return tree.policyPath(configSet.namespaceId) + ResourceRef.ConfigSetRef(id.value)
    }
}
