package io.github.big_sw_little_sw.folio.credential.internal

import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.Path
import io.github.big_sw_little_sw.folio.catalog.Slug
import io.github.big_sw_little_sw.folio.credential.CredentialId
import io.github.big_sw_little_sw.folio.credential.KeyId

/** Disable is permanent in v1 (design 11.1). No operation changes `DISABLED` back to `ENABLED`. */
enum class CredentialStatus {
    ENABLED,
    DISABLED,
}

enum class KeyStatus {
    PENDING,
    ACTIVE,
    RETIRED,
}

/** A row of the `credential` table. The row has no keys and no path. */
data class CredentialRow(
    val id: CredentialId,
    val namespaceId: NamespaceId,
    val name: Slug,
    val gitInstance: Slug,
    val status: CredentialStatus,
) {
    fun toCredential(namespacePath: Path, keys: List<Key>) =
        Credential(id, namespaceId, namespacePath, name, gitInstance, status, keys)
}

/** A key without its private key. Folio never gives a private key, so this class has none (design 11.1). */
data class Key(
    val id: KeyId,
    val status: KeyStatus,
    /** The OpenSSH line without a comment, for example `ssh-ed25519 AAAA…`. */
    val publicKey: String,
    /** The SHA-256 fingerprint in OpenSSH form, for example `SHA256:bbXp…`. */
    val fingerprint: String,
) {
    /**
     * The OpenSSH line with the API ID of the key as its comment. Thus an administrator can match a deploy key in the
     * Git service to its Folio key.
     */
    val publicKeyLine: String get() = "$publicKey ${id.toApiId()}"
}

/** A credential with the current path of its Namespace and its keys in creation order. */
data class Credential(
    val id: CredentialId,
    val namespaceId: NamespaceId,
    val namespacePath: Path,
    val name: Slug,
    val gitInstance: Slug,
    val status: CredentialStatus,
    val keys: List<Key>,
) {
    val enabled: Boolean get() = status == CredentialStatus.ENABLED

    /** The pending key, or null if no key is pending. A partial unique index permits a maximum of one pending key. */
    val pendingKey: Key? get() = keys.firstOrNull { it.status == KeyStatus.PENDING }
}
