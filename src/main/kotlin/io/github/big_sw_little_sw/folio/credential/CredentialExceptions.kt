package io.github.big_sw_little_sw.folio.credential

/** Expected failures of credential operations (ADR 0006). */
sealed class CredentialException(
    message: String,
) : RuntimeException(message)

class CredentialNotFoundException(
    val id: CredentialId,
) : CredentialException("Credential ${id.value} not found")

/** An API ID that is not `cred_` followed by 32 lowercase hex characters (ADR 0007). */
class InvalidCredentialIdException(
    val value: String,
) : CredentialException("Invalid credential ID '$value'")

/** An API ID that is not `key_` followed by 32 lowercase hex characters (ADR 0007). */
class InvalidKeyIdException(
    val value: String,
) : CredentialException("Invalid key ID '$value'")

/** The name is not configured in `folio.git.instances` (ADR 0016). */
class UnknownGitInstanceException(
    val name: String,
) : CredentialException("Unknown Git instance '$name'")

class InvalidCredentialNameException(
    val name: String,
) : CredentialException("Invalid credential name '$name'")

/** Names are unique per Git instance (ADR 0029). */
class DuplicateCredentialNameException(
    val gitInstance: String,
    val name: CredentialName,
) : CredentialException("A credential named '$name' already exists for Git instance '$gitInstance'")

class CredentialDisabledException(
    val id: CredentialId,
) : CredentialException("Credential ${id.value} is disabled")

/** A regeneration while a key is already pending; activate it or replace the credential's key first. */
class PendingKeyExistsException(
    val id: CredentialId,
) : CredentialException("Credential ${id.value} already has a pending key")

/** Activation named a key that is not the credential's pending key (ADR 0017). */
class KeyNotPendingException(
    val credentialId: CredentialId,
    val keyId: KeyId,
) : CredentialException("Key ${keyId.value} is not the pending key of credential ${credentialId.value}")

/**
 * The credential's Git instance was removed from `folio.git.instances` after the credential was created, so it cannot
 * reach any Git service (ADR 0027).
 */
class GitInstanceNotConfiguredException(
    val id: CredentialId,
    val name: String,
) : CredentialException("Git instance '$name' of credential ${id.value} is not configured")
