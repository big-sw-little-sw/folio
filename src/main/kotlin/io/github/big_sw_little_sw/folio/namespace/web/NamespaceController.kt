package io.github.big_sw_little_sw.folio.namespace.web

import io.github.big_sw_little_sw.folio.namespace.Namespace
import io.github.big_sw_little_sw.folio.namespace.NamespaceService
import io.github.big_sw_little_sw.folio.namespace.Slug
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.net.URI

data class NamespaceResponse(
    val id: String,
    val parentId: String?,
    val slug: String,
)

/** Slugs arrive as strings and become [Slug] here, so an invalid slug fails as `InvalidSlugException`. */
data class CreateNamespaceRequest(
    val parentId: String? = null,
    val slug: String,
)

data class RenameNamespaceRequest(
    val slug: String,
)

/** A null [parentId] moves the namespace to the root. */
data class MoveNamespaceRequest(
    val parentId: String? = null,
)

@RestController
@RequestMapping("/api/v1/admin/namespaces")
class NamespaceController(
    private val namespaces: NamespaceService,
) {
    @PostMapping
    fun create(
        @RequestBody request: CreateNamespaceRequest,
    ): ResponseEntity<NamespaceResponse> {
        val namespace = namespaces.create(request.parentId?.toNamespaceId(), Slug(request.slug))
        return ResponseEntity
            .created(URI.create("/api/v1/admin/namespaces/${namespace.id.toApiId()}"))
            .body(namespace.toResponse())
    }

    /** Children of `parentId`, or root namespaces without it; only those the caller may view. */
    @GetMapping
    fun children(
        @RequestParam parentId: String?,
    ): List<NamespaceResponse> = namespaces.children(parentId?.toNamespaceId()).map { it.toResponse() }

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
    ): NamespaceResponse = namespaces.get(id.toNamespaceId()).toResponse()

    @PostMapping("/{id}:rename")
    fun rename(
        @PathVariable id: String,
        @RequestBody request: RenameNamespaceRequest,
    ): NamespaceResponse = namespaces.rename(id.toNamespaceId(), Slug(request.slug)).toResponse()

    @PostMapping("/{id}:move")
    fun move(
        @PathVariable id: String,
        @RequestBody request: MoveNamespaceRequest,
    ): NamespaceResponse = namespaces.move(id.toNamespaceId(), request.parentId?.toNamespaceId()).toResponse()

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable id: String,
    ) {
        namespaces.delete(id.toNamespaceId())
    }

    private fun Namespace.toResponse() = NamespaceResponse(id.toApiId(), parentId?.toApiId(), slug.value)
}
