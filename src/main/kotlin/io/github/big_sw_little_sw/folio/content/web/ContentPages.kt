package io.github.big_sw_little_sw.folio.content.web

import io.github.big_sw_little_sw.folio.catalog.CONFIG_SET_ID
import io.github.big_sw_little_sw.folio.catalog.ConfigSet
import io.github.big_sw_little_sw.folio.content.ItemPath
import io.github.big_sw_little_sw.folio.content.RevisionId
import io.github.big_sw_little_sw.folio.content.internal.ContentService
import io.github.big_sw_little_sw.folio.content.internal.ItemContent
import io.github.big_sw_little_sw.folio.content.internal.RevisionSelector
import io.github.big_sw_little_sw.folio.identity.CurrentCaller
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.util.UriUtils
import java.nio.charset.StandardCharsets

/**
 * ContentPages serves the content pages for callers with `READ` (design 12.3): the revisions, the files at a revision,
 * one file as text and the download of one file. The catalog serves the ConfigSet page, and the catalog does not
 * depend on the `content` module. Thus the ConfigSet page links to these pages by path only. The service authorizes
 * each page.
 */
@Controller
@RequestMapping("/configsets/{id}")
class ContentPages(private val currentCaller: CurrentCaller, private val content: ContentService) {
    @GetMapping("/revisions")
    fun revisions(@PathVariable id: String): ModelAndView {
        val list = content.revisions(currentCaller.get(), CONFIG_SET_ID.parse(id)).body
        return ModelAndView("content/web/revisions")
            .addObject("links", ContentLinks(list.configSet))
            .addObject("revisions", list.revisions)
    }

    /**
     * The files page of `latest` names the exact revision, and its links go to that revision. Thus the files that a
     * person opens are the files in the list, also after a new publish.
     */
    @GetMapping("/files")
    fun files(
        @PathVariable id: String,
        @RequestParam(defaultValue = RevisionId.LATEST) revision: String,
    ): ModelAndView {
        val selector = RevisionSelector.parse(revision)
        val list = content.items(currentCaller.get(), CONFIG_SET_ID.parse(id), selector).body
        return ModelAndView("content/web/files")
            .addObject("links", ContentLinks(list.configSet))
            .addObject("revision", list.revision)
            .addObject("latest", selector == RevisionSelector.Latest)
            .addObject("items", list.items)
    }

    /**
     * Shows the file as escaped text, never as HTML (design 12.3). A file larger than 3 MB is not in memory, so the
     * page shows no text for it. The page offers the download instead.
     */
    @GetMapping("/files/{*path}")
    fun file(
        @PathVariable id: String,
        @PathVariable path: String,
        @RequestParam(defaultValue = RevisionId.LATEST) revision: String,
    ): ModelAndView {
        val configSet = CONFIG_SET_ID.parse(id)
        val itemPath = itemPathOf(path)
        return content.read(currentCaller.get(), configSet, itemPath, RevisionSelector.parse(revision)) { read ->
            val body = read.content
            val text =
                when (body) {
                    is ItemContent.InMemory -> body.bytes.toString(StandardCharsets.UTF_8)
                    is ItemContent.Streamed -> null
                }
            ModelAndView("content/web/file")
                .addObject("links", ContentLinks(read.configSet))
                .addObject("revision", read.revision)
                .addObject("item", read.item)
                .addObject("mediaType", read.mediaType)
                .addObject("validation", body.validation)
                .addObject("text", text)
        }
    }

    /**
     * Sends the raw bytes as an attachment with `nosniff` (design 12.3). Thus the browser saves the file and never
     * shows it.
     * The download needs no validation status, so Folio does not validate the file.
     */
    @GetMapping("/download/{*path}")
    fun download(
        @PathVariable id: String,
        @PathVariable path: String,
        @RequestParam(defaultValue = RevisionId.LATEST) revision: String,
        response: HttpServletResponse,
    ) {
        val configSet = CONFIG_SET_ID.parse(id)
        val itemPath = itemPathOf(path)
        content.read(currentCaller.get(), configSet, itemPath, RevisionSelector.parse(revision)) { read ->
            val disposition = ContentDisposition.attachment().filename(itemPath.name, StandardCharsets.UTF_8).build()
            writeRaw(response, read) { response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition.toString()) }
        }
    }
}

/**
 * ContentLinks makes the URLs of the content pages of one ConfigSet. It encodes each segment of an item path
 * separately. Thus a `/` in the path stays a separator, and a character such as `#` or `?` cannot change the URL.
 */
class ContentLinks(configSet: ConfigSet) {
    val path: String = configSet.path.toString()
    val configSetPage: String = "/configsets/${configSet.id.toApiId()}"
    val revisions: String = "$configSetPage/revisions"

    fun files(revision: RevisionId): String = "$configSetPage/files?revision=$revision"

    fun file(path: ItemPath, revision: RevisionId): String = "$configSetPage/files/${encode(path)}?revision=$revision"

    fun download(path: ItemPath, revision: RevisionId): String =
        "$configSetPage/download/${encode(path)}?revision=$revision"

    private fun encode(path: ItemPath): String =
        path.segments.joinToString("/") { UriUtils.encodePathSegment(it, StandardCharsets.UTF_8) }
}
