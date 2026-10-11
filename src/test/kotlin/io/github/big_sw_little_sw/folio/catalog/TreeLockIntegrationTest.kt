package io.github.big_sw_little_sw.folio.catalog

import io.github.big_sw_little_sw.folio.catalog.internal.NamespaceDetails
import io.github.big_sw_little_sw.folio.catalog.internal.NamespaceService
import io.github.big_sw_little_sw.folio.catalog.internal.Rule
import io.github.big_sw_little_sw.folio.catalog.internal.RuleService
import io.github.big_sw_little_sw.folio.catalog.internal.TreeRepository
import io.github.big_sw_little_sw.folio.identity.Subject
import io.github.big_sw_little_sw.testing.FolioIntegrationTest
import io.github.big_sw_little_sw.testing.TestCatalog
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * These tests check the tree lock (design 8.1). Each catalog write takes the tree lock first, so the checks of a write
 * and its change see the same tree. Each test holds the lock in a transaction while two writes start. Both writes then
 * wait, and they run one after the other when the lock is free.
 */
@FolioIntegrationTest
class TreeLockIntegrationTest(
    @Autowired private val namespaces: NamespaceService,
    @Autowired private val configSets: ConfigSets,
    @Autowired private val rules: RuleService,
    @Autowired private val tree: TreeRepository,
    @Autowired private val catalog: TestCatalog,
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
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val holder = executor.submit { holdLock(locked, release) }
            locked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)

            val read = executor.submit<NamespaceDetails> { namespaces.get(SUPER_ADMIN, top) }

            assertEquals(top, read.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).namespace.id)
            release.countDown()
            holder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    /**
     * Runs [writes] in parallel while another transaction holds the tree lock, and then releases the lock. Each task
     * blocks, so each task gets its own virtual thread. With a pool of platform threads, a write could wait for a free
     * thread and not for the lock, and the test would fail.
     */
    private fun whileLocked(vararg writes: () -> Any): List<Result<Any>> {
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        return Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val holder = executor.submit { holdLock(locked, release) }
            locked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val running = writes.map { write -> executor.submit<Result<Any>> { runCatching(write) } }
            waitForLockWaiters(writes.size)
            release.countDown()
            holder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            running.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        }
    }

    private fun holdLock(locked: CountDownLatch, release: CountDownLatch) {
        transactions.executeWithoutResult {
            tree.lock()
            locked.countDown()
            release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    /**
     * Waits until [count] writes wait for the advisory lock. Without this wait, the holder could release the lock
     * before a write asks for it.
     */
    private fun waitForLockWaiters(count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            val waiting =
                jdbc
                    .sql("select count(*) from pg_locks where locktype = 'advisory' and not granted")
                    .query(Int::class.java)
                    .single()
            if (waiting >= count) return
            Thread.sleep(POLL_MILLIS)
        }
        error("The writes did not wait for the tree lock")
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
        .query(java.util.UUID::class.java)
        .optional()
        .map(::NamespaceId)
        .orElse(null)

    private companion object {
        const val TIMEOUT_SECONDS = 20L
        const val POLL_MILLIS = 20L
    }
}
