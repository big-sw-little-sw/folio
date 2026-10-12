package io.github.big_sw_little_sw.folio.content.web

import io.github.big_sw_little_sw.folio.content.ItemPath
import io.github.big_sw_little_sw.folio.content.internal.ItemList
import io.github.big_sw_little_sw.folio.content.internal.ItemRead
import io.github.big_sw_little_sw.folio.content.internal.Metadata
import io.github.big_sw_little_sw.folio.content.internal.RevisionList
import jakarta.servlet.http.HttpServletResponse
import java.time.Instant

/** [latestRevision] is null if the ConfigSet has no published revision. */
data class MetadataResponse(val id: String, val path: String, val description: String?, val latestRevision: String?)

data class RevisionsResponse(val revisions: List<RevisionResponse>)

data class RevisionResponse(val id: String, val publishedAt: Instant)

data class FilesResponse(val revision: String, val files: List<FileResponse>)

/** [path] is relative to the root of the ConfigSet. [size] is in bytes. */
data class FileResponse(val path: String, val size: Long)

fun Metadata.toResponse() =
    MetadataResponse(configSet.id.toApiId(), configSet.path.toString(), configSet.description, latest?.value)

fun RevisionList.toResponse() = RevisionsResponse(revisions.map { RevisionResponse(it.id.value, it.publishedAt) })

fun ItemList.toResponse() = FilesResponse(revision.value, items.map { FileResponse(it.path.value, it.size) })

/**
 * Writes the raw bytes of a ConfigItem, for the API and for a UI download. [headers] sets the other headers of the
 * route before this function writes the content. `nosniff` stops a browser from guessing a type other than the media
 * type. Thus a browser never runs a file as HTML or as a script.
 *
 * If the read fails before the response commits, this function resets the response. Spring itself resets only the
 * buffer, `Content-Type` and `Content-Disposition`. Without the reset, the error response keeps the success headers,
 * such as the `ETag` and the `Content-Length` of the file. Callers read the content only in [headers] or here, so the
 * reset covers each failed read.
 */
fun writeRaw(response: HttpServletResponse, read: ItemRead, headers: () -> Unit) {
    runCatching {
        headers()
        response.contentType = read.mediaType
        response.setHeader(CONTENT_TYPE_OPTIONS_HEADER, "nosniff")
        response.setContentLengthLong(read.item.size)
        read.writeTo(response.outputStream)
    }.onFailure { if (!response.isCommitted) response.reset() }
        .getOrThrow()
}

/** Spring MVC keeps the slash at the start of a `{*path}` route variable, so this function removes that one slash. */
fun itemPathOf(wildcard: String): ItemPath = ItemPath(wildcard.removePrefix("/"))

private const val CONTENT_TYPE_OPTIONS_HEADER = "X-Content-Type-Options"
