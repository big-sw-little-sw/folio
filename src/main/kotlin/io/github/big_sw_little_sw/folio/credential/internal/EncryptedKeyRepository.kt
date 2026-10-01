package io.github.big_sw_little_sw.folio.credential.internal

import io.github.big_sw_little_sw.folio.credential.CredentialId
import io.github.big_sw_little_sw.folio.credential.KeyId
import io.github.big_sw_little_sw.folio.credential.KeyStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

/** A key's encrypted private key with the IDs its associated data binds it to. */
class StoredKey(
    val id: KeyId,
    val credentialId: CredentialId,
    val encrypted: EncryptedKey,
)

/** The encryption columns of `credential_key`: reads of encrypted private keys and their re-encryption. */
@Repository
class EncryptedKeyRepository(
    private val jdbc: JdbcClient,
) {
    fun find(
        credentialId: CredentialId,
        status: KeyStatus,
    ): StoredKey? =
        jdbc
            .sql("select $COLUMNS from credential_key where credential_id = :credentialId and status = :status")
            .param("credentialId", credentialId.value)
            .param("status", status.name)
            .query { rs, _ -> rs.toStoredKey() }
            .optional()
            .orElse(null)

    /** Keys whose private key is encrypted under any master-key version other than [version]. */
    fun findNotUnder(version: Int): List<StoredKey> =
        jdbc
            .sql("select $COLUMNS from credential_key where master_key_version <> :version order by id")
            .param("version", version)
            .query { rs, _ -> rs.toStoredKey() }
            .list()

    /**
     * Replaces the encryption of [id] if it is still encrypted under [expectedVersion]. Returns false if another
     * re-encryption moved it or a retirement wiped it in the meantime; both leave nothing to do.
     */
    fun replaceEncryption(
        id: KeyId,
        expectedVersion: Int,
        encrypted: EncryptedKey,
    ): Boolean =
        jdbc
            .sql(
                """
                update credential_key
                set algorithm = :algorithm, master_key_version = :masterKeyVersion, salt = :salt, nonce = :nonce,
                    ciphertext = :ciphertext
                where id = :id and master_key_version = :expectedVersion
                """.trimIndent(),
            ).param("id", id.value)
            .param("expectedVersion", expectedVersion)
            .param("algorithm", encrypted.algorithm)
            .param("masterKeyVersion", encrypted.masterKeyVersion)
            .param("salt", encrypted.salt)
            .param("nonce", encrypted.nonce)
            .param("ciphertext", encrypted.ciphertext)
            .update() == 1

    /** How many keys are encrypted under each master-key version in use. */
    fun countByMasterKeyVersion(): Map<Int, Int> =
        jdbc
            .sql(
                """
                select master_key_version, count(*) as keys from credential_key
                where master_key_version is not null group by master_key_version
                """.trimIndent(),
            ).query { rs, _ -> rs.getInt("master_key_version") to rs.getInt("keys") }
            .list()
            .toMap()

    private fun ResultSet.toStoredKey() =
        StoredKey(
            id = KeyId(getObject("id", UUID::class.java)),
            credentialId = CredentialId(getObject("credential_id", UUID::class.java)),
            encrypted =
                EncryptedKey(
                    ciphertext = getBytes("ciphertext"),
                    nonce = getBytes("nonce"),
                    salt = getBytes("salt"),
                    masterKeyVersion = getInt("master_key_version"),
                    algorithm = getString("algorithm"),
                ),
        )

    private companion object {
        const val COLUMNS = "id, credential_id, algorithm, master_key_version, salt, nonce, ciphertext"
    }
}
