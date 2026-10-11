package io.github.big_sw_little_sw.folio.catalog

import io.github.big_sw_little_sw.folio.catalog.internal.NamespaceRepository
import io.github.big_sw_little_sw.folio.catalog.internal.TreeRepository
import org.springframework.stereotype.Service

/**
 * Other modules find Namespaces through this class (design 8.1). For example, the `credential` module finds the
 * Namespace that owns a credential. The HTTP routes of the catalog do the Namespace operations, so this class has
 * only a lookup.
 */
@Service
class Namespaces(private val tree: TreeRepository, private val namespaces: NamespaceRepository) {
    /**
     * Finds a Namespace without authorization. One snapshot keeps the row and its path consistent. Without the
     * snapshot, a parallel move could give a path that does not match the row.
     */
    @SnapshotRead
    fun find(id: NamespaceId): Namespace? {
        val row = namespaces.find(id) ?: return null
        val ancestry = tree.ancestry(id) ?: return null
        return row.toNamespace(ancestry.path)
    }
}
