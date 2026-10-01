package io.github.big_sw_little_sw.folio.credential.internal

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.stereotype.Component

/**
 * Fails startup if a key is encrypted under a master-key version that `folio.crypto.master-keys` lacks, which
 * would make it undecryptable (ADR 0020). It runs once every singleton exists, so after Flyway's initializer has
 * migrated the schema, and before the web server accepts requests.
 */
@Component
class MasterKeyVersionCheck(
    private val keys: EncryptedKeyRepository,
    private val masterKeys: CryptoProperties,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        val missing = keys.countByMasterKeyVersion().keys - masterKeys.versions
        check(missing.isEmpty()) {
            "Credential keys are encrypted under master-key versions ${missing.sorted()}, which " +
                "folio.crypto.master-keys does not configure. Add them back, then re-encrypt before removing them."
        }
    }
}
