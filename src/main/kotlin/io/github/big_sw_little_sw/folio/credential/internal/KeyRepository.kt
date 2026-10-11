package io.github.big_sw_little_sw.folio.credential.internal

import io.github.big_sw_little_sw.folio.catalog.Slug
import io.github.big_sw_little_sw.folio.credential.CredentialId
import io.github.big_sw_little_sw.folio.credential.KeyId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

/** An encrypted private key with its public key and the IDs that its associated data contains. */
class StoredKey(val id: KeyId, val credentialId: CredentialId, val publicKey: String, val encrypted: EncryptedKey)

/** A credential with the key that a lookup found. [key] is null if the credential has no such key. */
class CredentialKey(val credentialStatus: CredentialStatus, val gitInstance: Slug, val key: StoredKey?)

/**
 * Reads and writes the `credential_key` rows: their status, their public keys and their encryption columns. Only
 * `CredentialKeys` and the re-encryption read encrypted private keys.
 *
 * The partial unique indexes permit one `ACTIVE` key and one `PENDING` key for each credential (design 11.1). This
 * repository orders its updates so that no statement violates them. The caller locks the credential row first.
 */
@Repository
class KeyRepository(private val jdbc: JdbcClient) {
    /** A new key ID. The encryption needs the ID before the insert, and `uuidv7()` makes all IDs (design 5.2). */
    fun newKeyId(): KeyId = KeyId(jdbc.sql("select uuidv7()").query(UUID::class.java).single())

    fun insert(
        id: KeyId,
        credential: CredentialId,
        status: KeyStatus,
        publicKey: OpenSshPublicKey,
        encrypted: EncryptedKey,
    ) {
        jdbc
            .sql(
                """
                insert into credential_key (
                    id, credential_id, status, public_key, fingerprint,
                    algorithm, master_key_version, salt, nonce, ciphertext
                ) values (
                    :id, :credentialId, :status, :publicKey, :fingerprint,
                    :algorithm, :masterKeyVersion, :salt, :nonce, :ciphertext
                )
                """.trimIndent(),
            ).param("id", id.value)
            .param("credentialId", credential.value)
            .param("status", status.name)
            .param("publicKey", publicKey.text)
            .param("fingerprint", publicKey.fingerprint)
            .encryption(encrypted)
            .update()
    }

    /**
     * The keys of each credential without their encryption, in creation order. A credential without keys has no entry.
     */
    fun inCredentials(credentials: Collection<CredentialId>): Map<CredentialId, List<Key>> {
        if (credentials.isEmpty()) return emptyMap()
        return jdbc
            .sql(
                """
                select credential_id, id, status, public_key, fingerprint from credential_key
                where credential_id = any(cast(:ids as uuid[])) order by id
                """.trimIndent(),
            ).param("ids", credentials.map { it.value }.toTypedArray())
            .query { rs, _ -> CredentialId(rs.getObject("credential_id", UUID::class.java)) to rs.toKey() }
            .list()
            .groupBy({ it.first }, { it.second })
    }

    /**
     * The credential and its key with [status], or null if the credential does not exist. [status] is `ACTIVE` or
     * `PENDING`, so the partial unique indexes permit a maximum of one key. One statement reads the credential and the
     * key. Thus a parallel key change cannot mix the values of two keys.
     */
    fun find(credential: CredentialId, status: KeyStatus): CredentialKey? = jdbc
        .sql(
            """
                select c.status as credential_status, c.git_instance, $STORED_COLUMNS
                from credential c
                left join credential_key k on k.credential_id = c.id and k.status = :status
                where c.id = :credentialId
            """.trimIndent(),
        ).param("credentialId", credential.value)
        .param("status", status.name)
        .query { rs, _ ->
            CredentialKey(
                credentialStatus = CredentialStatus.valueOf(rs.getString("credential_status")),
                gitInstance = Slug(rs.getString("git_instance")),
                key = rs.getObject("id", UUID::class.java)?.let { rs.toStoredKey() },
            )
        }.optional()
        .orElse(null)

    /**
     * Retires the key of the credential that has [status], if one exists. Each retirement erases the encrypted private
     * key and keeps the public key and the fingerprint (design 11.1).
     */
    fun retire(credential: CredentialId, status: KeyStatus) {
        jdbc
            .sql("$RETIRE and status = :status")
            .param("credentialId", credential.value)
            .param("status", status.name)
            .update()
    }

