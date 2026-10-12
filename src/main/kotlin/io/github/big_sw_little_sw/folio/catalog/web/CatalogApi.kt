package io.github.big_sw_little_sw.folio.catalog.web

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.CONFIG_SET_ID
import io.github.big_sw_little_sw.folio.catalog.NAMESPACE_ID
import io.github.big_sw_little_sw.folio.catalog.Slug
import io.github.big_sw_little_sw.folio.catalog.internal.ConfigSetService
import io.github.big_sw_little_sw.folio.catalog.internal.DescriptionChange
import io.github.big_sw_little_sw.folio.catalog.internal.Edit
import io.github.big_sw_little_sw.folio.catalog.internal.NamespaceService
import io.github.big_sw_little_sw.folio.catalog.internal.Rule
import io.github.big_sw_little_sw.folio.catalog.internal.RuleService
import io.github.big_sw_little_sw.folio.identity.CurrentCaller
import io.github.big_sw_little_sw.folio.identity.Subject
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/** The services authorize each catalog operation, so the catalog routes only convert values and call a service. */
@RestController
class NamespaceApi(private val currentCaller: CurrentCaller, private val namespaces: NamespaceService) {
    @PostMapping("$ADMIN/namespaces")
    fun create(@RequestBody request: CreateNamespaceRequest): ResponseEntity<NamespaceResponse> {
        val namespace =
            namespaces.create(
                currentCaller.get(),
                request.parentId?.let(NAMESPACE_ID::parse),
                Slug(request.slug),
                request.description,
            )
        return ResponseEntity
            .created(URI.create("$ADMIN/namespaces/${namespace.id.toApiId()}"))
            .body(namespace.toResponse())
    }

    /**
     * The top-level Namespaces that the caller can READ. A caller with `READ` only deeper in the tree gets an empty
     * list. The start page of the UI shows those deeper resources.
     */
    @GetMapping("$ADMIN/namespaces")
    fun list(): NamespacesResponse =
        NamespacesResponse(namespaces.topLevel(currentCaller.get()).map { it.toResponse() })

    @GetMapping("$ADMIN/namespaces/{id}")
    fun get(@PathVariable id: String): NamespaceDetailsResponse =
        namespaces.get(currentCaller.get(), NAMESPACE_ID.parse(id)).toResponse()

    @PatchMapping("$ADMIN/namespaces/{id}")
    fun edit(@PathVariable id: String, @RequestBody request: EditRequest): NamespaceResponse =
        namespaces.edit(currentCaller.get(), NAMESPACE_ID.parse(id), request.toEdit()).toResponse()

    @PostMapping("$ADMIN/namespaces/{id}:move")
    fun move(@PathVariable id: String, @RequestBody request: MoveNamespaceRequest): NamespaceResponse = namespaces
        .move(currentCaller.get(), NAMESPACE_ID.parse(id), request.parentId?.let(NAMESPACE_ID::parse))
        .toResponse()

    @DeleteMapping("$ADMIN/namespaces/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: String) = namespaces.delete(currentCaller.get(), NAMESPACE_ID.parse(id))
}

@RestController
class ConfigSetApi(private val currentCaller: CurrentCaller, private val configSets: ConfigSetService) {
    @GetMapping("/api/v1/configsets:resolve")
    fun resolve(@RequestParam path: String): ResolveResponse {
        val configSet = configSets.resolve(currentCaller.get(), path)
        return ResolveResponse(configSet.id.toApiId(), configSet.path.toString())
    }

    @GetMapping("$ADMIN/configsets/{id}")
    fun get(@PathVariable id: String): ConfigSetResponse =
        configSets.get(currentCaller.get(), CONFIG_SET_ID.parse(id)).configSet.toResponse()

    @PatchMapping("$ADMIN/configsets/{id}")
    fun edit(@PathVariable id: String, @RequestBody request: EditRequest): ConfigSetResponse =
        configSets.edit(currentCaller.get(), CONFIG_SET_ID.parse(id), request.toEdit()).toResponse()

    @PostMapping("$ADMIN/configsets/{id}:move")
    fun move(@PathVariable id: String, @RequestBody request: MoveConfigSetRequest): ConfigSetResponse = configSets
        .move(currentCaller.get(), CONFIG_SET_ID.parse(id), NAMESPACE_ID.parse(request.namespaceId))
        .toResponse()

    @DeleteMapping("$ADMIN/configsets/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: String) = configSets.delete(currentCaller.get(), CONFIG_SET_ID.parse(id))
}

/**
 * Each route serves the rules of Namespaces and of ConfigSets, so the `@Parameter` annotation lists the two values of
 * `collection` for the OpenAPI document.
 */
@RestController
class RuleApi(private val currentCaller: CurrentCaller, private val rules: RuleService) {
    @GetMapping("$ADMIN/{collection:namespaces|configsets}/{id}/rules")
    fun rules(
        @PathVariable @Parameter(schema = Schema(allowableValues = [NAMESPACES, CONFIG_SETS])) collection: String,
        @PathVariable id: String,
    ): RulesResponse =
        RulesResponse(rules.rules(currentCaller.get(), resourceOf(collection, id)).map { it.toResponse() })

    @PutMapping("$ADMIN/{collection:namespaces|configsets}/{id}/rules/{action}")
    fun put(
        @PathVariable @Parameter(schema = Schema(allowableValues = [NAMESPACES, CONFIG_SETS])) collection: String,
        @PathVariable id: String,
        @PathVariable action: Action,
        @RequestBody request: PutRuleRequest,
    ): RuleResponse {
        val rule = Rule(action, request.subjects.map(Subject::parse).toSet())
        return rules.put(currentCaller.get(), resourceOf(collection, id), rule).toResponse()
    }

    @DeleteMapping("$ADMIN/{collection:namespaces|configsets}/{id}/rules/{action}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable @Parameter(schema = Schema(allowableValues = [NAMESPACES, CONFIG_SETS])) collection: String,
        @PathVariable id: String,
        @PathVariable action: Action,
    ) = rules.delete(currentCaller.get(), resourceOf(collection, id), action)

    @PostMapping("$ADMIN/{collection:namespaces|configsets}/{id}/explain")
    fun explain(
        @PathVariable @Parameter(schema = Schema(allowableValues = [NAMESPACES, CONFIG_SETS])) collection: String,
        @PathVariable id: String,
        @RequestBody request: ExplainRequest,
    ): ExplanationResponse {
        val described = request.caller?.toCaller()
        return rules.explain(currentCaller.get(), resourceOf(collection, id), request.action, described).toResponse()
    }
}

private const val ADMIN = "/api/v1/admin"

private fun EditRequest.toEdit() = Edit(
    slug?.let(::Slug),
    description?.let { DescriptionChange.Replace(it.orElse(null)) } ?: DescriptionChange.Keep,
)
