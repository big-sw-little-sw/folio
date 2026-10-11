package io.github.big_sw_little_sw.folio.catalog.web

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.ResourceId
import io.github.big_sw_little_sw.folio.catalog.internal.Ancestry
import io.github.big_sw_little_sw.folio.catalog.internal.ResourceService
import io.github.big_sw_little_sw.folio.catalog.internal.ResourceSummary
import io.github.big_sw_little_sw.folio.identity.CurrentCaller
import org.springframework.stereotype.Component

/**
 * One slug of a path on a page. [url] is null if the caller cannot READ the resource of the slug. A caller can see the
 * full path of each resource that the caller can READ. A link to a resource without `READ` would only give a 403 page
 * (design 7.4, 12.3).
 */
data class Crumb(val label: String, val url: String?) {
    companion object {
        fun of(links: List<Ancestry.Link>, readable: Set<ResourceId>): List<Crumb> = links.map { link ->
            Crumb(link.slug.value, ResourceView.pageOf(link.resource).takeIf { link.resource in readable })
        }
    }
}

/** A resource on the start page (design 12.3), with its full path. */
data class StartItem(val kind: String, val path: List<Crumb>, val description: String?) {
    companion object {
        fun of(summary: ResourceSummary): StartItem {
            val links = summary.ancestry.links
            return StartItem(
                kind = ResourceView.kindOf(links.last().resource),
                path = Crumb.of(links, summary.readable),
                description = summary.description,
            )
        }
    }
}

/**
 * A Namespace or a ConfigSet as the pages show it. The edit, move, delete and rules pages serve both kinds of resource.
 */
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

    /**
     * The page of the parent Namespace if the caller can READ the parent, or else the start page. A delete sends the
     * browser here. The start page is simpler than a search up the path for a Namespace that the caller can READ.
     */
    val parentUrl: String get() = crumbs.lastOrNull()?.url ?: "/"

    companion object {
        private const val NAMESPACE = "Namespace"

        /**
         * [readable] has the links of [ancestry] that the caller can READ. Only the crumbs of these resources are
         * links.
         */
        fun of(ancestry: Ancestry, description: String?, readable: Set<ResourceId>): ResourceView {
            val self = ancestry.links.last()
            return ResourceView(
                kind = kindOf(self.resource),
                id = self.resource.toApiId(),
                slug = self.slug.value,
                path = ancestry.path.toString(),
                description = description,
                url = pageOf(self.resource),
                crumbs = Crumb.of(ancestry.links.dropLast(1), readable),
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
 * Loads a Namespace or a ConfigSet for a page. Each page authorizes with the action of its operation (design 7.3,
 * 12.3). The rules page needs `GRANT`. The edit, move and delete forms need `MANAGE`.
 */
@Component
class ResourceViews(private val currentCaller: CurrentCaller, private val resources: ResourceService) {
    fun of(resource: ResourceId, action: Action): ResourceView {
        val summary = resources.summary(currentCaller.get(), resource, action)
        return ResourceView.of(summary.ancestry, summary.description, summary.readable)
    }
}

/** The lines of a text area. The function trims each line and removes blank lines. */
fun lines(text: String?): List<String> = text
    .orEmpty()
    .lines()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
