package io.github.big_sw_little_sw.folio.credential.web

import io.github.big_sw_little_sw.folio.credential.Credential
import io.github.big_sw_little_sw.folio.credential.CredentialKey
import io.github.big_sw_little_sw.folio.credential.CredentialService
import io.github.big_sw_little_sw.folio.credential.CredentialStatus
import io.github.big_sw_little_sw.folio.credential.KeyStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/** Public key material only; private keys never appear in the API (v1-scope). */
data class CredentialResponse(
    val id: String,
    val gitInstance: String,
    val status: CredentialStatus,
    /** Oldest first. */
    val keys: List<KeyResponse>,
)

/** [publicKey] is the OpenSSH line to register with the Git service; its comment is the key's API ID. */
data class KeyResponse(
    val id: String,
    val status: KeyStatus,
    val publicKey: String,
    val fingerprint: String,
)

/** [gitInstance] is a name from `folio.git.instances`. */
data class CreateCredentialRequest(
    val gitInstance: String,
)

/** [keyId] is the pending key the administrator registered with the Git service. */
data class ActivateKeyRequest(
    val keyId: String,
)

@RestController
@RequestMapping("/api/v1/admin/credentials")
class CredentialController(
    private val credentials: CredentialService,
) {
    @PostMapping
    fun create(
        @RequestBody request: CreateCredentialRequest,
    ): ResponseEntity<CredentialResponse> {
        val credential = credentials.create(request.gitInstance)
        return ResponseEntity
            .created(URI.create("$CREDENTIALS/${credential.id.toApiId()}"))
            .body(credential.toResponse())
    }

    @GetMapping
    fun list(): List<CredentialResponse> = credentials.list().map { it.toResponse() }

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
    ): CredentialResponse = credentials.get(id.toCredentialId()).toResponse()

    /** Adds a pending key; the response carries its public key. */
    @PostMapping("/{id}:regenerate")
    fun regenerate(
        @PathVariable id: String,
    ): CredentialResponse = credentials.regenerate(id.toCredentialId()).toResponse()

    @PostMapping("/{id}:activate")
    fun activate(
        @PathVariable id: String,
        @RequestBody request: ActivateKeyRequest,
    ): CredentialResponse = credentials.activate(id.toCredentialId(), request.keyId.toKeyId()).toResponse()

    /** Emergency replacement: a new key is active at once. */
    @PostMapping("/{id}:replace")
    fun replace(
        @PathVariable id: String,
    ): CredentialResponse = credentials.replace(id.toCredentialId()).toResponse()

    @PostMapping("/{id}:disable")
    fun disable(
        @PathVariable id: String,
    ): CredentialResponse = credentials.disable(id.toCredentialId()).toResponse()

    private fun Credential.toResponse() =
        CredentialResponse(
            id.toApiId(),
            gitInstance,
            status,
            keys.map {
                it.toResponse()
            },
        )

    private fun CredentialKey.toResponse(): KeyResponse {
        val apiId = id.toApiId()
        return KeyResponse(apiId, status, "$publicKey $apiId", fingerprint)
    }

    private companion object {
        const val CREDENTIALS = "/api/v1/admin/credentials"
    }
}
