package io.github.big_sw_little_sw.folio.credential.web

import io.github.big_sw_little_sw.folio.credential.internal.Credential
import io.github.big_sw_little_sw.folio.credential.internal.CredentialStatus
import io.github.big_sw_little_sw.folio.credential.internal.Key
import io.github.big_sw_little_sw.folio.credential.internal.KeyStatus
import io.github.big_sw_little_sw.folio.credential.internal.MasterKeyStatus
import io.github.big_sw_little_sw.folio.credential.internal.Reencryption

data class CreateCredentialRequest(
    val namespaceId: String,
    /** The name follows the slug rules and is unique in the Namespace. */
    val name: String,
    /** The name of an entry of `folio.git.instances`. */
    val gitInstance: String,
)

data class KeyRequest(val keyId: String)

data class CredentialResponse(
    val id: String,
    val namespaceId: String,
    /** The current path of the Namespace that owns the credential. */
    val namespacePath: String,
    val name: String,
    val gitInstance: String,
    val status: CredentialStatus,
    /** All keys of the credential, also the retired keys, in creation order. */
    val keys: List<KeyResponse>,
)

data class KeyResponse(
    val id: String,
    val status: KeyStatus,
    /** The OpenSSH line to register with the Git service. */
    val publicKey: String,
    val fingerprint: String,
)

data class CredentialsResponse(val credentials: List<CredentialResponse>)

data class VersionKeysResponse(val version: Int, val keys: Int)

data class CryptoResponse(val activeVersion: Int, val versions: List<VersionKeysResponse>)

data class ReencryptionResponse(
    /** The number of keys that the run moved to the active version, for each previous version. */
    val reencrypted: List<VersionKeysResponse>,
    val activeVersion: Int,
    val versions: List<VersionKeysResponse>,
)

fun Credential.toResponse() = CredentialResponse(
    id = id.toApiId(),
    namespaceId = namespaceId.toApiId(),
    namespacePath = namespacePath.toString(),
    name = name.value,
    gitInstance = gitInstance.value,
    status = status,
    keys = keys.map { it.toResponse() },
)

fun Key.toResponse() = KeyResponse(id.toApiId(), status, publicKeyLine, fingerprint)

fun MasterKeyStatus.toResponse() = CryptoResponse(activeVersion, keysByVersion.toResponse())

fun Reencryption.toResponse() =
    ReencryptionResponse(reencrypted.toResponse(), status.activeVersion, status.keysByVersion.toResponse())

private fun Map<Int, Int>.toResponse() = map { (version, keys) -> VersionKeysResponse(version, keys) }
