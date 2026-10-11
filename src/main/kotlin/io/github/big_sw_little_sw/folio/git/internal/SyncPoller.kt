package io.github.big_sw_little_sw.folio.git.internal

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * Starts the syncs of due ConfigSets on each poll (design 10.6, 8.7). Each sync runs on its own virtual thread, and the
 * fetch semaphore limits the syncs of this instance to `folio.sync.max-concurrent-fetches`. A poll claims only as
 * many ConfigSets as the semaphore has free permits. Thus a claimed ConfigSet never waits in a queue while its lease
 * runs, and other instances can claim the other due ConfigSets. A poll never claims a ConfigSet that this instance
 * syncs now.
 */
@Component
class SyncPoller(private val synchronizer: Synchronizer, private val properties: SyncProperties) {
    private val fetches = Semaphore(properties.maxConcurrentFetches)
    private val running = ConcurrentHashMap<ConfigSetId, Running>()

    @Volatile
    private var stopped = false

    /** [SyncSchedule] calls this method on one thread, so polls run one at a time. */
    fun poll() {
        if (stopped) return
        synchronizer.claim(fetches.availablePermits(), running.keys.toSet()).forEach(::start)
    }

    /** Waits a maximum of [timeout] for the running syncs to end. Returns true if no sync runs. */
    fun awaitSyncs(timeout: Duration): Boolean {
        val end = System.nanoTime() + timeout.toNanos()
        running.values.forEach { it.thread.join(Duration.ofNanos(maxOf(0L, end - System.nanoTime()))) }
        return running.isEmpty()
    }

    /**
     * Stops the poller when the application stops (design 10.6). The poller waits [SHUTDOWN_WAIT] for the running
     * syncs. Then it releases the leases of the remaining syncs, so the ConfigSets are due immediately on another
     * instance, and interrupts the syncs. A sync that loses its lease records nothing, so no failure and no backoff
     * follow. An interrupted sync logs one `INFO` line, not an error.
     */
    @PreDestroy
    fun stop() {
        stopped = true
        if (awaitSyncs(SHUTDOWN_WAIT)) return
        running.values.forEach {
            synchronizer.release(it.lease, Duration.ZERO)
            it.thread.interrupt()
        }
    }

    private fun start(lease: SyncLease) {
        // Only polls take permits, and a poll claims no more ConfigSets than the free permits. Thus this branch runs
        // only when the poller stopped during the poll. The release makes the ConfigSet due for another instance.
        if (stopped || !fetches.tryAcquire()) {
            synchronizer.release(lease, Duration.ZERO)
            return
        }
        val id = lease.configSet.toApiId()
        val thread =
            Thread
                .ofVirtual()
                .name("folio-sync-$id")
                // The message of an exception can contain server output, so the log gives only the exception types
                // (design 10.4).
                .uncaughtExceptionHandler { _, e -> logEnd(id, e) }
                .unstarted { run(lease) }
        running[lease.configSet] = Running(lease, thread)
        thread.start()
    }

    /**
     * Runs one sync. An unexpected error records no attempt (design 10.6). The poller releases the lease, and the
     * ConfigSet is due after one interval. Thus a defect does not cause a sync on each poll. If [stop] already
     * released the lease, the release changes nothing. The error then goes to the handler of the thread, which logs
     * its types ([logEnd]).
     *
     * The method always releases the permit and removes the entry from [running]. Without this rule, each failed sync
     * would keep one permit, and the instance would slowly stop its syncs.
     */
    private fun run(lease: SyncLease) {
        var finished = false
        try {
            synchronizer.sync(lease)
            finished = true
        } finally {
            try {
                if (!finished) synchronizer.release(lease, properties.interval)
            } finally {
                running.remove(lease.configSet)
                fetches.release()
            }
        }
    }

    /**
     * Logs a sync that ended with an exception. After [stop], the exception is usually the interrupt of [stop], for
     * example an `InterruptedException` or a wrapper of it. A shutdown is normal, so the line is `INFO` and does not
     * alert. The test is [stopped] and not the interrupt status, because an `InterruptedException` clears the status.
     */
    private fun logEnd(id: String, e: Throwable) {
        if (stopped) {
            log.info("The sync of ConfigSet {} stopped at shutdown. Exception types: {}", id, exceptionTypes(e))
        } else {
            log.error("The sync of ConfigSet {} failed unexpectedly. Exception types: {}", id, exceptionTypes(e))
        }
    }

    private class Running(val lease: SyncLease, val thread: Thread)

    private companion object {
        val log = LoggerFactory.getLogger(SyncPoller::class.java)
        val SHUTDOWN_WAIT: Duration = Duration.ofSeconds(10)
    }
}
