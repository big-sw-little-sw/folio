package io.github.big_sw_little_sw.folio.configset.web

import io.github.big_sw_little_sw.folio.configset.ConfigSet
import io.github.big_sw_little_sw.folio.configset.ConfigSetPath
import io.github.big_sw_little_sw.folio.configset.ConfigSetService
import io.github.big_sw_little_sw.folio.configset.toApiId
import io.github.big_sw_little_sw.folio.configset.toConfigSetId
import io.github.big_sw_little_sw.folio.credential.toApiId
import io.github.big_sw_little_sw.folio.credential.toCredentialId
import io.github.big_sw_little_sw.folio.credential.toKeyId
import io.github.big_sw_little_sw.folio.namespace.Slug
import io.github.big_sw_little_sw.folio.namespace.toApiId
import io.github.big_sw_little_sw.folio.namespace.toNamespaceId
import io.github.big_sw_little_sw.folio.source.Branch
import io.github.big_sw_little_sw.folio.source.RepositoryPath
import io.github.big_sw_little_sw.folio.source.SourceCheck
import io.github.big_sw_little_sw.folio.source.SourceDefinition
import io.github.big_sw_little_sw.folio.source.SourceFailure
import io.github.big_sw_little_sw.folio.source.SourcePath
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
    val source: SourceBody,
)

/** Slugs arrive as strings and become [Slug] here, so an invalid slug fails as `InvalidSlugException`. */
data class CreateConfigSetRequest(
    val namespaceId: String,
    val slug: String,
    val source: SourceBody,
)

/**
 * A ConfigSet's Git source (ADR 0022). [credentialId] is a `cred_` ID; [repositoryPath] is the repository's path on
 * the credential's Git instance, such as `org/repo.git`; [rootPath] is relative, and empty for the repository root.
 */
data class SourceBody(
    val credentialId: String,
    val repositoryPath: String,
    val branch: String,
    val rootPath: String,
)

/** [keyId] names the credential's pending key to check instead of its active key. */
data class CheckRequest(
    val keyId: String? = null,
)

/** [code] and [summary] are set when the check failed, [commitId] (the branch tip) when it passed. */
data class CheckResponse(
    val ok: Boolean,
    val code: SourceFailure?,
    val summary: String?,
    val commitId: String?,
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
        val configSet =
            configSets.create(request.namespaceId.toNamespaceId(), Slug(request.slug), request.source.toSource())
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

    /** Runs the onboarding check against the Git service; a failed check is a result, not an error (ADR 0027). */
    @PostMapping("$CONFIG_SETS/{id}:check")
    fun check(
        @PathVariable id: String,
        @RequestBody(required = false) request: CheckRequest?,
    ): CheckResponse =
        when (val result = configSets.check(id.toConfigSetId(), request?.keyId?.toKeyId())) {
            is SourceCheck.Passed -> CheckResponse(true, null, null, result.commitId)
            is SourceCheck.Failed -> CheckResponse(false, result.failure, result.failure.summary, null)
        }

    /** A consumption route, outside the admin API: anonymous callers reach it, so `public` rules apply (ADR 0035). */
    @GetMapping("/api/v1/configsets:resolve")
    fun resolve(
        @RequestParam path: String,
    ): ResolvedConfigSetResponse {
        val configSetPath = ConfigSetPath.parse(path)
        return ResolvedConfigSetResponse(configSets.resolve(configSetPath).id.toApiId(), configSetPath.toString())
    }

    private fun ConfigSet.toResponse() =
        ConfigSetResponse(
            id.toApiId(),
            namespaceId.toApiId(),
            slug.value,
            SourceBody(
                source.credentialId.toApiId(),
                source.repositoryPath.value,
                source.branch.value,
                source.rootPath.value,
            ),
        )

    private fun SourceBody.toSource() =
        SourceDefinition(
            credentialId.toCredentialId(),
            RepositoryPath(repositoryPath),
            Branch(branch),
            SourcePath.parse(rootPath),
        )

    private companion object {
        const val CONFIG_SETS = "/api/v1/admin/configsets"
    }
}
