package io.github.big_sw_little_sw.folio.catalog.web

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.ResourceId
import io.github.big_sw_little_sw.folio.catalog.internal.Ancestry
import io.github.big_sw_little_sw.folio.catalog.internal.ResourceSummary

/**
 * One slug of a path on a page. A caller sees the full path of each resource that it can READ. [url] is null if the
 * caller cannot READ the resource of the slug, because a link to that resource would only give a 403 page.
 */
data class Crumb(val label: String, val url: String?) {
    companion object {
        fun of(links: List<Ancestry.Link>, readable: Set<ResourceId>): List<Crumb> = links.map { link ->
            Crumb(link.slug.value, ResourceView.pageOf(link.resource).takeIf { link.resource in readable })
        }
    }
}

/** A resource on the start page, with its full path. */
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

        fun of(summary: ResourceSummary): ResourceView {
            val links = summary.ancestry.links
            val self = links.last()
            return ResourceView(
                kind = kindOf(self.resource),
                id = self.resource.toApiId(),
                slug = self.slug.value,
                path = summary.ancestry.path.toString(),
                description = summary.description,
                url = pageOf(self.resource),
                crumbs = Crumb.of(links.dropLast(1), summary.readable),
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

/** The lines of a text area. The function trims each line and removes blank lines. */
fun lines(text: String?): List<String> = text
    .orEmpty()
    .lines()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
