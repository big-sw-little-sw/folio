package io.github.big_sw_little_sw.folio.sync.web

import io.github.big_sw_little_sw.folio.configset.toApiId
import io.github.big_sw_little_sw.folio.configset.toConfigSetId
import io.github.big_sw_little_sw.folio.sync.SyncService
import io.github.big_sw_little_sw.folio.sync.SyncState
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/** See [SyncState]. Revisions are commit IDs; [errorCode] and [errorSummary] are set while the last attempt failed. */
data class SyncStateResponse(
    val configSetId: String,
    val lastSeenRevision: String?,
    val lastSyncedRevision: String?,
    val lastAttemptAt: Instant?,
    val lastSuccessAt: Instant?,
    val errorCode: String?,
    val errorSummary: String?,
    val consecutiveFailures: Int,
    val nextDueAt: Instant,
)

@RestController
@RequestMapping("/api/v1/admin/configsets")
class SyncController(
    private val syncs: SyncService,
) {
    /** Requests a sync; it runs on the next poll, not within this request. */
    @PostMapping("/{id}:sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun request(
        @PathVariable id: String,
    ): SyncStateResponse = syncs.request(id.toConfigSetId()).toResponse()

    @GetMapping("/{id}/sync")
    fun state(
        @PathVariable id: String,
    ): SyncStateResponse = syncs.state(id.toConfigSetId()).toResponse()

    private fun SyncState.toResponse() =
        SyncStateResponse(
            configSetId.toApiId(),
            lastSeenRevision,
            lastSyncedRevision,
            lastAttemptAt,
            lastSuccessAt,
            errorCode,
            errorSummary,
            consecutiveFailures,
            nextDueAt,
        )
}
