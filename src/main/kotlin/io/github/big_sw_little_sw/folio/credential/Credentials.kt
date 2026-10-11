package io.github.big_sw_little_sw.folio.credential

import io.github.big_sw_little_sw.folio.catalog.Access
import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.Slug
import io.github.big_sw_little_sw.folio.catalog.SnapshotRead
import io.github.big_sw_little_sw.folio.credential.internal.CredentialRepository
import io.github.big_sw_little_sw.folio.credential.internal.CredentialStatus
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.stereotype.Service

/** A credential that the caller can connect to a repository. [gitInstance] names the Git server of the repository. */
data class UsableCredential(val id: CredentialId, val namespaceId: NamespaceId, val gitInstance: Slug)

/** Other modules use credentials through this class (design 8.3). The `git` module calls it at onboarding. */
@Service
class Credentials(private val credentials: CredentialRepository, private val access: Access) {
    /**
     * Requires that the caller can use the credential for a ConfigSet: `MANAGE` on the Namespace that owns the
     * credential (design 7.3). Throws `CredentialNotFound` (404), `Forbidden` (403), `Unauthenticated` (401) or
     * `CredentialDisabled` (409), in this order.
     */
    @SnapshotRead
    fun requireUsable(caller: Caller, id: CredentialId): UsableCredential {
        val row = credentials.find(id) ?: throw CredentialException.CredentialNotFound(id)
        access.requireAllowed(caller, Action.MANAGE, row.namespaceId)
        if (row.status == CredentialStatus.DISABLED) throw CredentialException.CredentialDisabled(id)
        return UsableCredential(id, row.namespaceId, row.gitInstance)
    }
}
