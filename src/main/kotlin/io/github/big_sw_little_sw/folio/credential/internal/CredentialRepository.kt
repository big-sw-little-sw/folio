package io.github.big_sw_little_sw.folio.credential.internal

import io.github.big_sw_little_sw.folio.catalog.CatalogException
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.Slug
import io.github.big_sw_little_sw.folio.credential.CredentialException
import io.github.big_sw_little_sw.folio.credential.CredentialId
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

/**
 * Reads and writes the `credential` rows. [KeyRepository] owns the `credential_key` rows. This repository owns the
 * unique constraint on names and the foreign key to `namespace`, so it changes their violations to domain errors
 * (design 8.6).
 */
@Repository
class CredentialRepository(private val jdbc: JdbcClient) {
    /**
     * IDs come from `uuidv7()`, so only a repeated name in the Namespace can violate a unique constraint. A
     * foreign-key violation means that a parallel transaction deleted the Namespace after the caller found it.
     */
    fun insert(namespace: NamespaceId, name: Slug, gitInstance: Slug): CredentialId = try {
        jdbc
            .sql(
                """
                    insert into credential (namespace_id, name, git_instance, status)
                    values (:namespaceId, :name, :gitInstance, 'ENABLED')
                    returning id
                """.trimIndent(),
            ).param("namespaceId", namespace.value)
            .param("name", name.value)
            .param("gitInstance", gitInstance.value)
            .query(UUID::class.java)
            .single()
            .let(::CredentialId)
    } catch (_: DuplicateKeyException) {
        throw CredentialException.NameTaken(name, namespace)
    } catch (_: DataIntegrityViolationException) {
        throw CatalogException.NamespaceNotFound(namespace)
    }

    fun find(id: CredentialId): CredentialRow? = findRow("select $COLUMNS from credential where id = :id", id)

    /**
     * Locks the credential row until the transaction ends, and returns the row. Each change of a credential or of its
     * keys calls this method first (design 11.1). Thus changes of one credential run one at a time. For example, two
     * regenerations cannot both see that no key is pending.
     */
    fun lock(id: CredentialId): CredentialRow? =
        findRow("select $COLUMNS from credential where id = :id for update", id)

    /** The credentials of the Namespace, in name order. */
    fun inNamespace(namespace: NamespaceId): List<CredentialRow> = jdbc
        .sql("select $COLUMNS from credential where namespace_id = :namespaceId order by name")
        .param("namespaceId", namespace.value)
        .query { rs, _ -> rs.toRow() }
        .list()

    fun disable(id: CredentialId) {
        jdbc.sql("update credential set status = 'DISABLED' where id = :id").param("id", id.value).update()
    }

    /**
     * Deletes the credential. A foreign key that cascades deletes its keys. The Git slice adds a foreign key from
     * `git_source` that does not cascade. This method then changes its violation to `CREDENTIAL_IN_USE` (design 11.1).
     */
    fun delete(id: CredentialId) {
        jdbc.sql("delete from credential where id = :id").param("id", id.value).update()
    }

    private fun findRow(sql: String, id: CredentialId): CredentialRow? = jdbc
        .sql(sql)
        .param("id", id.value)
        .query { rs, _ -> rs.toRow() }
        .optional()
        .orElse(null)

    private fun ResultSet.toRow() = CredentialRow(
        id = CredentialId(getObject("id", UUID::class.java)),
        namespaceId = NamespaceId(getObject("namespace_id", UUID::class.java)),
        name = Slug(getString("name")),
        gitInstance = Slug(getString("git_instance")),
        status = CredentialStatus.valueOf(getString("status")),
    )

    private companion object {
        const val COLUMNS = "id, namespace_id, name, git_instance, status"
    }
}
