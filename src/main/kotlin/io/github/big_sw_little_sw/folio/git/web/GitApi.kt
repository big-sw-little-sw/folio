package io.github.big_sw_little_sw.folio.git.web

import io.github.big_sw_little_sw.folio.catalog.CONFIG_SET_ID
import io.github.big_sw_little_sw.folio.catalog.NAMESPACE_ID
import io.github.big_sw_little_sw.folio.catalog.Slug
import io.github.big_sw_little_sw.folio.credential.KEY_ID
import io.github.big_sw_little_sw.folio.git.internal.GitCheck
import io.github.big_sw_little_sw.folio.git.internal.GitSources
import io.github.big_sw_little_sw.folio.identity.CurrentCaller
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/**
 * The Git routes of the API (design 10.3, 12.2). Only the create route of a Source creates a ConfigSet of that Source,
 * so the git module validates its own Source definition. The services authorize each request.
 */
@RestController
class GitApi(private val currentCaller: CurrentCaller, private val sources: GitSources, private val check: GitCheck) {
    /**
     * Creates the ConfigSet and its Git source in one transaction, without a connection to the Git server. Register
     * the public key of the credential with the Git service, then run the check.
     */
    @PostMapping("$ADMIN/git/configsets")
    fun create(@RequestBody request: CreateGitConfigSetRequest): ResponseEntity<GitSourceResponse> {
        val namespace = NAMESPACE_ID.parse(request.namespaceId)
        val slug = Slug(request.slug)
        val source =
            sourceDefinitionOf(request.credentialId, request.repositoryPath, request.branch, request.rootPath)
        val created = sources.create(currentCaller.get(), namespace, slug, request.description, source)
        return ResponseEntity
            .created(URI.create("$ADMIN/configsets/${created.configSet.id.toApiId()}/git"))
            .body(created.toResponse())
    }

    @GetMapping("$ADMIN/configsets/{id}/git")
    fun get(@PathVariable id: String): GitSourceResponse =
        sources.get(currentCaller.get(), CONFIG_SET_ID.parse(id)).toResponse()

    /**
     * Connects to the Git server and gives the result with status 200, also for a failed check (design 10.3). The body
     * is optional. The body `{"keyId": …}` makes the check use that pending key of the credential.
     */
    @PostMapping("$ADMIN/configsets/{id}/git:check")
    fun check(@PathVariable id: String, @RequestBody(required = false) request: CheckRequest?): CheckResponse {
        val configSet = CONFIG_SET_ID.parse(id)
        val pendingKey = request?.keyId?.let(KEY_ID::parse)
        return check.check(currentCaller.get(), configSet, pendingKey).toResponse()
    }
}

private const val ADMIN = "/api/v1/admin"
