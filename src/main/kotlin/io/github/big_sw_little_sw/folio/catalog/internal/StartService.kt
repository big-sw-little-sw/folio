package io.github.big_sw_little_sw.folio.catalog.internal

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.SnapshotRead
import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.folio.identity.Subject
import org.springframework.stereotype.Service

/**
 * Finds the resources where a caller starts to browse the catalog. A caller with `READ` only deep in the tree sees that
 * resource on the start page, with the path above it.
 */
@Service
class StartService(
    private val tree: TreeRepository,
    private val namespaceRepository: NamespaceRepository,
    private val authorizer: Authorizer,
) {
    /**
     * The resources that the caller can READ and whose parent the caller cannot READ, in path order. The Namespace
     * pages list the other resources that the caller can READ, so the start page does not show them again. For a super
     * admin, the result is the top-level Namespaces.
     *
     * For other callers, a resource without its own `READ` rule gets `READ` only from its parent. Thus each start
     * resource has a `READ` rule with a subject that matches the caller. One query finds these rules. [Authorizer] then
     * decides each link of each path, because a nearer rule can take `READ` away.
     */
    @SnapshotRead
    fun entries(caller: Caller): List<ResourceSummary> {
        if (caller.superAdmin) {
            return namespaceRepository.children(null).map {
                ResourceSummary(Ancestry.ROOT.child(it.id, it.slug), it.description, setOf(it.id))
            }
        }
        val granted = tree.withRuleFor(Action.READ, Subject.matching(caller))
        val readable = authorizer.readable(caller, granted.keys)
        return granted
            .map { (ancestry, description) ->
                ResourceSummary(ancestry, description, ancestry.resources.filterTo(mutableSetOf()) { it in readable })
            }.filter(::isTopMost)
            .sortedBy { it.ancestry.path.toString() }
    }

    /**
     * True if the caller can READ the resource but not its parent. A top-level resource has no parent, so only its own
     * `READ` counts.
     */
    private fun isTopMost(summary: ResourceSummary): Boolean {
        val chain = summary.ancestry.resources
        return chain.last() in summary.readable && chain.getOrNull(chain.size - 2) !in summary.readable
    }
}
