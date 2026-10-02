package io.github.big_sw_little_sw.folio.audit

import io.github.big_sw_little_sw.folio.TestcontainersConfiguration
import io.github.big_sw_little_sw.folio.configset.ConfigSetPolicyService
import io.github.big_sw_little_sw.folio.configset.ConfigSetService
import io.github.big_sw_little_sw.folio.configset.exampleSource
import io.github.big_sw_little_sw.folio.credential.CredentialService
import io.github.big_sw_little_sw.folio.namespace.DuplicateSlugException
import io.github.big_sw_little_sw.folio.namespace.Namespace
import io.github.big_sw_little_sw.folio.namespace.NamespaceNotEmptyException
import io.github.big_sw_little_sw.folio.namespace.NamespacePolicyService
import io.github.big_sw_little_sw.folio.namespace.NamespaceService
import io.github.big_sw_little_sw.folio.namespace.Slug
import io.github.big_sw_little_sw.folio.policy.Action
import io.github.big_sw_little_sw.folio.policy.PermissionDeniedException
import io.github.big_sw_little_sw.folio.policy.Rule
import io.github.big_sw_little_sw.folio.policy.Subject
import io.github.big_sw_little_sw.folio.security.SUPER_ADMIN
import io.github.big_sw_little_sw.folio.security.authenticateAs
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Audit records of namespace, ConfigSet and rule operations (ADR 0038), written by the operations' services. */
@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class AuditIntegrationTest(
    @Autowired private val namespaces: NamespaceService,
    @Autowired private val namespaceRules: NamespacePolicyService,
    @Autowired private val configSets: ConfigSetService,
    @Autowired private val configSetRules: ConfigSetPolicyService,
    @Autowired private val credentials: CredentialService,
    @Autowired private val jdbc: JdbcClient,
) {
    private val records = AuditRecords(jdbc)

    @BeforeEach
    fun setUp() {
        jdbc.sql("delete from config_set").update()
        jdbc.sql("delete from namespace_closure").update()
        jdbc.sql("delete from namespace").update()
        authenticateAs(SUPER_ADMIN)
    }

    @AfterEach
    fun clearAuthentication() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `each namespace operation writes one record with the super admin and the path at the time`() {
        val engineering = namespaces.create(null, Slug("engineering"))
        val platform = namespaces.create(null, Slug("platform"))
        val team = namespaces.create(engineering.id, Slug("team"))

        namespaces.rename(team.id, Slug("squad"))
        namespaces.move(team.id, platform.id)
        namespaces.delete(team.id)

        val rows = records.of(team.id.value)
        assertEquals(
            listOf(
                Triple("NAMESPACE_CREATED", "engineering/team", emptyMap()),
                Triple("NAMESPACE_RENAMED", "engineering/squad", mapOf("previousPath" to "engineering/team")),
                Triple("NAMESPACE_MOVED", "platform/squad", mapOf("previousPath" to "engineering/squad")),
                Triple("NAMESPACE_DELETED", "platform/squad", emptyMap<String, Any?>()),
            ),
            rows.map { Triple(it.action, it.path, it.details) },
        )
        assertEquals(
            setOf(listOf("AUTHENTICATED", SUPER_ADMIN, true, "NAMESPACE")),
            rows.map { listOf(it.actorType, it.actorSubject, it.actorSuperAdmin, it.resourceType) }.toSet(),
        )
    }

    @Test
    fun `each ConfigSet operation writes one record with the path at the time`() {
        val engineering = namespaces.create(null, Slug("engineering"))
        val platform = namespaces.create(null, Slug("platform"))
        val source = credentials.exampleSource()
        val configSet = configSets.create(engineering.id, Slug("app"), source)

        configSets.rename(configSet.id, Slug("service"))
        configSets.move(configSet.id, platform.id)
        configSets.delete(configSet.id)

        val rows = records.of(configSet.id.value)
        assertEquals(
            listOf("CONFIG_SET_CREATED", "CONFIG_SET_RENAMED", "CONFIG_SET_MOVED", "CONFIG_SET_DELETED"),
            rows.map { it.action },
        )
        assertEquals(
            listOf("engineering/app", "engineering/service", "platform/service", "platform/service"),
            rows.map { it.path },
        )
        assertEquals(
            mapOf(
                "credentialId" to source.credentialId.value.toString(),
                "repositoryPath" to "org/repo.git",
                "branch" to "main",
                "rootPath" to "config",
            ),
            rows.first().details,
        )
        assertEquals(mapOf("previousPath" to "engineering/service"), rows[2].details)
        rows.forEach { assertEquals("CONFIG_SET", it.resourceType) }
    }

    @Test
    fun `rule writes are recorded on the namespace or ConfigSet they attach to`() {
        val namespace = namespaces.create(null, Slug("engineering"))
        val configSet = configSets.create(namespace.id, Slug("app"), credentials.exampleSource())
        val rule = Rule(Action.CONFIG_ITEM_READ, setOf(Subject.Group("readers"), Subject.Authenticated))

        namespaceRules.putRule(namespace.id, rule)
        namespaceRules.deleteRule(namespace.id, Action.CONFIG_ITEM_READ)
        configSetRules.putRule(configSet.id, rule)
        configSetRules.deleteRule(configSet.id, Action.CONFIG_ITEM_READ)

        val put = mapOf("ruleAction" to "CONFIG_ITEM_READ", "subjects" to listOf("authenticated", "group:readers"))
        val deleted = mapOf("ruleAction" to "CONFIG_ITEM_READ")
        listOf(namespace.id.value to "engineering", configSet.id.value to "engineering/app").forEach { (id, path) ->
            val rows = records.of(id).filter { it.action.startsWith("POLICY_") }
            assertEquals(listOf("POLICY_RULE_PUT", "POLICY_RULE_DELETED"), rows.map { it.action })
            assertEquals(listOf(put, deleted), rows.map { it.details })
            assertEquals(listOf(path, path), rows.map { it.path })
        }
    }

    @Test
    fun `a caller who is not a super admin is recorded with its application ID`() {
        val namespace = namespaces.create(null, Slug("engineering"))
        namespaceRules.putRule(namespace.id, Rule(Action.NAMESPACE_RENAME, setOf(Subject.User("alice"))))
        authenticateAsApplication("alice", "deployer")

        namespaces.rename(namespace.id, Slug("eng"))

        val renamed = records.of(namespace.id.value).single { it.action == "NAMESPACE_RENAMED" }
        assertEquals("alice", renamed.actorSubject)
        assertEquals("deployer", renamed.actorApplicationId)
        assertEquals(false, renamed.actorSuperAdmin)
    }

    @Test
    fun `denied and conflicting operations write nothing`() {
        val engineering = namespaces.create(null, Slug("engineering"))
        val platform = namespaces.create(null, Slug("platform"))
        namespaces.create(engineering.id, Slug("team"))

        assertFailsWith<NamespaceNotEmptyException> { namespaces.delete(engineering.id) }
        assertFailsWith<DuplicateSlugException> { namespaces.rename(platform.id, Slug("engineering")) }
        authenticateAs("alice")
        assertFailsWith<PermissionDeniedException> { namespaces.rename(engineering.id, Slug("eng")) }

        assertEquals(listOf("NAMESPACE_CREATED"), records.of(engineering.id.value).map { it.action })
        assertEquals(listOf("NAMESPACE_CREATED"), records.of(platform.id.value).map { it.action })
    }

    @Test
    fun `an operation whose audit record cannot be written fails and changes nothing`() {
        val namespace = namespaces.create(null, Slug("engineering"))
        // NOT VALID: records of earlier tests are not checked, only new ones.
        jdbc
            .sql(
                "alter table audit_event add constraint audit_test_failure " +
                    "check (action <> 'NAMESPACE_RENAMED') not valid",
            ).update()
        try {
            assertFailsWith<DataIntegrityViolationException> { namespaces.rename(namespace.id, Slug("eng")) }
        } finally {
            jdbc.sql("alter table audit_event drop constraint audit_test_failure").update()
        }

        assertEquals(Namespace(namespace.id, null, Slug("engineering")), namespaces.get(namespace.id))
        assertEquals(listOf("NAMESPACE_CREATED"), records.of(namespace.id.value).map { it.action })
    }

    private fun authenticateAsApplication(
        subject: String,
        applicationId: String,
    ) {
        val jwt =
            Jwt
                .withTokenValue("test-token")
                .header("alg", "none")
                .subject(subject)
                .claim("azp", applicationId)
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }
}
