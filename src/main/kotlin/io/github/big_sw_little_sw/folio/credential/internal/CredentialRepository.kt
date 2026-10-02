package io.github.big_sw_little_sw.folio.credential.internal

import io.github.big_sw_little_sw.folio.credential.Credential
import io.github.big_sw_little_sw.folio.credential.CredentialId
import io.github.big_sw_little_sw.folio.credential.CredentialKey
import io.github.big_sw_little_sw.folio.credential.CredentialName
import io.github.big_sw_little_sw.folio.credential.CredentialStatus
import io.github.big_sw_little_sw.folio.credential.DuplicateCredentialNameException
import io.github.big_sw_little_sw.folio.credential.KeyId
import io.github.big_sw_little_sw.folio.credential.KeyStatus
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

/**
 * Rows of `credential`, and of `credential_key` apart from reading encrypted private keys, which only
 * [EncryptedKeyRepository] does. A `RETIRED` key has no encryption columns.
 */
@Repository
class CredentialRepository(
    private val jdbc: JdbcClient,
) {
    /** A new uuidv7 ID. Keys need their ID before insert because the associated data includes it. */
    fun newKeyId(): KeyId = KeyId(jdbc.sql("select uuidv7()").query(UUID::class.java).single())

    // The instance-and-name unique constraint is the only unique key a caller can violate; IDs come from uuidv7().
    fun insert(
        gitInstance: String,
        name: CredentialName,
    ): CredentialId =
        try {
            CredentialId(
                jdbc
                    .sql(
                        """
                        insert into credential (git_instance, name, status) values (:gitInstance, :name, :status)
                        returning id
                        """.trimIndent(),
                    ).param("gitInstance", gitInstance)
                    .param("name", name.value)
                    .param("status", CredentialStatus.ENABLED.name)
                    .query(UUID::class.java)
                    .single(),
            )
        } catch (_: DuplicateKeyException) {
            throw DuplicateCredentialNameException(gitInstance, name)
        }

    /**
     * Locks the credential row until the transaction ends and returns its status, or null if there is none.
     * Every change to a credential's keys takes this lock first; see `CredentialService`.
     */
    fun lock(id: CredentialId): CredentialStatus? =
        jdbc
            .sql("select status from credential where id = :id for update")
            .param("id", id.value)
            .query { rs, _ -> CredentialStatus.valueOf(rs.getString("status")) }
            .optional()
            .orElse(null)

    fun updateStatus(
        id: CredentialId,
        status: CredentialStatus,
    ) {
        jdbc
            .sql("update credential set status = :status where id = :id")
            .param("id", id.value)
            .param("status", status.name)
            .update()
    }

    fun findById(id: CredentialId): Credential? {
        val keys = findKeys(id)
        return jdbc
            .sql("select $COLUMNS from credential where id = :id")
            .param("id", id.value)
            .query { rs, _ -> rs.toCredential { keys } }
            .optional()
            .orElse(null)
    }

    /** Ordered by ID, which is creation order (uuidv7). */
    fun findAll(): List<Credential> {
        val keys =
            jdbc
                .sql("select credential_id, $KEY_COLUMNS from credential_key order by id")
                .query { rs, _ -> CredentialId(rs.getObject("credential_id", UUID::class.java)) to rs.toKey() }
                .list()
                .groupBy({ it.first }, { it.second })
        return jdbc
            .sql("select $COLUMNS from credential order by id")
            .query { rs, _ -> rs.toCredential { keys[it].orEmpty() } }
            .list()
    }

    fun insertKey(
        id: KeyId,
        credentialId: CredentialId,
        status: KeyStatus,
        publicKey: OpenSshPublicKey,
        encrypted: EncryptedKey,
    ) {
        jdbc
            .sql(
                """
                insert into credential_key (id, credential_id, status, public_key, fingerprint,
                                            algorithm, master_key_version, salt, nonce, ciphertext)
                values (:id, :credentialId, :status, :publicKey, :fingerprint,
                        :algorithm, :masterKeyVersion, :salt, :nonce, :ciphertext)
                """.trimIndent(),
            ).param("id", id.value)
            .param("credentialId", credentialId.value)
            .param("status", status.name)
            .param("publicKey", publicKey.text)
            .param("fingerprint", publicKey.fingerprint)
            .param("algorithm", encrypted.algorithm)
            .param("masterKeyVersion", encrypted.masterKeyVersion)
            .param("salt", encrypted.salt)
            .param("nonce", encrypted.nonce)
            .param("ciphertext", encrypted.ciphertext)
            .update()
    }

    /** Makes the key with [status] `RETIRED` and wipes its encrypted private key; does nothing if there is none. */
    fun retire(
        credentialId: CredentialId,
        status: KeyStatus,
    ) {
        jdbc
            .sql(
                """
                update credential_key
                set status = 'RETIRED', algorithm = null, master_key_version = null, salt = null, nonce = null,
                    ciphertext = null
                where credential_id = :credentialId and status = :status
                """.trimIndent(),
            ).param("credentialId", credentialId.value)
            .param("status", status.name)
            .update()
    }

    fun activate(id: KeyId) {
        jdbc.sql("update credential_key set status = 'ACTIVE' where id = :id").param("id", id.value).update()
    }

    private fun findKeys(credentialId: CredentialId): List<CredentialKey> =
        jdbc
            .sql("select $KEY_COLUMNS from credential_key where credential_id = :credentialId order by id")
            .param("credentialId", credentialId.value)
            .query { rs, _ -> rs.toKey() }
            .list()

    private fun ResultSet.toCredential(keysOf: (CredentialId) -> List<CredentialKey>): Credential {
        val id = CredentialId(getObject("id", UUID::class.java))
        return Credential(
            id,
            getString("git_instance"),
            CredentialName(getString("name")),
            CredentialStatus.valueOf(getString("status")),
            keysOf(id),
        )
    }

    private fun ResultSet.toKey() =
        CredentialKey(
            id = KeyId(getObject("id", UUID::class.java)),
            status = KeyStatus.valueOf(getString("status")),
            publicKey = getString("public_key"),
            fingerprint = getString("fingerprint"),
        )

    private companion object {
        const val COLUMNS = "id, git_instance, name, status"
        const val KEY_COLUMNS = "id, status, public_key, fingerprint"
    }
}
