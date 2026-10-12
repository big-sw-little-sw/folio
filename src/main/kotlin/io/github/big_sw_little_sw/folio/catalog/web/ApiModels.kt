package io.github.big_sw_little_sw.folio.catalog.web

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.CONFIG_SET_ID
import io.github.big_sw_little_sw.folio.catalog.ConfigSet
import io.github.big_sw_little_sw.folio.catalog.NAMESPACE_ID
import io.github.big_sw_little_sw.folio.catalog.ResourceId
import io.github.big_sw_little_sw.folio.catalog.SourceType
import io.github.big_sw_little_sw.folio.catalog.internal.Decision
import io.github.big_sw_little_sw.folio.catalog.internal.Explanation
import io.github.big_sw_little_sw.folio.catalog.internal.Namespace
import io.github.big_sw_little_sw.folio.catalog.internal.NamespaceDetails
import io.github.big_sw_little_sw.folio.catalog.internal.Rule
import io.github.big_sw_little_sw.folio.identity.Caller
import java.util.Optional

data class CreateNamespaceRequest(
    /** A null parent creates a top-level Namespace. */
    val parentId: String? = null,
    val slug: String,
    val description: String? = null,
)

/**
 * The body of a PATCH. An absent field keeps its value. A null or blank description clears the description. Thus
 * [description] is an Optional: null means an absent field, and an empty Optional means JSON null.
 */
data class EditRequest(val slug: String? = null, val description: Optional<String>? = null)

/** A null [parentId] moves the Namespace to the top level. */
data class MoveNamespaceRequest(val parentId: String? = null)

data class MoveConfigSetRequest(val namespaceId: String)

data class PutRuleRequest(val subjects: List<String>)

data class ExplainRequest(
    val action: Action,
    /** The caller to explain. A null caller is an anonymous caller. */
    val caller: CallerRequest? = null,
)

data class CallerRequest(
    val subject: String,
    val groups: Set<String> = emptySet(),
    val applicationId: String? = null,
) {
    fun toCaller() = Caller.Authenticated(subject, groups, applicationId)
}

data class NamespaceResponse(
    val id: String,
    val parentId: String?,
    val slug: String,
    val path: String,
    val description: String?,
)

/** The top-level Namespaces that the caller can READ, in slug order. */
data class NamespacesResponse(val namespaces: List<NamespaceResponse>)

/** A Namespace with the children that the caller can READ. */
data class NamespaceDetailsResponse(
    val id: String,
    val parentId: String?,
    val slug: String,
    val path: String,
    val description: String?,
    val namespaces: List<NamespaceResponse>,
    val configSets: List<ConfigSetResponse>,
)

data class ConfigSetResponse(
    val id: String,
    val namespaceId: String,
    val slug: String,
    val path: String,
    val description: String?,
    val sourceType: SourceType,
)

data class ResolveResponse(val id: String, val path: String)

data class RulesResponse(val rules: List<RuleResponse>)

data class RuleResponse(val action: Action, val subjects: List<String>)

/** The reason why Folio permits or denies the action. */
enum class ExplanationReason {
    /** A super-admin entry matched. */
    SUPER_ADMIN,

    /** A subject of the nearest rule matched. */
    GRANTED,

    /** None of the subjects of the nearest rule matched. */
    NOT_GRANTED,

    /** No rule for the action exists on the resource or above it. */
    NO_RULE,
}

data class ExplanationResponse(
    val action: Action,
    val allowed: Boolean,
    val reason: ExplanationReason,
    val matchedSubject: String?,
    /** The rule that decided. The value is null for `SUPER_ADMIN` and `NO_RULE`. */
    val rule: DecidingRuleResponse?,
)

/** The rule that decided, without its subjects, as `DecidingRule` explains. */
data class DecidingRuleResponse(val resourceId: String, val path: String)

fun Namespace.toResponse() =
    NamespaceResponse(id.toApiId(), parentId?.toApiId(), slug.value, path.toString(), description)

fun NamespaceDetails.toResponse() = NamespaceDetailsResponse(
    id = namespace.id.toApiId(),
    parentId = namespace.parentId?.toApiId(),
    slug = namespace.slug.value,
    path = namespace.path.toString(),
    description = namespace.description,
    namespaces = namespaces.map { it.toResponse() },
    configSets = configSets.map { it.toResponse() },
)

fun ConfigSet.toResponse() =
    ConfigSetResponse(id.toApiId(), namespaceId.toApiId(), slug.value, path.toString(), description, sourceType)

fun Rule.toResponse() = RuleResponse(action, subjects.map { it.toString() })

fun Explanation.toResponse(): ExplanationResponse {
    val reason =
        when (decision) {
            Decision.SuperAdmin -> ExplanationReason.SUPER_ADMIN
            is Decision.Granted -> ExplanationReason.GRANTED
            is Decision.NotGranted -> ExplanationReason.NOT_GRANTED
            Decision.NoRule -> ExplanationReason.NO_RULE
        }
    return ExplanationResponse(
        action = action,
        allowed = decision.allowed,
        reason = reason,
        matchedSubject = matchedSubject?.toString(),
        rule = rule?.let { DecidingRuleResponse(it.resource.toApiId(), it.path.toString()) },
    )
}

/**
 * The resource of a route that serves both collections. [collection] is `namespaces` or `configsets`, and [id] is an
 * API ID of that type.
 */
fun resourceOf(collection: String, id: String): ResourceId = when (collection) {
    NAMESPACES -> NAMESPACE_ID.parse(id)
    else -> CONFIG_SET_ID.parse(id)
}

const val NAMESPACES = "namespaces"
const val CONFIG_SETS = "configsets"
