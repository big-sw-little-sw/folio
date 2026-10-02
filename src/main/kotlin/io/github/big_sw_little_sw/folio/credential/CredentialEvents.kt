package io.github.big_sw_little_sw.folio.credential

enum class CredentialChange {
    CREATED,
    KEY_REGENERATED,
    KEY_ACTIVATED,
    KEY_DISCARDED,
    KEY_REPLACED,
    DISABLED,
}

/**
 * Published synchronously inside the transaction of each credential change, after it; a listener that throws rolls the
 * change back. Audit records them (ADR 0038). [credential] is the credential after the change, and [changedKeys] are
 * its keys whose status the change set. Both hold public key material only.
 */
data class CredentialChanged(
    val change: CredentialChange,
    val credential: Credential,
    val changedKeys: List<CredentialKey>,
)

/**
 * Published synchronously inside a transaction after a re-encryption pass (ADR 0038). [reencrypted] counts the keys
 * the pass moved off each master-key version; [usage] is what remains.
 */
data class MasterKeysReencrypted(
    val reencrypted: Map<Int, Int>,
    val usage: MasterKeyUsage,
)
