package io.github.big_sw_little_sw.folio.namespace.internal

import io.github.big_sw_little_sw.folio.namespace.NamespaceId
import io.github.big_sw_little_sw.folio.namespace.NamespaceNotFoundException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

/** Rows of `namespace_closure`: every (ancestor, descendant) pair, including each namespace with itself. */
@Repository
class NamespaceClosureRepository(
    private val jdbc: JdbcClient,
) {
    /**
     * Serializes namespace writes until the transaction ends. Every write takes this lock first.
     *
     * Writes check the tree and then change it based on what they read. Run concurrently, they act on a
     * stale tree: two crossing moves create a cycle, a create under a subtree being moved keeps the old
     * ancestors, and a rename can overwrite a concurrent move's parent. SHARE ROW EXCLUSIVE conflicts with
     * itself but not with plain reads, so writes run one at a time while reads continue. Namespace writes
     * are rare administrative actions, so the lost write concurrency does not matter.
     */
    fun lockTree() {
        jdbc.sql("lock table namespace_closure in share row exclusive mode").update()
    }

    /** Adds the paths of a new leaf [id]: one per ancestor of [parentId], plus itself at depth 0. */
    fun insertPathsForLeaf(
        id: NamespaceId,
        parentId: NamespaceId?,
    ) {
        jdbc
            .sql(
                """
                insert into namespace_closure (ancestor_id, descendant_id, depth)
                select ancestor_id, :id, depth + 1 from namespace_closure where descendant_id = :parentId
                union all
                select :id, :id, 0
                """.trimIndent(),
            ).param("id", id.value)
            .param("parentId", parentId?.value)
            .update()
    }

    /** IDs from the root namespace down to [id], including [id]. Fails if [id] does not exist. */
    fun findPath(id: NamespaceId): List<NamespaceId> =
        jdbc
            .sql("select ancestor_id from namespace_closure where descendant_id = :id order by depth desc")
            .param("id", id.value)
            .query { rs, _ -> NamespaceId(rs.getObject("ancestor_id", UUID::class.java)) }
            .list()
            // Every namespace has a closure row for itself, so no rows means no namespace.
            .ifEmpty { throw NamespaceNotFoundException(id) }

    /** [id] and all its descendants. */
    fun findSubtree(id: NamespaceId): List<NamespaceId> =
        jdbc
            .sql("select descendant_id from namespace_closure where ancestor_id = :id")
            .param("id", id.value)
            .query { rs, _ -> NamespaceId(rs.getObject("descendant_id", UUID::class.java)) }
            .list()

    fun isAncestorOrSelf(
        ancestorId: NamespaceId,
        descendantId: NamespaceId,
    ): Boolean =
        jdbc
            .sql(
                """
                select exists (
                    select 1 from namespace_closure where ancestor_id = :ancestorId and descendant_id = :descendantId
                )
                """.trimIndent(),
            ).param("ancestorId", ancestorId.value)
            .param("descendantId", descendantId.value)
            .query(Boolean::class.java)
            .single()

    /** Re-links the subtree under [id] from its current ancestors to [newParentId] and its ancestors. */
    fun moveSubtree(
        id: NamespaceId,
        newParentId: NamespaceId?,
    ) {
        jdbc
            .sql(
                """
                delete from namespace_closure
                where descendant_id in (select descendant_id from namespace_closure where ancestor_id = :id)
                  and ancestor_id not in (select descendant_id from namespace_closure where ancestor_id = :id)
                """.trimIndent(),
            ).param("id", id.value)
            .update()
        jdbc
            .sql(
                """
                insert into namespace_closure (ancestor_id, descendant_id, depth)
                select above.ancestor_id, below.descendant_id, above.depth + below.depth + 1
                from namespace_closure above
                cross join namespace_closure below
                where above.descendant_id = :newParentId and below.ancestor_id = :id
                """.trimIndent(),
            ).param("id", id.value)
            .param("newParentId", newParentId?.value)
            .update()
    }

    /** Removes the paths of leaf [id]; a leaf is the ancestor only of itself. */
    fun deletePathsForLeaf(id: NamespaceId) {
        jdbc.sql("delete from namespace_closure where descendant_id = :id").param("id", id.value).update()
    }
}
