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

/**
 * The credential routes of the API (design 11.1, 12.2). Each route needs `MANAGE` on the Namespace that owns the
 * credential. The services authorize each request. No response contains a private key or a ciphertext.
 */
@RestController
class CredentialApi(private val currentCaller: CurrentCaller, private val credentials: CredentialService) {
    /** Creates the credential with an active key. Register the public key of that key with the Git service. */
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

    /** The credentials that the Namespace owns. */
    @GetMapping("$ADMIN/credentials")
    fun list(@RequestParam namespaceId: String): CredentialsResponse = CredentialsResponse(
        credentials.list(currentCaller.get(), NAMESPACE_ID.parse(namespaceId)).credentials.map { it.toResponse() },
    )

    @GetMapping("$ADMIN/credentials/{id}")
    fun get(@PathVariable id: String): CredentialResponse =
        credentials.get(currentCaller.get(), CREDENTIAL_ID.parse(id)).toResponse()

    /** Adds a pending key. The response has its public key. The route gives status 409 if a key is already pending. */
    @PostMapping("$ADMIN/credentials/{id}:regenerate")
    fun regenerate(@PathVariable id: String): CredentialResponse =
        credentials.regenerate(currentCaller.get(), CREDENTIAL_ID.parse(id)).toResponse()

    /**
     * Activates the pending key and retires the previous active key. The route gives status 409 if the key is not the
     * pending key.
     */
    @PostMapping("$ADMIN/credentials/{id}:activate")
    fun activate(@PathVariable id: String, @RequestBody request: KeyRequest): CredentialResponse = credentials
        .activate(currentCaller.get(), CREDENTIAL_ID.parse(id), KEY_ID.parse(request.keyId))
        .toResponse()

    /** Retires the pending key. The active key does not change. */
    @PostMapping("$ADMIN/credentials/{id}:discard")
    fun discard(@PathVariable id: String, @RequestBody request: KeyRequest): CredentialResponse = credentials
        .discard(currentCaller.get(), CREDENTIAL_ID.parse(id), KEY_ID.parse(request.keyId))
        .toResponse()

    /** For emergencies: retires the active key and the pending key, and activates a new key. */
    @PostMapping("$ADMIN/credentials/{id}:replace")
    fun replace(@PathVariable id: String): CredentialResponse =
        credentials.replace(currentCaller.get(), CREDENTIAL_ID.parse(id)).toResponse()

    /** Disables the credential permanently. A second disable changes nothing. */
    @PostMapping("$ADMIN/credentials/{id}:disable")
    fun disable(@PathVariable id: String): CredentialResponse =
        credentials.disable(currentCaller.get(), CREDENTIAL_ID.parse(id)).toResponse()

    /** Deletes the credential and all its keys. */
    @DeleteMapping("$ADMIN/credentials/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: String) {
        credentials.delete(currentCaller.get(), CREDENTIAL_ID.parse(id))
    }
}

/** The master-key routes (design 11.3). Only super admins can use them. */
@RestController
class CryptoApi(private val currentCaller: CurrentCaller, private val masterKeys: MasterKeyService) {
    /** The number of encrypted keys for each master-key version. */
    @GetMapping("$ADMIN/crypto")
    fun status(): CryptoResponse = masterKeys.status(currentCaller.get()).toResponse()

    /** Encrypts again each key that does not use the active version. Parallel and repeated runs are safe. */
    @PostMapping("$ADMIN/crypto:reencrypt")
    fun reencrypt(): ReencryptionResponse = masterKeys.reencrypt(currentCaller.get()).toResponse()
}

private const val ADMIN = "/api/v1/admin"
