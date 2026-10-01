package io.github.big_sw_little_sw.folio.security.internal

import io.github.big_sw_little_sw.folio.security.ApplicationPrincipal
import org.springframework.boot.context.properties.ConfigurationProperties

/** Names of the JWT claims that carry the principal's identity (ADR 0002). */
@ConfigurationProperties("folio.security.claims")
data class ClaimNames(
    val subject: String = "sub",
    val groups: String = "groups",
    val applicationId: String = "azp",
) {
    init {
        require(subject.isNotBlank() && groups.isNotBlank() && applicationId.isNotBlank()) {
            "folio.security.claims names must not be blank"
        }
    }

    /** Fails with [IllegalArgumentException] if the subject is missing or a claim has the wrong type. */
    fun toPrincipal(claims: Map<String, Any?>): ApplicationPrincipal.Authenticated {
        val subjectValue = claims[subject]
        require(subjectValue is String && subjectValue.isNotBlank()) { "Claim '$subject' must be a non-blank string" }
        val groupsValue = claims[groups] ?: emptyList<String>()
        require(groupsValue is Collection<*> && groupsValue.all { it is String }) {
            "Claim '$groups' must be a list of strings"
        }
        val applicationIdValue = claims[applicationId]
        require(applicationIdValue == null || applicationIdValue is String) {
            "Claim '$applicationId' must be a string"
        }
        return ApplicationPrincipal.Authenticated(
            subject = subjectValue,
            groups = groupsValue.filterIsInstance<String>().toSet(),
            applicationId = applicationIdValue,
        )
    }
}
