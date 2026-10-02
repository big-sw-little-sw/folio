package io.github.big_sw_little_sw.folio.sync.internal

import io.github.big_sw_little_sw.folio.configset.ConfigSetId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Requests sync for due ConfigSets on each poll and runs the fetches on a bounded executor (ADR 0032). It claims only
 * as many ConfigSets as there are free fetch slots, so a claimed ConfigSet never waits in a queue while its lease
 * runs out, and the rest stay claimable by other instances. It never claims a ConfigSet it is still syncing.
 */
@Component
class SyncPoller(
    private val synchronizer: Synchronizer,
    private val properties: SyncProperties,
) : DisposableBean {
    private val slots = Semaphore(properties.maxConcurrentFetches)
    private val running = ConcurrentHashMap<ConfigSetId, SyncLease>()
    private val executor: ExecutorService =
        Executors.newFixedThreadPool(
            properties.maxConcurrentFetches,
            Thread
                .ofPlatform()
                .name("folio-sync-", 0)
                // Leases are released on shutdown, so a sync still running must not keep the JVM alive.
                .daemon()
                // The exception's message may quote anything; its type is enough to start looking.
                .uncaughtExceptionHandler { _, e -> log.error("A sync failed unexpectedly: {}", e.javaClass.name) }
                .factory(),
        )

    /** Called by the scheduler; one poll at a time. */
    fun poll() {
        synchronizer.claimDue(slots.availablePermits(), running.keys.toSet()).forEach { lease ->
            // Only polls take slots, and a poll claims no more than are free, so this does not block.
            slots.acquire()
            running[lease.configSetId] = lease
            executor.execute { sync(lease) }
        }
    }

    /**
     * An unexpected exception leaves no attempt recorded. The lease is released so the ConfigSet is due again after
     * the interval rather than after the lease expires; there is no failure code to record and no backoff. The
     * exception itself reaches the thread's handler, which logs its type: catching it here would need a catch-all.
     */
    private fun sync(lease: SyncLease) {
        var finished = false
        try {
            synchronizer.sync(lease)
            finished = true
        } finally {
            if (!finished) {
                log.error("Sync of ConfigSet {} failed unexpectedly", lease.configSetId.value)
                synchronizer.release(lease, properties.interval)
            }
            running.remove(lease.configSetId, lease)
            slots.release()
        }
    }

    /**
     * Lets running syncs finish for a while, then releases the leases of those still running, due at once, before
     * interrupting them. A sync cut short by shutdown then cannot record a failure, and no backoff follows.
     */
    override fun destroy() {
        executor.shutdown()
        if (executor.awaitTermination(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS)) return
        running.values.forEach { synchronizer.release(it, Duration.ZERO) }
        executor.shutdownNow()
    }

    private companion object {
        val log = LoggerFactory.getLogger(SyncPoller::class.java)
        val SHUTDOWN_GRACE: Duration = Duration.ofSeconds(10)
    }
}
