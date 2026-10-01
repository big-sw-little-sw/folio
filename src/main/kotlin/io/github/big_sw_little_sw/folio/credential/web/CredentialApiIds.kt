package io.github.big_sw_little_sw.folio.credential.web

import io.github.big_sw_little_sw.folio.apiid.formatApiId
import io.github.big_sw_little_sw.folio.apiid.parseApiId
import io.github.big_sw_little_sw.folio.credential.CredentialId
import io.github.big_sw_little_sw.folio.credential.InvalidCredentialIdException
import io.github.big_sw_little_sw.folio.credential.InvalidKeyIdException
import io.github.big_sw_little_sw.folio.credential.KeyId

private const val CREDENTIAL_PREFIX = "cred_"
private const val KEY_PREFIX = "key_"

fun CredentialId.toApiId(): String = formatApiId(CREDENTIAL_PREFIX, value)

fun String.toCredentialId(): CredentialId =
    CredentialId(parseApiId(CREDENTIAL_PREFIX, this) ?: throw InvalidCredentialIdException(this))

fun KeyId.toApiId(): String = formatApiId(KEY_PREFIX, value)

fun String.toKeyId(): KeyId = KeyId(parseApiId(KEY_PREFIX, this) ?: throw InvalidKeyIdException(this))
