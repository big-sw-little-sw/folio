package io.github.big_sw_little_sw.folio.credential.web

import io.github.big_sw_little_sw.folio.catalog.NAMESPACE_ID
import io.github.big_sw_little_sw.folio.catalog.Slug
import io.github.big_sw_little_sw.folio.credential.CREDENTIAL_ID
import io.github.big_sw_little_sw.folio.credential.KEY_ID
import io.github.big_sw_little_sw.folio.credential.internal.CredentialService
import io.github.big_sw_little_sw.folio.credential.internal.MasterKeyService
import io.github.big_sw_little_sw.folio.identity.CurrentCaller
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

@RestController
class CredentialApi(private val currentCaller: CurrentCaller, private val credentials: CredentialService) {
    @PostMapping("$ADMIN/credentials")
    fun create(@RequestBody request: CreateCredentialRequest): ResponseEntity<CredentialResponse> {
        val credential =
            credentials.create(
                currentCaller.get(),
                NAMESPACE_ID.parse(request.namespaceId),
                Slug(request.name),
                Slug(request.gitInstance),
            )
        return ResponseEntity
            .created(URI.create("$ADMIN/credentials/${credential.id.toApiId()}"))
            .body(credential.toResponse())
    }

    @GetMapping("$ADMIN/credentials")
    fun list(@RequestParam namespaceId: String): CredentialsResponse = CredentialsResponse(
        credentials.list(currentCaller.get(), NAMESPACE_ID.parse(namespaceId)).credentials.map { it.toResponse() },
    )

    @GetMapping("$ADMIN/credentials/{id}")
    fun get(@PathVariable id: String): CredentialResponse =
        credentials.get(currentCaller.get(), CREDENTIAL_ID.parse(id)).toResponse()

    @PostMapping("$ADMIN/credentials/{id}:regenerate")
    fun regenerate(@PathVariable id: String): CredentialResponse =
        credentials.regenerate(currentCaller.get(), CREDENTIAL_ID.parse(id)).toResponse()

    @PostMapping("$ADMIN/credentials/{id}:activate")
    fun activate(@PathVariable id: String, @RequestBody request: KeyRequest): CredentialResponse = credentials
        .activate(currentCaller.get(), CREDENTIAL_ID.parse(id), KEY_ID.parse(request.keyId))
        .toResponse()

    @PostMapping("$ADMIN/credentials/{id}:discard")
    fun discard(@PathVariable id: String, @RequestBody request: KeyRequest): CredentialResponse = credentials
        .discard(currentCaller.get(), CREDENTIAL_ID.parse(id), KEY_ID.parse(request.keyId))
        .toResponse()

    @PostMapping("$ADMIN/credentials/{id}:replace")
    fun replace(@PathVariable id: String): CredentialResponse =
        credentials.replace(currentCaller.get(), CREDENTIAL_ID.parse(id)).toResponse()

    @PostMapping("$ADMIN/credentials/{id}:disable")
    fun disable(@PathVariable id: String): CredentialResponse =
        credentials.disable(currentCaller.get(), CREDENTIAL_ID.parse(id)).toResponse()

    @DeleteMapping("$ADMIN/credentials/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: String) {
        credentials.delete(currentCaller.get(), CREDENTIAL_ID.parse(id))
    }
}

@RestController
class CryptoApi(private val currentCaller: CurrentCaller, private val masterKeys: MasterKeyService) {
    @GetMapping("$ADMIN/crypto")
    fun status(): CryptoResponse = masterKeys.status(currentCaller.get()).toResponse()

    @PostMapping("$ADMIN/crypto:reencrypt")
    fun reencrypt(): ReencryptionResponse = masterKeys.reencrypt(currentCaller.get()).toResponse()
}

private const val ADMIN = "/api/v1/admin"
