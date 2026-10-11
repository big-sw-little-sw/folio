package io.github.big_sw_little_sw.folio.catalog.internal

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.ResourceId
import io.github.big_sw_little_sw.folio.catalog.SnapshotRead
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.stereotype.Service

/**
 * A Namespace or a ConfigSet with its ancestry and description. [readable] has the links of the ancestry that the
 * caller can READ. The pages make links only to these resources (design 12.3).
 */
data class ResourceSummary(val ancestry: Ancestry, val description: String?, val readable: Set<ResourceId>)

/**
 * Finds a Namespace or a ConfigSet for the pages that serve both: the edit, move, delete and rules pages. Each page
 * authorizes with the action of its operation, not with `READ`. The actions are independent, so a caller with `MANAGE`
 * can lack `READ` (design 7.1).
 */
@Service
class ResourceService(
    private val tree: TreeRepository,
    private val namespaces: NamespaceRepository,
    private val configSets: ConfigSetRepository,
    private val authorizer: Authorizer,
) {
    /** Finds the resource (404), then requires [action] on it (403 or 401). */
    @SnapshotRead
    fun summary(caller: Caller, resource: ResourceId, action: Action): ResourceSummary {
        val ancestry = tree.requireAncestry(resource)
        authorizer.require(caller, action, ancestry)
        val description =
            when (resource) {
                is NamespaceId -> namespaces.find(resource)?.description
                is ConfigSetId -> configSets.find(resource)?.description
            }
        return ResourceSummary(ancestry, description, authorizer.readable(caller, listOf(ancestry)))
    }
}
