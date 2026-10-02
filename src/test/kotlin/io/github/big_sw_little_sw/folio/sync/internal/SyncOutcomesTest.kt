package io.github.big_sw_little_sw.folio.sync.internal

import io.github.big_sw_little_sw.folio.configset.ConfigSetId
import io.github.big_sw_little_sw.folio.configset.ConfigSetPath
import io.github.big_sw_little_sw.folio.namespace.Slug
import io.github.big_sw_little_sw.folio.source.SourceFailure
import io.github.big_sw_little_sw.folio.sync.SyncEvent
import io.github.big_sw_little_sw.folio.sync.SyncFailed
import io.github.big_sw_little_sw.folio.sync.SyncRecovered
import io.github.big_sw_little_sw.folio.sync.SyncRevisionChanged
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncOutcomesTest {
    @Test
    fun `the first sync records its revision`() {
        assertEquals(SyncRevisionChanged(ID, PATH, A, null), event(PreviousSync(null, null), fetched(A)))
        assertEquals(AttemptOutcome.SYNCED, attemptOutcome(PreviousSync(null, null), fetched(A)))
    }

    @Test
    fun `a new revision is recorded with the previous one`() {
        assertEquals(SyncRevisionChanged(ID, PATH, B, A), event(PreviousSync(A, null), fetched(B)))
        assertEquals(AttemptOutcome.SYNCED, attemptOutcome(PreviousSync(A, null), fetched(B)))
    }

    @Test
    fun `an unchanged success records nothing and does not read the path`() {
        assertNull(outcomeEvent(ID, PreviousSync(A, null), fetched(A)) { error("path read") })
        assertEquals(AttemptOutcome.UNCHANGED, attemptOutcome(PreviousSync(A, null), fetched(A)))
    }

    @Test
    fun `the first failure is recorded`() {
        assertEquals(SyncFailed(ID, PATH, "AUTH_FAILED", null), event(PreviousSync(A, null), failed(AUTH)))
        assertEquals(AttemptOutcome.FAILED, attemptOutcome(PreviousSync(A, null), failed(AUTH)))
    }

    @Test
    fun `a failure with another code is recorded, the same failure again is not`() {
        val previous = PreviousSync(A, "AUTH_FAILED")

        assertEquals(
            SyncFailed(ID, PATH, "UNREACHABLE", "AUTH_FAILED"),
            event(previous, failed(SourceFailure.UNREACHABLE)),
        )
        assertNull(event(previous, failed(AUTH)))
    }

    @Test
    fun `a success after failures is a recovery, with or without a new revision`() {
        val previous = PreviousSync(A, "AUTH_FAILED")

        assertEquals(SyncRecovered(ID, PATH, A, A, "AUTH_FAILED"), event(previous, fetched(A)))
        assertEquals(SyncRecovered(ID, PATH, B, A, "AUTH_FAILED"), event(previous, fetched(B)))
        assertEquals(AttemptOutcome.UNCHANGED, attemptOutcome(previous, fetched(A)))
    }

    private fun event(
        previous: PreviousSync,
        result: FetchResult,
    ): SyncEvent? = outcomeEvent(ID, previous, result) { PATH }

    private fun fetched(revision: String) = FetchResult.Fetched(revision)

    private fun failed(failure: SourceFailure) = FetchResult.Failed(failure)

    private companion object {
        val ID = ConfigSetId(UUID.randomUUID())
        val PATH = ConfigSetPath(listOf(Slug("production")), Slug("app"))
        val A = "a".repeat(40)
        val B = "b".repeat(40)
        val AUTH = SourceFailure.AUTH_FAILED
    }
}
