package io.github.big_sw_little_sw.folio.catalog.web

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.ResourceId
import io.github.big_sw_little_sw.folio.catalog.internal.Ancestry
import io.github.big_sw_little_sw.folio.catalog.internal.ResourceService
import io.github.big_sw_little_sw.folio.identity.CurrentCaller
import org.springframework.stereotype.Component

/** A link in the breadcrumbs of a page. */
data class Crumb(val label: String, val url: String)

/** A Namespace or a ConfigSet as the pages show it. The edit, move, delete and rules pages serve both. */
data class ResourceView(
    /** `Namespace` or `ConfigSet`. */
    val kind: String,
    val id: String,
    val slug: String,
    val path: String,
    val description: String?,
    val url: String,
    /** The Namespaces above the resource, from the top level down. */
    val crumbs: List<Crumb>,
) {
    val isNamespace: Boolean get() = kind == NAMESPACE

    /** The page of the parent Namespace, or the start page. */
    val parentUrl: String get() = crumbs.lastOrNull()?.url ?: "/"

    companion object {
        private const val NAMESPACE = "Namespace"

        fun of(ancestry: Ancestry, description: String?): ResourceView {
            val self = ancestry.links.last()
            return ResourceView(
                kind = kindOf(self.resource),
                id = self.resource.toApiId(),
                slug = self.slug.value,
                path = ancestry.path.toString(),
                description = description,
                url = pageOf(self.resource),
                crumbs = ancestry.links.dropLast(1).map { Crumb(it.slug.value, pageOf(it.resource)) },
            )
        }

        fun kindOf(resource: ResourceId) = when (resource) {
            is NamespaceId -> NAMESPACE
            is ConfigSetId -> "ConfigSet"
        }

        fun pageOf(resource: ResourceId) = when (resource) {
            is NamespaceId -> "/$NAMESPACES/${resource.toApiId()}"
            is ConfigSetId -> "/$CONFIG_SETS/${resource.toApiId()}"
        }
    }
}

/**
 * Loads a Namespace or a ConfigSet for a page. Each page authorizes with the action of its operation: `GRANT` for the
 * rules page, `MANAGE` for the edit, move and delete forms (design 7.3).
 */
@Component
class ResourceViews(private val currentCaller: CurrentCaller, private val resources: ResourceService) {
    fun of(resource: ResourceId, action: Action): ResourceView {
        val summary = resources.summary(currentCaller.get(), resource, action)
        return ResourceView.of(summary.ancestry, summary.description)
    }
}

/** The lines of a text area, without blank lines and spaces at the ends. */
fun lines(text: String?): List<String> = text
    .orEmpty()
    .lines()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
