package io.github.big_sw_little_sw.folio.namespace

import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class NamespaceServiceIntegrationTest(
    @Autowired private val service: NamespaceService,
    @Autowired private val jdbc: JdbcClient,
) {
    @BeforeEach
    fun deleteAllNamespaces() {
        jdbc.sql("delete from namespace_closure").update()
        jdbc.sql("delete from namespace").update()
    }

    @Test
    fun `creates root and nested namespaces with their ancestors root first`() {
        val engineering = create(null, "engineering")
        val ai = create(engineering, "ai")
        val hive = create(ai, "hive")

        assertEquals(Namespace(hive.id, ai.id, Slug("hive")), service.get(hive.id))
        assertEquals(listOf(engineering, ai), service.ancestors(hive.id))
        assertEquals(emptyList(), service.ancestors(engineering.id))
        assertClosureMatchesParents()
    }

    @Test
    fun `lists root namespaces and children ordered by slug`() {
        val b = create(null, "b")
        val a = create(null, "a")
        val child2 = create(a, "y")
        val child1 = create(a, "x")

        assertEquals(listOf(a, b), service.children(null))
        assertEquals(listOf(child1, child2), service.children(a.id))
        assertEquals(emptyList(), service.children(b.id))
    }

    @Test
    fun `rejects a duplicate slug among root namespaces`() {
        create(null, "engineering")

        assertFailsWith<DuplicateSlugException> { create(null, "engineering") }
    }

    @Test
    fun `rejects a duplicate slug among nested siblings`() {
        val engineering = create(null, "engineering")
        create(engineering, "ai")

        assertFailsWith<DuplicateSlugException> { create(engineering, "ai") }
    }

    @Test
    fun `allows the same slug under different parents`() {
        val development = create(null, "development")
        val production = create(null, "production")

        create(development, "service-a")
        create(production, "service-a")

        assertEquals(1, service.children(development.id).size)
        assertEquals(1, service.children(production.id).size)
    }

    @Test
    fun `rejects creating under a missing parent`() {
        val missing = NamespaceId(UUID.randomUUID())

        val failure = assertFailsWith<NamespaceNotFoundException> { service.create(missing, Slug("a")) }
        assertEquals(missing, failure.id)
    }

    @Test
    fun `rename changes the slug but keeps the ID and the tree`() {
        val engineering = create(null, "engineering")
        val ai = create(engineering, "ai")
        val hive = create(ai, "hive")

        val renamed = service.rename(ai.id, Slug("ml"))

        assertEquals(Namespace(ai.id, engineering.id, Slug("ml")), renamed)
        assertEquals(listOf(engineering, renamed), service.ancestors(hive.id))
        assertClosureMatchesParents()
    }

    @Test
    fun `a renamed slug can be reused by a sibling`() {
        val old = create(null, "old")
        service.rename(old.id, Slug("new"))

        create(null, "old")

        assertEquals(listOf(Slug("new"), Slug("old")), service.children(null).map { it.slug })
    }

    @Test
    fun `rejects renaming to a sibling's slug`() {
        create(null, "a")
        val b = create(null, "b")

        assertFailsWith<DuplicateSlugException> { service.rename(b.id, Slug("a")) }
        assertEquals(Slug("b"), service.get(b.id).slug)
    }

    @Test
    fun `move re-links the whole subtree under the new parent`() {
        val engineering = create(null, "engineering")
        val ai = create(engineering, "ai")
        val hive = create(ai, "hive")
        val production = create(hive, "production")
        val platform = create(null, "platform")

        val moved = service.move(ai.id, platform.id)

        assertEquals(Namespace(ai.id, platform.id, Slug("ai")), moved)
        assertEquals(listOf(platform, moved, hive), service.ancestors(production.id))
        assertEquals(emptyList(), service.children(engineering.id))
        assertClosureMatchesParents()
    }

    @Test
    fun `move to the root detaches the subtree from its old ancestors`() {
        val engineering = create(null, "engineering")
        val ai = create(engineering, "ai")
        val hive = create(ai, "hive")

        service.move(ai.id, null)

        assertEquals(listOf(service.get(ai.id)), service.ancestors(hive.id))
        assertEquals(listOf(Slug("ai"), Slug("engineering")), service.children(null).map { it.slug })
        assertClosureMatchesParents()
    }

    @Test
    fun `rejects moving a namespace into itself`() {
        val ai = create(null, "ai")

        assertFailsWith<NamespaceMoveIntoOwnSubtreeException> { service.move(ai.id, ai.id) }
    }

    @Test
    fun `rejects moving a namespace into its own subtree`() {
        val engineering = create(null, "engineering")
        val ai = create(engineering, "ai")
        val hive = create(ai, "hive")

        assertFailsWith<NamespaceMoveIntoOwnSubtreeException> { service.move(engineering.id, hive.id) }
        assertEquals(null, service.get(engineering.id).parentId)
        assertClosureMatchesParents()
    }

    @Test
    fun `rejects a move that duplicates a slug under the new parent and leaves the tree unchanged`() {
        val development = create(null, "development")
        val production = create(null, "production")
        create(development, "service-a")
        val productionService = create(production, "service-a")

        assertFailsWith<DuplicateSlugException> { service.move(productionService.id, development.id) }
        assertEquals(production.id, service.get(productionService.id).parentId)
        assertClosureMatchesParents()
    }

    @Test
    fun `moving under the current parent changes nothing`() {
        val engineering = create(null, "engineering")
        val ai = create(engineering, "ai")
        create(ai, "hive")
        val before = closureRows()

        assertEquals(ai, service.move(ai.id, engineering.id))
        assertEquals(before, closureRows())
    }

    @Test
    fun `moving a root namespace to the root changes nothing`() {
        val engineering = create(null, "engineering")
        create(engineering, "ai")
        val before = closureRows()

        assertEquals(engineering, service.move(engineering.id, null))
        assertEquals(before, closureRows())
    }

    @Test
    fun `rejects a move to the root that duplicates a root slug and leaves the tree unchanged`() {
        create(null, "ai")
        val engineering = create(null, "engineering")
        val nestedAi = create(engineering, "ai")
        val before = closureRows()

        assertFailsWith<DuplicateSlugException> { service.move(nestedAi.id, null) }
        assertEquals(engineering.id, service.get(nestedAi.id).parentId)
        assertEquals(before, closureRows())
    }

    @Test
    fun `rejects moving to a missing parent`() {
        val ai = create(null, "ai")

        assertFailsWith<NamespaceNotFoundException> { service.move(ai.id, NamespaceId(UUID.randomUUID())) }
    }

    @Test
    fun `delete removes an empty namespace and its closure rows`() {
        val engineering = create(null, "engineering")
        val ai = create(engineering, "ai")

        service.delete(ai.id)

        assertFailsWith<NamespaceNotFoundException> { service.get(ai.id) }
        assertEquals(emptyList(), service.children(engineering.id))
        assertClosureMatchesParents()
    }

    @Test
    fun `rejects deleting a namespace that has child namespaces`() {
        val engineering = create(null, "engineering")
        val ai = create(engineering, "ai")

        assertFailsWith<NamespaceNotEmptyException> { service.delete(engineering.id) }
        assertEquals(listOf(ai), service.children(engineering.id))
    }

    @Test
    fun `rejects operations on a missing namespace`() {
        val missing = NamespaceId(UUID.randomUUID())

        assertFailsWith<NamespaceNotFoundException> { service.get(missing) }
        assertFailsWith<NamespaceNotFoundException> { service.rename(missing, Slug("a")) }
        assertFailsWith<NamespaceNotFoundException> { service.move(missing, null) }
        assertFailsWith<NamespaceNotFoundException> { service.delete(missing) }
        assertFailsWith<NamespaceNotFoundException> { service.children(missing) }
        assertFailsWith<NamespaceNotFoundException> { service.ancestors(missing) }
    }

    @Test
    fun `concurrent crossing moves never create a cycle`() {
        repeat(20) {
            deleteAllNamespaces()
            val a = create(null, "a")
            val b = create(null, "b")
            val barrier = CyclicBarrier(2)

            val results =
                Executors.newFixedThreadPool(2).use { executor ->
                    listOf(a to b, b to a)
                        .map { (moved, parent) ->
                            executor.submit<Result<Namespace>> {
                                barrier.await()
                                runCatching { service.move(moved.id, parent.id) }
                            }
                        }.map { it.get() }
                }

            assertEquals(1, results.count { it.isSuccess })
            assertIs<NamespaceMoveIntoOwnSubtreeException>(results.single { it.isFailure }.exceptionOrNull())
            assertClosureMatchesParents()
        }
    }

    private fun create(
        parent: Namespace?,
        slug: String,
    ): Namespace = service.create(parent?.id, Slug(slug))

    /** The closure table must hold exactly the paths that follow from the parent links. */
    private fun assertClosureMatchesParents() {
        val expected =
            jdbc
                .sql(
                    """
                    with recursive paths (ancestor_id, descendant_id, depth) as (
                        select id, id, 0 from namespace
                        union all
                        select p.ancestor_id, n.id, p.depth + 1
                        from paths p join namespace n on n.parent_id = p.descendant_id
                        where p.depth < 100
                    )
                    select ancestor_id, descendant_id, depth from paths
                    """.trimIndent(),
                ).query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getInt(3)) }
                .set()

        assertEquals(expected, closureRows())
    }

    private fun closureRows(): Set<Triple<String, String, Int>> =
        jdbc
            .sql("select ancestor_id, descendant_id, depth from namespace_closure")
            .query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getInt(3)) }
            .set()
}
