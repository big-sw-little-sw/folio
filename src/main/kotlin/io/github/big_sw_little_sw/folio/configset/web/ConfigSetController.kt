package io.github.big_sw_little_sw.folio.configset.web

import io.github.big_sw_little_sw.folio.configset.ConfigSet
import io.github.big_sw_little_sw.folio.configset.ConfigSetPath
import io.github.big_sw_little_sw.folio.configset.ConfigSetService
import io.github.big_sw_little_sw.folio.namespace.Slug
import io.github.big_sw_little_sw.folio.namespace.toApiId
import io.github.big_sw_little_sw.folio.namespace.toNamespaceId
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.net.URI

data class ConfigSetResponse(
    val id: String,
    val namespaceId: String,
    val slug: String,
)

/** Slugs arrive as strings and become [Slug] here, so an invalid slug fails as `InvalidSlugException`. */
data class CreateConfigSetRequest(
    val namespaceId: String,
    val slug: String,
)

data class RenameConfigSetRequest(
    val slug: String,
)

data class MoveConfigSetRequest(
    val namespaceId: String,
)

/** [path] is the canonical path (ADR 0013). */
data class ResolvedConfigSetResponse(
    val id: String,
    val path: String,
)

@RestController
class ConfigSetController(
    private val configSets: ConfigSetService,
) {
    @PostMapping(CONFIG_SETS)
    fun create(
        @RequestBody request: CreateConfigSetRequest,
    ): ResponseEntity<ConfigSetResponse> {
        val configSet = configSets.create(request.namespaceId.toNamespaceId(), Slug(request.slug))
        return ResponseEntity
            .created(URI.create("$CONFIG_SETS/${configSet.id.toApiId()}"))
            .body(configSet.toResponse())
    }

    /** ConfigSets in the namespace that the caller may view. */
    @GetMapping(CONFIG_SETS)
    fun list(
        @RequestParam namespaceId: String,
    ): List<ConfigSetResponse> = configSets.list(namespaceId.toNamespaceId()).map { it.toResponse() }

    @GetMapping("$CONFIG_SETS/{id}")
    fun get(
        @PathVariable id: String,
    ): ConfigSetResponse = configSets.get(id.toConfigSetId()).toResponse()

    @PostMapping("$CONFIG_SETS/{id}:rename")
    fun rename(
        @PathVariable id: String,
        @RequestBody request: RenameConfigSetRequest,
    ): ConfigSetResponse = configSets.rename(id.toConfigSetId(), Slug(request.slug)).toResponse()

    @PostMapping("$CONFIG_SETS/{id}:move")
    fun move(
        @PathVariable id: String,
        @RequestBody request: MoveConfigSetRequest,
    ): ConfigSetResponse = configSets.move(id.toConfigSetId(), request.namespaceId.toNamespaceId()).toResponse()

    @DeleteMapping("$CONFIG_SETS/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable id: String,
    ) {
        configSets.delete(id.toConfigSetId())
    }

    /** A consumption route, outside the admin API; it still needs a token until slice 7. */
    @GetMapping("/api/v1/configsets:resolve")
    fun resolve(
        @RequestParam path: String,
    ): ResolvedConfigSetResponse {
        val configSetPath = ConfigSetPath.parse(path)
        return ResolvedConfigSetResponse(configSets.resolve(configSetPath).id.toApiId(), configSetPath.toString())
    }

    private fun ConfigSet.toResponse() = ConfigSetResponse(id.toApiId(), namespaceId.toApiId(), slug.value)

    private companion object {
        const val CONFIG_SETS = "/api/v1/admin/configsets"
    }
}
