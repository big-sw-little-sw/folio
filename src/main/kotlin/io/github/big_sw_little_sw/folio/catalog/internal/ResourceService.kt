package io.github.big_sw_little_sw.folio.catalog.internal

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.ResourceId
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/** A Namespace or a ConfigSet with its ancestry and description. */
data class ResourceSummary(val ancestry: Ancestry, val description: String?)

/**
 * Finds a Namespace or a ConfigSet for the pages that serve both: the edit, move, delete and rules pages. Each page
 * authorizes with the action of its operation, not with `READ`, because the actions are independent (design 7.1).
 */
@Service
class ResourceService(
    private val tree: TreeRepository,
    private val namespaces: NamespaceRepository,
    private val configSets: ConfigSetRepository,
    private val authorizer: Authorizer,
) {
    /** Finds the resource (404), then requires [action] on it (403 or 401). */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun summary(caller: Caller, resource: ResourceId, action: Action): ResourceSummary {
        val ancestry = tree.ancestry(resource) ?: throw notFound(resource)
        authorizer.require(caller, action, ancestry)
        val description =
            when (resource) {
                is NamespaceId -> namespaces.find(resource)?.description
                is ConfigSetId -> configSets.find(resource)?.description
            }
        return ResourceSummary(ancestry, description)
    }
}
