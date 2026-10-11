package io.github.big_sw_little_sw.folio.audit.internal

import io.github.big_sw_little_sw.testing.FolioIntegrationTest
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.config.IntervalTask
import org.springframework.scheduling.config.ScheduledTaskHolder
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Deletion of audit records after the default retention of 90 days (design 14). Test classes share the table, so each
 * test writes records under its own resource ID and counts only those.
 */
@FolioIntegrationTest
class AuditRetentionIntegrationTest(
    @Autowired private val retention: AuditRetention,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val dataSource: DataSource,
    @Autowired private val scheduledTasks: ScheduledTaskHolder,
) {
    private val resourceId: UUID = UUID.randomUUID()

    @Test
    fun `records older than the retention are deleted and newer ones kept`() {
        insert(count = 3, age = Duration.ofDays(91))
        insert(count = 2, age = Duration.ofDays(89))

        retention.deleteExpired()

        assertEquals(mapOf(89 to 2), countsByAgeInDays())
    }

    @Test
    fun `a run deletes more records than fit in one batch`() {
        insert(count = 2 * AuditRetention.BATCH_SIZE + 1, age = Duration.ofDays(365))
        insert(count = 1, age = Duration.ofDays(1))

        retention.deleteExpired()

        assertEquals(mapOf(1 to 1), countsByAgeInDays())
    }

    /** Only the end state. The test below shows that a run does not wait for another one. */
    @Test
    fun `parallel runs together delete each old record and nothing else`() {
        insert(count = 3 * AuditRetention.BATCH_SIZE, age = Duration.ofDays(100))
        insert(count = 1, age = Duration.ofDays(1))

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            List(2) { executor.submit(retention::deleteExpired) }.forEach { it.get() }
        }

        assertEquals(mapOf(1 to 1), countsByAgeInDays())
    }

    @Test
    fun `a run skips records that another transaction locked, and does not wait for it`() {
        insert(count = 20, age = Duration.ofDays(100))

        dataSource.connection.use { other ->
            other.autoCommit = false
            try {
                other
                    .prepareStatement("select id from audit_event where resource_id = ? limit 10 for update")
                    .use { lock ->
                        lock.setObject(1, resourceId)
                        lock.executeQuery().close()
                    }

                assertTimeoutPreemptively(Duration.ofSeconds(5)) { retention.deleteExpired() }

                assertEquals(mapOf(100 to 10), countsByAgeInDays())
            } finally {
                other.rollback()
            }
        }

        retention.deleteExpired()

        assertEquals(emptyMap(), countsByAgeInDays())
    }

    @Test
    fun `each instance deletes old records one time each day`() {
        val task = scheduledTasks.scheduledTasks.single { "AuditRetention.deleteExpired" in it.task.toString() }.task

        assertIs<IntervalTask>(task)
        assertEquals(Duration.ofDays(1), task.intervalDuration)
    }

    private fun insert(count: Int, age: Duration) {
        jdbc
            .sql(
                """
                insert into audit_event (
                    occurred_at, actor_type, actor_super_admin, operation, resource_type, resource_id, details
                )
                select now() - :ageSeconds * interval '1 second', 'SYSTEM', false, 'SYNC_FAILED', 'CONFIG_SET',
                    :resourceId, '{}'::jsonb
                from generate_series(1, :count)
                """.trimIndent(),
            ).param("ageSeconds", age.toSeconds())
            .param("resourceId", resourceId)
            .param("count", count)
            .update()
    }

    /** The remaining records of this test, counted by their age in whole days. */
    private fun countsByAgeInDays(): Map<Int, Int> = jdbc
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
