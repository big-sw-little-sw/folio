package io.github.big_sw_little_sw.folio

import io.github.big_sw_little_sw.testing.FolioServerTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.TaskScheduler
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Requests and scheduled tasks run on virtual threads (design 8.7). MockMvc runs handlers on the test thread. */
@FolioServerTest
class VirtualThreadsIntegrationTest(
    @Value("\${local.server.port}") private val serverPort: Int,
    @Autowired private val scheduler: TaskScheduler,
) {
    @Test
    fun `requests run on virtual threads`() {
        val response =
            HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:$serverPort/api/test/thread")).build(),
                HttpResponse.BodyHandlers.ofString(),
            )

        assertEquals("true", response.body())
    }

    @Test
    fun `scheduled tasks run on virtual threads`() {
        val virtual = CompletableFuture<Boolean>()

        scheduler.schedule({ virtual.complete(Thread.currentThread().isVirtual) }, Instant.now())

        assertTrue(virtual.get(5, TimeUnit.SECONDS))
    }
}
