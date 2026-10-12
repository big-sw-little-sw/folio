package io.github.big_sw_little_sw.folio.credential

import io.github.big_sw_little_sw.folio.catalog.Access
import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.Slug
import io.github.big_sw_little_sw.folio.catalog.SnapshotRead
import io.github.big_sw_little_sw.folio.credential.internal.CredentialRepository
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.stereotype.Service

/** A credential that the caller can connect to a repository. [gitInstance] names the Git server of the repository. */
data class UsableCredential(val id: CredentialId, val namespaceId: NamespaceId, val gitInstance: Slug)

/** Other modules use credentials through this class. The `git` module calls it at onboarding. */
@Service
class Credentials(private val credentialRepository: CredentialRepository, private val access: Access) {
    /**
     * Requires that the caller can use the credential for a ConfigSet: `MANAGE` on the Namespace that owns the
     * credential. A ConfigSet with a credential can serve each repository that the credential can read. Without this
     * check, a caller could read repositories that the owner of the credential did not open to the caller. Throws
     * `CredentialNotFound` (404), `Forbidden` (403), `Unauthenticated` (401) or `CredentialDisabled` (409), in this
     * order.
     */
    @SnapshotRead
    fun requireUsable(caller: Caller, id: CredentialId): UsableCredential {
        val row = credentialRepository.find(id) ?: throw CredentialException.CredentialNotFound(id)
        access.requireAllowed(caller, Action.MANAGE, row.namespaceId)
        if (!row.enabled) throw CredentialException.CredentialDisabled(id)
        return UsableCredential(id, row.namespaceId, row.gitInstance)
    }
}
