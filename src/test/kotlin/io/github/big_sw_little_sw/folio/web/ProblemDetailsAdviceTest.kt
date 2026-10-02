package io.github.big_sw_little_sw.folio.web

import io.github.big_sw_little_sw.folio.consumption.TooManyFetchesException
import io.github.big_sw_little_sw.folio.source.SourceAccessFailedException
import io.github.big_sw_little_sw.folio.source.SourceBusyException
import io.github.big_sw_little_sw.folio.source.SourceFailure
import io.github.big_sw_little_sw.folio.source.SourceFileTooLargeException
import io.github.big_sw_little_sw.folio.source.SourcePath
import org.springframework.http.HttpHeaders
import kotlin.test.Test
import kotlin.test.assertEquals

class ProblemDetailsAdviceTest {
    private val advice = ProblemDetailsAdvice()

    @Test
    fun `a fetch past its deadline is a gateway timeout, other fetch failures a bad gateway, both with the code`() {
        val timeout = advice.source(SourceAccessFailedException(SourceFailure.DEADLINE_EXCEEDED))
        val failed = advice.source(SourceAccessFailedException(SourceFailure.AUTH_FAILED))

        assertEquals(504, timeout.statusCode.value())
        assertEquals("DEADLINE_EXCEEDED", timeout.body?.properties?.get("code"))
        assertEquals(SourceFailure.DEADLINE_EXCEEDED.summary, timeout.body?.detail)
        assertEquals(502, failed.statusCode.value())
        assertEquals("AUTH_FAILED", failed.body?.properties?.get("code"))
    }

    @Test
    fun `a fetch that cannot run now is 503 with Retry-After`() {
        listOf(advice.source(SourceBusyException()), advice.consumption(TooManyFetchesException())).forEach {
            assertEquals(503, it.statusCode.value())
            assertEquals("5", it.headers.getFirst(HttpHeaders.RETRY_AFTER))
        }
    }

    @Test
    fun `a file too large to serve is 422`() {
        val response = advice.source(SourceFileTooLargeException(SourcePath.parse("big.bin"), 10))

        assertEquals(422, response.statusCode.value())
    }
}
