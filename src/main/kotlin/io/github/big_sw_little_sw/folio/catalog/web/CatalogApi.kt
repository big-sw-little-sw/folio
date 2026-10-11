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

/**
 * NamespaceApi, ConfigSetApi and RuleApi serve the catalog routes of the API (design 12.1, 12.2). Each route takes the
 * caller from the request, converts API IDs and slugs, and calls a service. The services authorize.
 */
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
     * list. The start page of the UI shows those deeper resources (design 12.2, 12.3).
     */
    @GetMapping("$ADMIN/namespaces")
    fun list(): NamespacesResponse =
        NamespacesResponse(namespaces.topLevel(currentCaller.get()).map { it.toResponse() })

    /** The Namespace with the child Namespaces and ConfigSets that the caller can READ. */
    @GetMapping("$ADMIN/namespaces/{id}")
    fun get(@PathVariable id: String): NamespaceDetailsResponse =
        namespaces.get(currentCaller.get(), NAMESPACE_ID.parse(id)).toResponse()

    /** Renames, describes or both. */
    @PatchMapping("$ADMIN/namespaces/{id}")
    fun edit(@PathVariable id: String, @RequestBody request: EditRequest): NamespaceResponse =
        namespaces.edit(currentCaller.get(), NAMESPACE_ID.parse(id), request.toEdit()).toResponse()

    @PostMapping("$ADMIN/namespaces/{id}:move")
    fun move(@PathVariable id: String, @RequestBody request: MoveNamespaceRequest): NamespaceResponse = namespaces
        .move(currentCaller.get(), NAMESPACE_ID.parse(id), request.parentId?.let(NAMESPACE_ID::parse))
        .toResponse()

    /** Deletes an empty Namespace. */
    @DeleteMapping("$ADMIN/namespaces/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: String) = namespaces.delete(currentCaller.get(), NAMESPACE_ID.parse(id))
}

@RestController
class ConfigSetApi(private val currentCaller: CurrentCaller, private val configSets: ConfigSetService) {
    /**
     * Finds the ConfigSet at a path such as `engineering/search/service-a`. A consumer keeps the ID, because a rename
     * or a move changes the path. A path that does not exist and a path that the caller cannot read give the same
     * response.
     */
    @GetMapping("/api/v1/configsets:resolve")
    fun resolve(@RequestParam path: String): ResolveResponse {
        val configSet = configSets.resolve(currentCaller.get(), path)
        return ResolveResponse(configSet.id.toApiId(), configSet.path.toString())
    }

    @GetMapping("$ADMIN/configsets/{id}")
    fun get(@PathVariable id: String): ConfigSetResponse =
        configSets.get(currentCaller.get(), CONFIG_SET_ID.parse(id)).configSet.toResponse()

    /** Renames, describes or both. */
    @PatchMapping("$ADMIN/configsets/{id}")
    fun edit(@PathVariable id: String, @RequestBody request: EditRequest): ConfigSetResponse =
        configSets.edit(currentCaller.get(), CONFIG_SET_ID.parse(id), request.toEdit()).toResponse()

    @PostMapping("$ADMIN/configsets/{id}:move")
    fun move(@PathVariable id: String, @RequestBody request: MoveConfigSetRequest): ConfigSetResponse = configSets
        .move(currentCaller.get(), CONFIG_SET_ID.parse(id), NAMESPACE_ID.parse(request.namespaceId))
        .toResponse()

    /** Deletes the ConfigSet, its rules, its Source definition and its revisions. */
    @DeleteMapping("$ADMIN/configsets/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: String) = configSets.delete(currentCaller.get(), CONFIG_SET_ID.parse(id))
}

/**
 * The rules and explanations of Namespaces and ConfigSets. All routes need `GRANT`. Each route serves both collections,
 * so the `@Parameter` annotation lists the two values of `collection` for the OpenAPI document.
 */
@RestController
class RuleApi(private val currentCaller: CurrentCaller, private val rules: RuleService) {
    /** The own rules of the resource. Inherited rules are on the resources above it. */
    @GetMapping("$ADMIN/{collection:namespaces|configsets}/{id}/rules")
    fun rules(
        @PathVariable @Parameter(schema = Schema(allowableValues = [NAMESPACES, CONFIG_SETS])) collection: String,
        @PathVariable id: String,
    ): RulesResponse =
        RulesResponse(rules.rules(currentCaller.get(), resourceOf(collection, id)).map { it.toResponse() })

    /** Replaces the rule for the action. */
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

    /** Removes the rule for the action, so that the action inherits again. A missing rule is not an error. */
    @DeleteMapping("$ADMIN/{collection:namespaces|configsets}/{id}/rules/{action}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable @Parameter(schema = Schema(allowableValues = [NAMESPACES, CONFIG_SETS])) collection: String,
        @PathVariable id: String,
        @PathVariable action: Action,
    ) = rules.delete(currentCaller.get(), resourceOf(collection, id), action)

    /** Tells which rule or super-admin entry decides the action for the caller in the request. */
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

/** An absent description keeps the current description. A null or blank description clears the description. */
private fun EditRequest.toEdit() = Edit(
    slug?.let(::Slug),
    description?.let { DescriptionChange.Replace(it.orElse(null)) } ?: DescriptionChange.Keep,
)
