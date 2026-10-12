package io.github.big_sw_little_sw.folio.catalog

import io.github.big_sw_little_sw.folio.catalog.internal.Authorizer
import io.github.big_sw_little_sw.folio.catalog.internal.NamespaceRepository
import io.github.big_sw_little_sw.folio.catalog.internal.TreeRepository
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.stereotype.Service

/**
 * Other modules find Namespaces through this class, because the catalog repositories are internal. For example, the
 * `credential` module finds the Namespace that owns a credential. The HTTP routes of the catalog do the Namespace
 * operations, so this class has only lookups.
 */
@Service
class Namespaces(
    private val tree: TreeRepository,
    private val namespaceRepository: NamespaceRepository,
    private val authorizer: Authorizer,
) {
    /**
     * Finds a Namespace without authorization. One snapshot keeps the row and its path consistent. Without the
     * snapshot, a parallel move could give a path that does not match the row.
     */
    @SnapshotRead
    fun find(id: NamespaceId): Namespace? {
        val row = namespaceRepository.find(id) ?: return null
        val ancestry = tree.ancestry(id) ?: return null
        return row.toNamespace(ancestry.path)
    }

    /**
     * Finds the Namespace (404) and then requires [action] on it (403 or 401). In a write, the method first takes
     * [TreeRepository.lockShared].
     */
    @SnapshotRead
    fun require(caller: Caller, action: Action, id: NamespaceId): Namespace {
        tree.lockShared()
        val ancestry = tree.requireAncestry(id)
        authorizer.require(caller, action, ancestry)
        val row = namespaceRepository.find(id) ?: throw CatalogException.NamespaceNotFound(id)
        return row.toNamespace(ancestry.path)
    }
}
