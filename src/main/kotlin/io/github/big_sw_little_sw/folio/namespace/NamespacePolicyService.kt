package io.github.big_sw_little_sw.folio.namespace

import io.github.big_sw_little_sw.folio.namespace.internal.NamespaceClosureRepository
import io.github.big_sw_little_sw.folio.policy.Action
import io.github.big_sw_little_sw.folio.policy.Decision
import io.github.big_sw_little_sw.folio.policy.PolicyService
import io.github.big_sw_little_sw.folio.policy.Rule
import io.github.big_sw_little_sw.folio.security.ApplicationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Rules on namespaces. The policy module owns rules but not the tree, so this service supplies each
 * namespace's path. [PolicyService] authorizes every call.
 */
@Service
class NamespacePolicyService(
    private val closure: NamespaceClosureRepository,
    private val policy: PolicyService,
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
        closure.lockTree()
        return policy.putRule(path(id), rule)
    }

    @Transactional
    fun deleteRule(
        id: NamespaceId,
        action: Action,
    ) {
        closure.lockTree()
        policy.deleteRule(path(id), action)
    }

    @Transactional(readOnly = true)
    fun explain(
        id: NamespaceId,
        principal: ApplicationPrincipal,
        action: Action,
    ): Decision = policy.explain(principal, action, path(id))

    private fun path(id: NamespaceId): List<UUID> = closure.findPath(id).map { it.value }
}
