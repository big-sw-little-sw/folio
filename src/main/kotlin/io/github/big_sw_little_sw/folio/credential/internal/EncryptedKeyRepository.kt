package io.github.big_sw_little_sw.folio.credential.internal

import io.github.big_sw_little_sw.folio.credential.CredentialId
import io.github.big_sw_little_sw.folio.credential.CredentialStatus
import io.github.big_sw_little_sw.folio.credential.KeyId
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

/** A usable key with its credential's status and Git instance and the key's public key, as one consistent read. */
class UsableKey(
    val credentialStatus: CredentialStatus,
    val gitInstance: String,
    val publicKey: String,
    val stored: StoredKey,
)

/** The encryption columns of `credential_key`: reads of encrypted private keys and their re-encryption. */
@Repository
class EncryptedKeyRepository(
    private val jdbc: JdbcClient,
) {
    /**
     * The credential's active key, or null if the credential does not exist; an existing credential always has
     * one. A single statement reads from one snapshot, so a concurrent activation or replacement cannot pair
     * the status, public key and ciphertext of different moments.
     */
    fun findActive(credentialId: CredentialId): UsableKey? = findUsable(credentialId, "k.status = 'ACTIVE'", null)

    /** The key [keyId] if it is the credential's pending key, or null; read as one snapshot like [findActive]. */
    fun findPending(
        credentialId: CredentialId,
        keyId: KeyId,
    ): UsableKey? = findUsable(credentialId, "k.status = 'PENDING' and k.id = :keyId", keyId)

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

    private fun findUsable(
        credentialId: CredentialId,
        keyCondition: String,
        keyId: KeyId?,
    ): UsableKey? {
        val statement =
            jdbc
                .sql(
                    """
                    select c.status as credential_status, c.git_instance, k.public_key,
                           k.id, k.credential_id, k.algorithm, k.master_key_version, k.salt, k.nonce, k.ciphertext
                    from credential c
                    join credential_key k on k.credential_id = c.id and $keyCondition
                    where c.id = :credentialId
                    """.trimIndent(),
                ).param("credentialId", credentialId.value)
        if (keyId != null) statement.param("keyId", keyId.value)
        return statement
            .query { rs, _ ->
                UsableKey(
                    CredentialStatus.valueOf(rs.getString("credential_status")),
                    rs.getString("git_instance"),
                    rs.getString("public_key"),
                    rs.toStoredKey(),
                )
            }.optional()
            .orElse(null)
    }

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
