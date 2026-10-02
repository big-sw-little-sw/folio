package io.github.big_sw_little_sw.folio.audit.internal

import io.github.big_sw_little_sw.folio.configset.ConfigSetCreated
import io.github.big_sw_little_sw.folio.configset.ConfigSetId
import io.github.big_sw_little_sw.folio.configset.ConfigSetMoved
import io.github.big_sw_little_sw.folio.configset.ConfigSetPath
import io.github.big_sw_little_sw.folio.configset.ConfigSetRuleDeleted
import io.github.big_sw_little_sw.folio.credential.Credential
import io.github.big_sw_little_sw.folio.credential.CredentialChange
import io.github.big_sw_little_sw.folio.credential.CredentialChanged
import io.github.big_sw_little_sw.folio.credential.CredentialId
import io.github.big_sw_little_sw.folio.credential.CredentialKey
import io.github.big_sw_little_sw.folio.credential.CredentialName
import io.github.big_sw_little_sw.folio.credential.CredentialStatus
import io.github.big_sw_little_sw.folio.credential.KeyId
import io.github.big_sw_little_sw.folio.credential.KeyStatus
import io.github.big_sw_little_sw.folio.credential.MasterKeyUsage
import io.github.big_sw_little_sw.folio.credential.MasterKeysReencrypted
import io.github.big_sw_little_sw.folio.namespace.NamespaceId
import io.github.big_sw_little_sw.folio.namespace.NamespaceRenamed
import io.github.big_sw_little_sw.folio.namespace.NamespaceRulePut
import io.github.big_sw_little_sw.folio.namespace.Slug
import io.github.big_sw_little_sw.folio.policy.Action
import io.github.big_sw_little_sw.folio.policy.Rule
import io.github.big_sw_little_sw.folio.policy.Subject
import io.github.big_sw_little_sw.folio.source.Branch
import io.github.big_sw_little_sw.folio.source.RepositoryPath
import io.github.big_sw_little_sw.folio.source.SourceDefinition
import io.github.big_sw_little_sw.folio.source.SourcePath
import io.github.big_sw_little_sw.folio.sync.SyncFailed
import io.github.big_sw_little_sw.folio.sync.SyncRequested
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuditEntriesTest {
    private val namespaceId = NamespaceId(UUID.randomUUID())
    private val configSetId = ConfigSetId(UUID.randomUUID())
    private val path = ConfigSetPath(listOf(Slug("engineering"), Slug("ai")), Slug("app"))

    @Test
    fun `a namespace rename records the path after it and the previous path`() {
        val event = NamespaceRenamed(namespaceId, listOf(Slug("a"), Slug("new")), listOf(Slug("a"), Slug("old")))

        assertEquals(
            AuditEntry(
                AuditAction.NAMESPACE_RENAMED,
                ResourceType.NAMESPACE,
                namespaceId.value,
                "a/new",
                mapOf("previousPath" to "a/old"),
            ),
            event.toAuditEntry(),
        )
    }

    @Test
    fun `a rule put records the rule's action and its subjects in a stable order`() {
        val rule = Rule(Action.CONFIG_ITEM_READ, setOf(Subject.User("bob"), Subject.Group("alpha"), Subject.Public))

        val entry = NamespaceRulePut(namespaceId, listOf(Slug("a")), rule).toAuditEntry()

        assertEquals(AuditAction.POLICY_RULE_PUT, entry.action)
        assertEquals(
            mapOf("ruleAction" to "CONFIG_ITEM_READ", "subjects" to listOf("group:alpha", "public", "user:bob")),
            entry.details,
        )
    }

    @Test
    fun `ConfigSet events record the ConfigSet's path, and its source on creation`() {
        val credentialId = UUID.randomUUID()
        val source =
            SourceDefinition(
                CredentialId(credentialId),
                RepositoryPath("org/repo.git"),
                Branch("main"),
                SourcePath.parse("config"),
            )
        val previous = ConfigSetPath(listOf(Slug("engineering")), Slug("app"))

        val created = ConfigSetCreated(configSetId, path, source).toAuditEntry()
        val moved = ConfigSetMoved(configSetId, path, previous).toAuditEntry()
        val ruleDeleted = ConfigSetRuleDeleted(configSetId, path, Action.CONFIG_SET_VIEW).toAuditEntry()

        assertEquals(ResourceType.CONFIG_SET, created.resourceType)
        assertEquals("engineering/ai/app", created.path)
        assertEquals(
            mapOf(
                "credentialId" to credentialId,
                "repositoryPath" to "org/repo.git",
                "branch" to "main",
                "rootPath" to "config",
            ),
            created.details,
        )
        assertEquals(mapOf("previousPath" to "engineering/app"), moved.details)
        assertEquals(AuditAction.POLICY_RULE_DELETED, ruleDeleted.action)
        assertEquals(mapOf("ruleAction" to "CONFIG_SET_VIEW"), ruleDeleted.details)
    }

    @Test
    fun `credential changes record changed keys by ID, fingerprint and status, never the public key`() {
        val retired = key(KeyStatus.RETIRED)
        val active = key(KeyStatus.ACTIVE)
        val id = CredentialId(UUID.randomUUID())
        val credential =
            Credential(id, "github", CredentialName("bot"), CredentialStatus.ENABLED, listOf(retired, active))

        val entry =
            CredentialChanged(
                CredentialChange.KEY_ACTIVATED,
                credential,
                listOf(retired, active),
            ).toAuditEntry()

        assertEquals(
            AuditEntry(
                AuditAction.CREDENTIAL_KEY_ACTIVATED,
                ResourceType.CREDENTIAL,
                id.value,
                null,
                mapOf("gitInstance" to "github", "name" to "bot", "keys" to listOf(details(retired), details(active))),
            ),
            entry,
        )
        assertFalse(entry.details.toString().contains("ssh-ed25519"))
    }

    @Test
    fun `a re-encryption records counts per master-key version on the key ring`() {
        val entry = MasterKeysReencrypted(mapOf(1 to 3), MasterKeyUsage(2, mapOf(1 to 0, 2 to 5))).toAuditEntry()

        assertEquals(
            AuditEntry(
                AuditAction.MASTER_KEYS_REENCRYPTED,
                ResourceType.MASTER_KEY_RING,
                null,
                null,
                mapOf(
                    "activeVersion" to 2,
                    "reencryptedByVersion" to mapOf(1 to 3),
                    "keysByVersion" to mapOf(1 to 0, 2 to 5),
                ),
            ),
            entry,
        )
    }

    @Test
    fun `sync requests are a caller's, sync outcomes the system's`() {
        val requested = SyncRequested(configSetId, path).toAuditEntry()
        val failed = SyncFailed(configSetId, path, "AUTH_FAILED", null).toAuditEntry()

        assertFalse(requested.action.bySystem)
        assertTrue(failed.action.bySystem)
        assertEquals(mapOf("failureCode" to "AUTH_FAILED", "previousFailureCode" to null), failed.details)
        assertEquals("engineering/ai/app", failed.path)
    }

    private fun details(key: CredentialKey) =
        mapOf("keyId" to key.id.value, "fingerprint" to key.fingerprint, "status" to key.status.name)

    private fun key(status: KeyStatus) =
        CredentialKey(KeyId(UUID.randomUUID()), status, "ssh-ed25519 AAAA", "SHA256:${UUID.randomUUID()}")
}
