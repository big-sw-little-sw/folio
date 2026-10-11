package io.github.big_sw_little_sw.folio.credential

import io.github.big_sw_little_sw.folio.web.ApiId
import java.util.UUID

@JvmInline
value class CredentialId(val value: UUID) {
    /** The API form, for example `cred_0192f0c1e8a47c3b9d2e5f6a7b8c9d0e`. */
    fun toApiId(): String = CREDENTIAL_ID.format(this)
}

/** The ID of a key of a credential. The comment of the OpenSSH line of a key is its API ID (design 11.1). */
@JvmInline
value class KeyId(val value: UUID) {
    /** The API form, for example `key_0192f0c1e8a47c3b9d2e5f6a7b8c9d0e`. */
    fun toApiId(): String = KEY_ID.format(this)
}

val CREDENTIAL_ID = ApiId("cred", ::CredentialId, CredentialId::value)

val KEY_ID = ApiId("key", ::KeyId, KeyId::value)
