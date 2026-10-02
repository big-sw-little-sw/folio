package io.github.big_sw_little_sw.folio.audit.internal

import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pruning audit records past the default retention of 90 days (ADR 0041). Test classes share the table, so each test
 * writes records under a resource ID of its own and counts only those.
 */
@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class AuditPruningIntegrationTest(
    @Autowired private val pruning: AuditPruning,
    @Autowired private val jdbc: JdbcClient,
) {
    private val resourceId: UUID = UUID.randomUUID()

    @Test
    fun `records older than the retention are deleted and newer ones kept`() {
        insert(count = 3, age = Duration.ofDays(91))
        insert(count = 2, age = Duration.ofDays(89))

        pruning.prune()

        assertEquals(mapOf(89 to 2), countsByAgeInDays())
    }

    @Test
    fun `a run deletes more old records than fit in one batch`() {
        insert(count = 2 * AuditPruning.BATCH_SIZE + 1, age = Duration.ofDays(365))
        insert(count = 1, age = Duration.ofDays(1))

        pruning.prune()

        assertEquals(mapOf(1 to 1), countsByAgeInDays())
    }

    @Test
    fun `concurrent runs together delete every old record and nothing else`() {
        insert(count = 3 * AuditPruning.BATCH_SIZE, age = Duration.ofDays(100))
        insert(count = 1, age = Duration.ofDays(1))

        listOf(CompletableFuture.supplyAsync(pruning::prune), CompletableFuture.supplyAsync(pruning::prune))
            .forEach { it.join() }

        assertEquals(mapOf(1 to 1), countsByAgeInDays())
    }

    private fun insert(
        count: Int,
        age: Duration,
    ) {
        jdbc
            .sql(
                """
                insert into audit_event (
                    occurred_at, actor_type, actor_super_admin, action, resource_type, resource_id, details
                )
                select now() - :ageMillis * interval '1 millisecond', 'SYSTEM', false, 'SYNC_FAILED', 'CONFIG_SET',
                    :resourceId, '{}'::jsonb
                from generate_series(1, :count)
                """.trimIndent(),
            ).param("ageMillis", age.toMillis())
            .param("resourceId", resourceId)
            .param("count", count)
            .update()
    }

    /** This test's remaining records, counted by their age in whole days. */
    private fun countsByAgeInDays(): Map<Int, Int> =
        jdbc
            .sql(
                """
                select extract(day from now() - occurred_at)::int as days, count(*)::int as records
                from audit_event where resource_id = :resourceId group by days
                """.trimIndent(),
            ).param("resourceId", resourceId)
            .query { rs, _ -> rs.getInt("days") to rs.getInt("records") }
            .list()
            .toMap()
}
