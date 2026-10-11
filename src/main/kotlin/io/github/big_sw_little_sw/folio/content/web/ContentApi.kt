package io.github.big_sw_little_sw.folio.content.web

import io.github.big_sw_little_sw.folio.catalog.CONFIG_SET_ID
import io.github.big_sw_little_sw.folio.content.RevisionId
import io.github.big_sw_little_sw.folio.content.internal.Cached
import io.github.big_sw_little_sw.folio.content.internal.ContentService
import io.github.big_sw_little_sw.folio.content.internal.RevisionSelector
import io.github.big_sw_little_sw.folio.identity.CurrentCaller
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.ServletWebRequest

/**
 * The consumption routes of content (design 12.1). The catalog serves `:resolve`. Anonymous callers can get to these
 * routes, so that `public` rules apply. The service authorizes each request.
 *
 * Each response has an `ETag` and a `Cache-Control` header. For the JSON routes, Spring MVC answers a matching
 * `If-None-Match` with 304 after the service authorized. The file route asks Spring to compare `If-None-Match`
 * before it reads the content, so a 304 reads no content. Each route parses the ID, the path and the revision first,
 * so a malformed value gives status 400 before Folio finds the ConfigSet.
 */
@RestController
@RequestMapping("/api/v1/configsets/{id}")
class ContentApi(private val currentCaller: CurrentCaller, private val content: ContentService) {
    /**
     * Returns the ID, the path, the description and the latest revision of the ConfigSet. `latestRevision` is null
     * before the first publish.
     */
    @GetMapping
    fun metadata(@PathVariable id: String): ResponseEntity<MetadataResponse> {
        val cached = content.metadata(currentCaller.get(), CONFIG_SET_ID.parse(id))
        return cachedResponse(cached).body(cached.body.toResponse())
    }

    /** Returns the newest published revisions, newest first, with a maximum of 100. */
    @GetMapping("/revisions")
    fun revisions(@PathVariable id: String): ResponseEntity<RevisionsResponse> {
        val cached = content.revisions(currentCaller.get(), CONFIG_SET_ID.parse(id))
        return cachedResponse(cached).body(cached.body.toResponse())
    }

    /** Returns the ConfigItems at the revision, with their sizes in bytes. */
    @GetMapping("/files")
    fun files(
        @PathVariable id: String,
        @RequestParam(defaultValue = RevisionId.LATEST) revision: String,
    ): ResponseEntity<FilesResponse> {
        val configSet = CONFIG_SET_ID.parse(id)
        val cached = content.items(currentCaller.get(), configSet, RevisionSelector.parse(revision))
        return cachedResponse(cached)
            .header(REVISION_HEADER, cached.body.revision.value)
            .body(cached.body.toResponse())
    }

    /**
     * Returns the raw bytes of one ConfigItem. Folio never changes the bytes, also when they do not parse (design 13).
     * The response has the media type of the extension and the validation status. The path is relative to the root of
     * the ConfigSet and must be in normal form.
     */
    @GetMapping("/files/{*path}")
    @Operation(
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "The raw bytes of the ConfigItem.",
                content = [Content(mediaType = "*/*", schema = Schema(type = "string", format = "binary"))],
                headers = [
                    Header(name = HttpHeaders.ETAG, description = "The hash of the content, such as the Git blob ID."),
                    Header(name = HttpHeaders.CACHE_CONTROL, description = "The cache policy of the revision."),
                    Header(name = REVISION_HEADER, description = "The exact revision of the file."),
                    Header(
                        name = VALIDATION_HEADER,
                        description = "VALID, INVALID or UNKNOWN.",
                        schema = Schema(type = "string", allowableValues = ["VALID", "INVALID", "UNKNOWN"]),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "304",
                description = "The If-None-Match header names the ETag of the file.",
                headers = [
                    Header(name = HttpHeaders.ETAG),
                    Header(name = HttpHeaders.CACHE_CONTROL),
                    Header(name = REVISION_HEADER),
                ],
            ),
        ],
    )
    fun file(
        @PathVariable id: String,
        @Parameter(description = "The path of the file in the ConfigSet. It can contain '/'.")
        @PathVariable path: String,
        @RequestParam(defaultValue = RevisionId.LATEST) revision: String,
        request: ServletWebRequest,
        response: HttpServletResponse,
    ) {
        val configSet = CONFIG_SET_ID.parse(id)
        val itemPath = itemPathOf(path)
        content.read(currentCaller.get(), configSet, itemPath, RevisionSelector.parse(revision)) { read ->
            // Spring compares If-None-Match in the same way as for the JSON routes. Spring also sets the ETag and the
            // 304 status. A match ends the request before the read of the content, so a 304 reads no content.
            if (request.checkNotModified(read.item.etag)) {
                response.setHeader(HttpHeaders.CACHE_CONTROL, read.cacheControl)
                response.setHeader(REVISION_HEADER, read.revision.value)
            } else {
                writeRaw(response, read) {
                    response.setHeader(HttpHeaders.CACHE_CONTROL, read.cacheControl)
                    response.setHeader(REVISION_HEADER, read.revision.value)
                    response.setHeader(VALIDATION_HEADER, read.content.validation.name)
                }
            }
        }
    }

    private fun cachedResponse(cached: Cached<*>): ResponseEntity.BodyBuilder = ResponseEntity
        .ok()
        .eTag(cached.etag)
        .header(HttpHeaders.CACHE_CONTROL, cached.cacheControl)

    companion object {
        const val REVISION_HEADER = "X-Folio-Revision"
        const val VALIDATION_HEADER = "X-Folio-Validation-Status"
    }
}