    /** Retires the active key and the pending key of the credential, so that a new key can be active. */
    fun retireAll(credential: CredentialId) {
        jdbc.sql("$RETIRE and status in ('ACTIVE', 'PENDING')").param("credentialId", credential.value).update()
    }

    /** Activates [key] and retires the previous active key of the credential. */
    fun activate(credential: CredentialId, key: KeyId) {
        // PostgreSQL checks the partial unique index at each statement, not at commit. Thus the retirement of the
        // previous active key must run first.
        retire(credential, KeyStatus.ACTIVE)
        jdbc
            .sql("update credential_key set status = 'ACTIVE' where id = :id and credential_id = :credentialId")
            .param("id", key.value)
            .param("credentialId", credential.value)
            .update()
    }

    /** The keys that a master-key version other than [version] encrypts. */
    fun notUnder(version: Int): List<StoredKey> = jdbc
        .sql("select $STORED_COLUMNS from credential_key k where k.master_key_version <> :version order by k.id")
        .param("version", version)
        .query { rs, _ -> rs.toStoredKey() }
        .list()

    /**
     * Replaces the encryption of the key if [readVersion] still encrypts it. The update is one statement and thus one
     * transaction. It returns false if a parallel re-encryption changed the row or a retirement erased it. In both
     * cases, nothing remains to do. Thus parallel runs and repeated runs are safe (design 11.3).
     */
    fun replaceEncryption(key: KeyId, readVersion: Int, encrypted: EncryptedKey): Boolean = jdbc
        .sql(
            """
                update credential_key
                set algorithm = :algorithm, master_key_version = :masterKeyVersion, salt = :salt, nonce = :nonce,
                    ciphertext = :ciphertext
                where id = :id and master_key_version = :readVersion
            """.trimIndent(),
        ).param("id", key.value)
        .param("readVersion", readVersion)
        .encryption(encrypted)
        .update() == 1

    /** The number of encrypted keys for each master-key version that a key uses. Retired keys have no version. */
    fun countByVersion(): Map<Int, Int> = jdbc
        .sql(
            """
                select master_key_version, count(*) as keys from credential_key
                where master_key_version is not null group by master_key_version
            """.trimIndent(),
        ).query { rs, _ -> rs.getInt("master_key_version") to rs.getInt("keys") }
        .list()
        .toMap()

    /**
     * Binds the five encryption columns. The insert and the re-encryption share this function, so a change to the
     * columns changes one place only.
     */
    private fun JdbcClient.StatementSpec.encryption(encrypted: EncryptedKey): JdbcClient.StatementSpec =
        param("algorithm", encrypted.algorithm)
            .param("masterKeyVersion", encrypted.masterKeyVersion)
            .param("salt", encrypted.salt)
            .param("nonce", encrypted.nonce)
            .param("ciphertext", encrypted.ciphertext)

    private fun ResultSet.toKey() = Key(
        id = KeyId(getObject("id", UUID::class.java)),
        status = KeyStatus.valueOf(getString("status")),
        publicKey = getString("public_key"),
        fingerprint = getString("fingerprint"),
    )

    private fun ResultSet.toStoredKey() = StoredKey(
        id = KeyId(getObject("id", UUID::class.java)),
        credentialId = CredentialId(getObject("credential_id", UUID::class.java)),
        publicKey = getString("public_key"),
        encrypted =
        EncryptedKey(
            algorithm = getString("algorithm"),
            masterKeyVersion = getInt("master_key_version"),
            salt = getBytes("salt"),
            nonce = getBytes("nonce"),
            ciphertext = getBytes("ciphertext"),
        ),
    )

    private companion object {
        const val STORED_COLUMNS =
            "k.id, k.credential_id, k.public_key, k.algorithm, k.master_key_version, k.salt, k.nonce, k.ciphertext"

        /**
         * The start of each retirement statement. A check constraint requires null encryption columns on a retired
         * key, so each retirement sets them to null.
         */
        const val RETIRE =
            "update credential_key set status = 'RETIRED', algorithm = null, master_key_version = null, salt = null, " +
                "nonce = null, ciphertext = null where credential_id = :credentialId"
    }
}
