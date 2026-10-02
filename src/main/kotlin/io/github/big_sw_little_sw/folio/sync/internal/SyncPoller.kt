package io.github.big_sw_little_sw.folio.sync.internal

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/**
 * Requests sync for due ConfigSets on each poll and runs the fetches on a bounded executor (ADR 0032). It claims only
 * as many ConfigSets as there are free fetch slots, so a claimed ConfigSet never waits in a queue while its lease
 * runs out, and the rest stay claimable by other instances.
 */
@Component
class SyncPoller(
    private val synchronizer: Synchronizer,
    properties: SyncProperties,
) : DisposableBean {
    private val slots = Semaphore(properties.maxConcurrentFetches)
    private val executor: ExecutorService =
        Executors.newFixedThreadPool(
            properties.maxConcurrentFetches,
            Thread
                .ofPlatform()
                .name("folio-sync-", 0)
                // The exception's message may quote anything; its type is enough to start looking.
                .uncaughtExceptionHandler { _, e -> log.error("A sync failed unexpectedly: {}", e.javaClass.name) }
                .factory(),
        )

    /** Called by the scheduler; one poll at a time. */
    fun poll() {
        synchronizer.claimDue(slots.availablePermits()).forEach { lease ->
            // Only polls take slots, and a poll claims no more than are free, so this does not block.
            slots.acquire()
            executor.execute {
                try {
                    synchronizer.sync(lease)
                } finally {
                    slots.release()
                }
            }
        }
    }

    override fun destroy() {
        executor.shutdownNow()
    }

    private companion object {
        val log = LoggerFactory.getLogger(SyncPoller::class.java)
    }
}
