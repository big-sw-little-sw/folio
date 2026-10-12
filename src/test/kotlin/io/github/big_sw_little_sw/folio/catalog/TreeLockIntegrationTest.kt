package io.github.big_sw_little_sw.folio.catalog

import io.github.big_sw_little_sw.folio.catalog.internal.NamespaceService
import io.github.big_sw_little_sw.folio.catalog.internal.Rule
import io.github.big_sw_little_sw.folio.catalog.internal.RuleService
import io.github.big_sw_little_sw.folio.catalog.internal.TreeRepository
import io.github.big_sw_little_sw.folio.identity.Subject
import io.github.big_sw_little_sw.testing.*
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import kotlin.test.*

/**
 * Catalog writes take the tree lock first, so parallel writes run one after the other and see the same tree. Reads take
 * no lock.
 */
@FolioIntegrationTest
class TreeLockIntegrationTest(
    @Autowired private val namespaces: NamespaceService,
    @Autowired private val configSets: ConfigSets,
    @Autowired private val rules: RuleService,
    @Autowired private val tree: TreeRepository,
    @Autowired private val catalog: TestCatalog,
    @Autowired private val locks: TestLocks,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val jdbc: JdbcClient,
) {
    @Test
    fun `two parallel moves cannot make a cycle`() {
        val top = catalog.namespace()
        val a = catalog.namespace(top, "a")
        val b = catalog.namespace(top, "b")

        val results =
            whileLocked(
                { namespaces.move(SUPER_ADMIN, a, b) },
                { namespaces.move(SUPER_ADMIN, b, a) },
            )

        assertEquals(1, results.count { it.isSuccess }, "One move wins")
        assertTrue(results.single { it.isFailure }.exceptionOrNull() is CatalogException.MoveIntoOwnSubtree)
        assertEquals(top, topOf(a))
        assertEquals(top, topOf(b))
    }

    @Test
    fun `a parallel create and delete never leave a child without its parent`() {
        val top = catalog.namespace()
        val parent = catalog.namespace(top, "parent")

        val results =
            whileLocked(
                { namespaces.create(SUPER_ADMIN, parent, Slug("child"), null) },
                { namespaces.delete(SUPER_ADMIN, parent) },
            )

        assertEquals(1, results.count { it.isSuccess })
        val failure = results.single { it.isFailure }.exceptionOrNull()
        assertTrue(failure is CatalogException.NamespaceNotEmpty || failure is CatalogException.NamespaceNotFound)
    }

    @Test
    fun `a ConfigSet create of a Source adapter takes the lock`() {
        val top = catalog.namespace()
        val parent = catalog.namespace(top, "parent")

        val results =
            whileLocked(
                { configSets.create(SUPER_ADMIN, parent, Slug("cfg"), null, SourceType.GIT) },
                { namespaces.delete(SUPER_ADMIN, parent) },
            )

        assertEquals(1, results.count { it.isSuccess })
        val failure = results.single { it.isFailure }.exceptionOrNull()
        assertTrue(failure is CatalogException.NamespaceNotEmpty || failure is CatalogException.NamespaceNotFound)
    }

    @Test
    fun `rule writes take the lock`() {
        val top = catalog.namespace()
        val to = catalog.namespace(top, "to")
        val moved = catalog.namespace(top, "moved")
        catalog.rule(moved, Action.MANAGE, "user:alice")

        val results =
            whileLocked(
                { rules.put(SUPER_ADMIN, moved, Rule(Action.READ, setOf(Subject.Public))) },
                { rules.delete(SUPER_ADMIN, moved, Action.MANAGE) },
                { namespaces.move(SUPER_ADMIN, moved, to) },
            )

        assertTrue(results.all { it.isSuccess }, results.toString())
    }

    @Test
    fun `reads take no lock`() {
        val top = catalog.namespace()

        val read = whileHolding(::holdTreeLock) { namespaces.get(SUPER_ADMIN, top) }

        assertEquals(top, read.namespace.id)
    }

    /** Runs [writes] in parallel while another transaction holds the tree lock, and then releases the lock. */
    private fun whileLocked(vararg writes: () -> Any): List<Result<Any?>> = locks.whileHeld({ tree.lock() }, *writes)

    private fun holdTreeLock(held: () -> Unit) = transactions.executeWithoutResult {
        tree.lock()
        held()
    }

    /** `union` removes repeated rows, so the recursive query ends even if the tree has a cycle. */
    private fun topOf(id: NamespaceId): NamespaceId? = jdbc
        .sql(
            """
                with recursive up (id, parent_id) as (
                    select id, parent_id from namespace where id = :id
                    union
                    select n.id, n.parent_id from namespace n join up on n.id = up.parent_id
                )
                select id from up where parent_id is null
            """.trimIndent(),
        ).param("id", id.value)
        .query(UUID::class.java)
        .optional()
        .map(::NamespaceId)
        .orElse(null)
}
