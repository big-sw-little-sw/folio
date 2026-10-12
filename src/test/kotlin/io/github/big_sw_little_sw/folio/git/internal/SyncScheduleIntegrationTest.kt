package io.github.big_sw_little_sw.folio.git.internal

import io.github.big_sw_little_sw.testing.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import java.time.Duration
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.test.*

/**
 * These tests check the schedule of sync. The test context removes the schedule ([NoSyncSchedule]), so the tests build
 * it from the beans and examine its tasks.
 */
@GitIntegrationTest
class SyncScheduleIntegrationTest(
    @Autowired private val context: ApplicationContext,
    @Autowired private val poller: SyncPoller,
    @Autowired private val cache: RepositoryCache,
    @Autowired private val syncRepository: SyncRepository,
    @Autowired private val properties: GitProperties,
) {
    /** Without the removal, a scheduled poll would claim the ConfigSets of a test while the test runs. */
    @Test
    fun `the test context has no schedule`() {
        assertEquals(emptyList(), context.getBeanNamesForType(SyncSchedule::class.java).toList())
    }

    @Test
    fun `the schedule polls every poll interval and sweeps every sync interval, each first after one delay`() {
        val registrar = ScheduledTaskRegistrar()

        SyncSchedule(poller, cache, syncRepository, SyncProperties()).configureTasks(registrar)

        val (poll, sweep) = registrar.fixedDelayTaskList
        assertEquals(listOf(POLL_INTERVAL, POLL_INTERVAL), listOf(poll.intervalDuration, poll.initialDelayDuration))
        assertEquals(listOf(SYNC_INTERVAL, SYNC_INTERVAL), listOf(sweep.intervalDuration, sweep.initialDelayDuration))
        val orphan = properties.cacheDirectory.resolve(UUID.randomUUID().toString()).createDirectories()
        sweep.runnable.run()
        assertFalse(orphan.exists())
    }

    private companion object {
        /** The defaults of `folio.sync.poll-interval` and `folio.sync.interval`. */
        val POLL_INTERVAL: Duration = Duration.ofSeconds(10)
        val SYNC_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}
