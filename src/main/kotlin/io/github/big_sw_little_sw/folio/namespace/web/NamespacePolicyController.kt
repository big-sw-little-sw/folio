package io.github.big_sw_little_sw.folio.namespace.web

import io.github.big_sw_little_sw.folio.namespace.NamespaceId
import io.github.big_sw_little_sw.folio.namespace.NamespacePolicyService
import io.github.big_sw_little_sw.folio.policy.Action
import io.github.big_sw_little_sw.folio.policy.Decision
import io.github.big_sw_little_sw.folio.policy.Rule
import io.github.big_sw_little_sw.folio.policy.Subject
import io.github.big_sw_little_sw.folio.security.ApplicationPrincipal
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Subjects in text form: `public`, `authenticated`, `user:<id>`, `group:<id>` or `application:<id>`. */
data class RuleResponse(
    val action: Action,
    val subjects: List<String>,
)

data class PutRuleRequest(
    val subjects: List<String>,
)

/** A null [principal] explains the decision for an anonymous caller. */
data class ExplainRequest(
    val action: Action,
    val principal: PrincipalRequest? = null,
)

data class PrincipalRequest(
    val subject: String,
    val groups: Set<String> = emptySet(),
    val applicationId: String? = null,
)

/** [policySource] is the namespace whose rule decided, if any; [reason] names the [Decision] kind. */
data class DecisionResponse(
    val allowed: Boolean,
    val action: Action,
    val resourceId: String,
    val reason: String,
    val policySource: String?,
    val matchedSubject: String?,
)

@RestController
@RequestMapping("/api/v1/admin/namespaces")
class NamespacePolicyController(
    private val policies: NamespacePolicyService,
) {
    /** Rules on the namespace itself, not inherited ones. */
    @GetMapping("/{id}/rules")
    fun rules(
        @PathVariable id: String,
    ): List<RuleResponse> = policies.rules(id.toNamespaceId()).map { it.toResponse() }

    /** Adds the rule for [action], replacing the namespace's existing rule for it. */
    @PutMapping("/{id}/rules/{action}")
    fun putRule(
        @PathVariable id: String,
        @PathVariable action: Action,
        @RequestBody request: PutRuleRequest,
    ): RuleResponse {
        val rule = Rule(action, request.subjects.map(Subject::parse).toSet())
        return policies.putRule(id.toNamespaceId(), rule).toResponse()
    }

    @DeleteMapping("/{id}/rules/{action}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteRule(
        @PathVariable id: String,
        @PathVariable action: Action,
    ) {
        policies.deleteRule(id.toNamespaceId(), action)
    }

    @PostMapping("/{id}:explain")
    fun explain(
        @PathVariable id: String,
        @RequestBody request: ExplainRequest,
    ): DecisionResponse {
        val principal =
            request.principal?.let { ApplicationPrincipal.Authenticated(it.subject, it.groups, it.applicationId) }
                ?: ApplicationPrincipal.Anonymous
        return policies.explain(id.toNamespaceId(), principal, request.action).toResponse(request.action, id)
    }

    private fun Rule.toResponse() = RuleResponse(action, subjects.map { it.toString() })

    private fun Decision.toResponse(
        action: Action,
        resourceId: String,
    ) = when (this) {
        is Decision.BootstrapAdmin -> {
            DecisionResponse(true, action, resourceId, "BOOTSTRAP_ADMIN", null, subject.toString())
        }

        is Decision.Granted -> {
            DecisionResponse(true, action, resourceId, "RULE_MATCHED", source(namespaceId), subject.toString())
        }

        is Decision.NotGranted -> {
            DecisionResponse(false, action, resourceId, "RULE_NOT_MATCHED", source(namespaceId), null)
        }

        Decision.NoRule -> {
            DecisionResponse(false, action, resourceId, "NO_RULE", null, null)
        }
    }

    private fun source(namespaceId: UUID) = NamespaceId(namespaceId).toApiId()
}
