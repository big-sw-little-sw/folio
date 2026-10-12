package io.github.big_sw_little_sw.folio.catalog.internal

import io.github.big_sw_little_sw.folio.catalog.Path
import io.github.big_sw_little_sw.folio.catalog.ResourceId
import io.github.big_sw_little_sw.folio.catalog.Slug

/**
 * A resource and the Namespaces above it, from the top level down. The last link is the resource. The root ancestry has
 * no links.
 */
data class Ancestry(val links: List<Link>) {
    data class Link(val resource: ResourceId, val slug: Slug)

    val resources: List<ResourceId> get() = links.map { it.resource }

    val path: Path get() = Path(links.map { it.slug })

    /**
     * The path of [resource]. [resource] must be a link of this ancestry. For another resource, the path is empty, and
     * [Path] throws.
     */
    fun pathOf(resource: ResourceId): Path = Path(links.take(resources.indexOf(resource) + 1).map { it.slug })

    fun child(resource: ResourceId, slug: Slug): Ancestry = Ancestry(links + Link(resource, slug))

    /** The path of a child named [slug]. At the root, the path has one segment. */
    fun childPath(slug: Slug): Path = Path(links.map { it.slug } + slug)

    /** The path after a rename of the resource to [slug]. */
    fun renamed(slug: Slug): Path = Path(links.dropLast(1).map { it.slug } + slug)

    companion object {
        val ROOT = Ancestry(emptyList())
    }
}
