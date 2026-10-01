package io.github.big_sw_little_sw.folio.policy

import io.github.big_sw_little_sw.folio.security.ApplicationPrincipal

// Request and response bodies of the rule and explain endpoints. The namespace and ConfigSet modules serve
// those endpoints because they know the paths; the bodies are the same for both (ADR 0015).

/** Subjects in text form: `public`, `authenticated`, `user:<id>`, `group:<id>` or `application:<id>`. */
data class RuleResponse(
    val action: Action,
    val subjects: List<String>,
)

data class PutRuleRequest(
    val subjects: List<String>,
) {
    fun toRule(action: Action) = Rule(action, subjects.map(Subject::parse).toSet())
}

/** A null [principal] explains the decision for an anonymous caller. */
data class ExplainRequest(
    val action: Action,
    val principal: PrincipalRequest? = null,
) {
    fun toPrincipal(): ApplicationPrincipal =
        principal?.let { ApplicationPrincipal.Authenticated(it.subject, it.groups, it.applicationId) }
            ?: ApplicationPrincipal.Anonymous
}

data class PrincipalRequest(
    val subject: String,
    val groups: Set<String> = emptySet(),
    val applicationId: String? = null,
)

/** [policySource] is the API ID of the resource whose rule decided, if any; [reason] names the [Decision] kind. */
data class DecisionResponse(
    val allowed: Boolean,
    val action: Action,
    val resourceId: String,
    val reason: String,
    val policySource: String?,
    val matchedSubject: String?,
)

fun Rule.toResponse() = RuleResponse(action, subjects.map { it.toString() })

/** [apiId] formats a policy source as the API ID of its module (ADR 0007). */
fun Decision.toResponse(
    action: Action,
    resourceId: String,
    apiId: (ResourceRef) -> String,
) = when (this) {
    is Decision.BootstrapAdmin -> {
        DecisionResponse(true, action, resourceId, "BOOTSTRAP_ADMIN", null, subject.toString())
    }

    is Decision.Granted -> {
        DecisionResponse(true, action, resourceId, "RULE_MATCHED", apiId(source), subject.toString())
    }

    is Decision.NotGranted -> {
        DecisionResponse(false, action, resourceId, "RULE_NOT_MATCHED", apiId(source), null)
    }

    Decision.NoRule -> {
        DecisionResponse(false, action, resourceId, "NO_RULE", null, null)
    }
}
