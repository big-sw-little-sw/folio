package io.github.big_sw_little_sw.folio.credential

import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.Slug
import io.github.big_sw_little_sw.folio.web.ErrorKind
import io.github.big_sw_little_sw.folio.web.FolioException

/**
 * The expected failures of the credential module. A name or a Git instance that does not follow the slug rules gives
 * `CatalogException.InvalidSlug`, because the catalog owns the slug rules.
 */
sealed class CredentialException(kind: ErrorKind, code: String, detail: String) : FolioException(kind, code, detail) {
    class CredentialNotFound(id: CredentialId) :
        CredentialException(ErrorKind.NOT_FOUND, "CREDENTIAL_NOT_FOUND", "Credential ${id.toApiId()} does not exist.")

    class NameTaken(name: Slug, namespace: NamespaceId) :
        CredentialException(
            ErrorKind.CONFLICT,
            "CREDENTIAL_NAME_TAKEN",
            "The credential name '$name' is already in use in ${namespace.toApiId()}.",
        )

    /** A disabled credential refuses key changes and gives no key to sync. */
    class CredentialDisabled(id: CredentialId) :
        CredentialException(
            ErrorKind.CONFLICT,
            "CREDENTIAL_DISABLED",
            "Credential ${id.toApiId()} is disabled.",
        )

    /** A credential has a maximum of one pending key. Activate or discard the pending key first. */
    class PendingKeyExists(id: CredentialId) :
        CredentialException(
            ErrorKind.CONFLICT,
            "PENDING_KEY_EXISTS",
            "Credential ${id.toApiId()} already has a pending key. Activate or discard it first.",
        )

    /**
     * Activate, discard and the Git check name the pending key that they act on. Without the name, a caller could act
     * on a key that another person generated in the meantime.
     */
    class KeyNotPending(credential: CredentialId, key: KeyId) :
        CredentialException(
            ErrorKind.CONFLICT,
            "KEY_NOT_PENDING",
            "Key ${key.toApiId()} is not the pending key of credential ${credential.toApiId()}.",
        )

    /** A Git source uses the credential, so Folio does not delete it. */
    class CredentialInUse(id: CredentialId) :
        CredentialException(
            ErrorKind.CONFLICT,
            "CREDENTIAL_IN_USE",
            "A Git source uses credential ${id.toApiId()}. Delete the ConfigSets that use it first.",
        )
}
