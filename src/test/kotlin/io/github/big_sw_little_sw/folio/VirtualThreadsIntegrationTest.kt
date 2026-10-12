package io.github.big_sw_little_sw.folio

import io.github.big_sw_little_sw.testing.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.TaskScheduler
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** This test uses a real port, because MockMvc runs handlers on the test thread. */
@FolioServerTest
class VirtualThreadsIntegrationTest(
    @Value("\${local.server.port}") private val serverPort: Int,
    @Autowired private val scheduler: TaskScheduler,
) {
    @Test
    fun `requests run on virtual threads`() {
        val response = httpGet(serverPort, "/api/test/thread")

        assertEquals("true", response.body())
    }

    @Test
    fun `scheduled tasks run on virtual threads`() {
        val virtual = CompletableFuture<Boolean>()

        scheduler.schedule({ virtual.complete(Thread.currentThread().isVirtual) }, Instant.now())

        assertTrue(virtual.get(5, TimeUnit.SECONDS))
    }
}
