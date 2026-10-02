package io.github.big_sw_little_sw.folio.credential

import io.github.big_sw_little_sw.folio.apiid.formatApiId
import io.github.big_sw_little_sw.folio.apiid.parseApiId

// Public so that HTTP layers of other modules, such as ConfigSets, can accept and return credential and key IDs
// (ADR 0023).
private const val CREDENTIAL_PREFIX = "cred_"
private const val KEY_PREFIX = "key_"

fun CredentialId.toApiId(): String = formatApiId(CREDENTIAL_PREFIX, value)

fun String.toCredentialId(): CredentialId =
    CredentialId(parseApiId(CREDENTIAL_PREFIX, this) ?: throw InvalidCredentialIdException(this))

fun KeyId.toApiId(): String = formatApiId(KEY_PREFIX, value)

fun String.toKeyId(): KeyId = KeyId(parseApiId(KEY_PREFIX, this) ?: throw InvalidKeyIdException(this))
