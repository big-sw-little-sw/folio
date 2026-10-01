package io.github.big_sw_little_sw.folio.credential.web

import io.github.big_sw_little_sw.folio.credential.CryptoService
import io.github.big_sw_little_sw.folio.credential.MasterKeyUsage
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/** [versions] lists every configured master-key version, ordered by version, with its number of stored keys. */
data class MasterKeyUsageResponse(
    val activeVersion: Int,
    val versions: List<MasterKeyVersionResponse>,
)

data class MasterKeyVersionResponse(
    val version: Int,
    val keys: Int,
)

@RestController
class CryptoController(
    private val crypto: CryptoService,
) {
    @GetMapping(CRYPTO)
    fun usage(): MasterKeyUsageResponse = crypto.usage().toResponse()

    /** Re-encrypts keys under older master-key versions with the active one; safe to re-run. */
    @PostMapping("$CRYPTO:reencrypt")
    fun reencrypt(): MasterKeyUsageResponse = crypto.reencrypt().toResponse()

    private fun MasterKeyUsage.toResponse() =
        MasterKeyUsageResponse(
            activeVersion,
            keysByVersion.map { (version, keys) ->
                MasterKeyVersionResponse(version, keys)
            },
        )

    private companion object {
        const val CRYPTO = "/api/v1/admin/crypto"
    }
}
