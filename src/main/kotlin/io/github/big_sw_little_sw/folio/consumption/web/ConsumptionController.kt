package io.github.big_sw_little_sw.folio.consumption.web

import io.github.big_sw_little_sw.folio.configset.toApiId
import io.github.big_sw_little_sw.folio.configset.toConfigSetId
import io.github.big_sw_little_sw.folio.consumption.Cacheable
import io.github.big_sw_little_sw.folio.consumption.ConsumptionService
import io.github.big_sw_little_sw.folio.consumption.RevisionSelector
import io.github.big_sw_little_sw.folio.source.SourcePath
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/** [latestRevision] is the commit ID served as `latest`, null until the first sync. */
data class ConfigSetMetadataResponse(
    val id: String,
    val path: String,
    val latestRevision: String?,
)

data class FileListingResponse(
    val revision: String,
    val files: List<FileResponse>,
)

/** [path] is relative to the ConfigSet's root path; [size] is in bytes. */
data class FileResponse(
    val path: String,
    val size: Long,
)

/** Newest first; the first is `latest`. */
data class RevisionListingResponse(
    val revisions: List<RevisionResponse>,
)

/** [id] is the commit ID; [syncedAt] is when it last became the synced revision. */
data class RevisionResponse(
    val id: String,
    val syncedAt: Instant,
)

/**
 * The consumption API (design 16), outside the admin API. Anonymous callers reach it, so that `public` rules apply;
 * the service authorizes every request (ADR 0035). Responses carry an `ETag`, and Spring answers a matching
 * `If-None-Match` with 304 and the same headers but no body.
 */
@RestController
@RequestMapping("/api/v1/configsets/{id}")
class ConsumptionController(
    private val consumption: ConsumptionService,
) {
    @GetMapping
    fun metadata(
        @PathVariable id: String,
    ): ResponseEntity<ConfigSetMetadataResponse> {
        val served = consumption.metadata(id.toConfigSetId())
        val metadata = served.body
        return cached(served).body(
            ConfigSetMetadataResponse(metadata.id.toApiId(), metadata.path.toString(), metadata.latestRevision),
        )
    }

    @GetMapping("/files")
    fun files(
        @PathVariable id: String,
        @RequestParam(defaultValue = RevisionSelector.LATEST) revision: String,
    ): ResponseEntity<FileListingResponse> {
        val served = consumption.list(id.toConfigSetId(), RevisionSelector.parse(revision))
        val listing = served.body
        return cached(served)
            .header(REVISION_HEADER, listing.revision)
            .body(FileListingResponse(listing.revision, listing.files.map { FileResponse(it.path.value, it.size) }))
    }

    /**
     * The raw bytes, never transformed (design 15.5). Known formats get their media type, anything else
     * `application/octet-stream`; Spring Security adds `X-Content-Type-Options: nosniff` to every response.
     */
    @GetMapping("/files/{*path}")
    fun file(
        @PathVariable id: String,
        @PathVariable path: String,
        @RequestParam(defaultValue = RevisionSelector.LATEST) revision: String,
    ): ResponseEntity<ByteArray> {
        // The wildcard keeps the leading slash; the rest must already be a normal relative path (ADR 0024).
        val served =
            consumption.read(
                id.toConfigSetId(),
                SourcePath.parse(path.removePrefix("/")),
                RevisionSelector.parse(revision),
            )
        val content = served.body
        return cached(served)
            .header(REVISION_HEADER, content.revision)
            .header(VALIDATION_HEADER, content.validation.name)
            .contentType(
                content.format?.let { MediaType.parseMediaType(it.mediaType) } ?: MediaType.APPLICATION_OCTET_STREAM,
            ).body(content.bytes)
    }

    @GetMapping("/revisions")
    fun revisions(
        @PathVariable id: String,
    ): ResponseEntity<RevisionListingResponse> {
        val served = consumption.revisions(id.toConfigSetId())
        return cached(
            served,
        ).body(RevisionListingResponse(served.body.map { RevisionResponse(it.commitId, it.syncedAt) }))
    }

    private fun cached(served: Cacheable<*>): ResponseEntity.BodyBuilder =
        ResponseEntity
            .ok()
            .eTag(served.etag)
            .header(HttpHeaders.CACHE_CONTROL, served.cache.headerValue)

    private companion object {
        const val REVISION_HEADER = "X-Config-Revision"
        const val VALIDATION_HEADER = "X-Config-Validation-Status"
    }
}
