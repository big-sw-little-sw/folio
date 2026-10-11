package io.github.big_sw_little_sw.folio.git

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.git.internal.FailureCode
import io.github.big_sw_little_sw.folio.git.internal.RepositoryCache
import io.github.big_sw_little_sw.folio.git.internal.SyncRepository
import io.github.big_sw_little_sw.testing.CounterGrowth
import io.github.big_sw_little_sw.testing.GitIntegrationTest
import io.github.big_sw_little_sw.testing.SshGitServer
import io.github.big_sw_little_sw.testing.TestCatalog.Companion.SUPER_ADMIN
import io.github.big_sw_little_sw.testing.TestGit
import io.github.big_sw_little_sw.testing.bearer
import io.github.big_sw_little_sw.testing.total
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.BaseUnits
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * These tests check the meters of the `git` module (design 15.1) through sync, the check and the cache sweep: the sync
 * attempts, the fetch duration of sync and the gauges of the cache. They also check that Folio has only the meters of
 * design 15.1, and that no meter of Folio has an ID, a path, a subject or a commit as a tag.
 * OnDemandFetchIntegrationTest checks the meter of the fetch on demand.
 */
@GitIntegrationTest
class GitMetricsIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val git: TestGit,
    @Autowired private val cache: RepositoryCache,
    @Autowired private val syncs: SyncRepository,
    @Autowired private val meters: MeterRegistry,
) {
    @BeforeEach
    fun setUp() {
        SshGitServer.revokeAll()
        SshGitServer.createRepository("configs")
    }

    @Test
    fun `sync attempts count by outcome and code, and each fetch of sync is timed`() {
        val configSet = git.configSet(git.authorizedCredential().id)
        val published = attempts("published", "none")
        val unchanged = attempts("unchanged", "none")
        val failed = attempts("failed", FailureCode.AUTH_FAILED.name)
        val fetches = fetchDuration()

        git.publishTip(configSet)
        git.publishTip(configSet)
        SshGitServer.revokeAll()
        git.sync(configSet)

        assertEquals(listOf(1.0, 1.0, 1.0), listOf(published, unchanged, failed).map { it.added() })
        assertEquals(fetches + 3, fetchDuration())
    }

    @Test
    fun `the check does not change the meters of sync`() {
        val configSet = git.configSet(git.authorizedCredential().id)
        val attempts = meters.total(ATTEMPTS)
        val fetches = fetchDuration()

        mvc
            .post("/api/v1/admin/configsets/${configSet.toApiId()}/git:check") { bearer(SUPER_ADMIN) }
            .andExpect {
                status { isOk() }
                jsonPath("$.ok") { value(true) }
            }

        assertEquals(attempts, meters.total(ATTEMPTS))
        assertEquals(fetches, fetchDuration())
    }

    @Test
    fun `the cache gauges give the size and the number of the repositories that stay after the last sweep`() {
        val configSet = git.configSet(git.authorizedCredential().id)
        git.publishTip(configSet)
        cache.sweep(syncs::existing)
        val size = gauge(CACHE_SIZE)
        val repositories = gauge(CACHE_REPOSITORIES)

        delete(configSet)
        assertEquals(size, gauge(CACHE_SIZE), "The gauges change only at a sweep.")
        cache.sweep(syncs::existing)

        assertTrue(size > 0)
        assertEquals(
            BaseUnits.BYTES,
            meters
                .get(CACHE_SIZE)
                .gauge()
                .id.baseUnit,
        )
        assertTrue(gauge(CACHE_SIZE) < size)
        assertEquals(repositories - 1, gauge(CACHE_REPOSITORIES))
    }

    /** The sweep skips a repository that a fetch holds, so the repository still counts until a later sweep. */
    @Test
    fun `a repository that the sweep cannot delete still counts in the cache gauges`() {
        val configSet = git.configSet(git.authorizedCredential().id)
        git.publishTip(configSet)
        cache.sweep(syncs::existing)
        val size = gauge(CACHE_SIZE)
        val repositories = gauge(CACHE_REPOSITORIES)

        delete(configSet)
        git.whileFetchLockHeld(configSet) { cache.sweep(syncs::existing) }

        assertEquals(listOf(size, repositories), listOf(gauge(CACHE_SIZE), gauge(CACHE_REPOSITORIES)))
        cache.sweep(syncs::existing)
        assertEquals(repositories - 1, gauge(CACHE_REPOSITORIES))
    }

    /**
     * Folio has only the meters of the table of design 15.1. The `git` module denies the timers of the fetch
     * observation. Test classes share the registry, so this test cannot know which meters of the table exist. Thus the
     * test does not require each meter of the table.
     */
    @Test
    fun `Folio has only the meters of the design, and no meter has an ID, a path, a subject or a commit as a tag`() {
        val configSet = git.configSet(git.authorizedCredential().id)
        git.publishTip(configSet)
        git.dropCache(configSet)
        mvc
            .get("/api/v1/configsets/${configSet.toApiId()}/files/app.yaml") { bearer(SUPER_ADMIN) }
            .andExpect { status { isOk() } }
        cache.sweep(syncs::existing)

        val folio = meters.meters.filter { it.id.name.startsWith("folio.") }
        val tags = folio.flatMap { it.id.tags }

        assertEquals(emptySet(), folio.map { it.id.name }.toSet() - DESIGN_METERS)
        assertTrue(tags.isNotEmpty())
        assertEquals(emptySet(), tags.map { it.key }.toSet() - ALLOWED_TAGS)
        val values = tags.map { it.value }
        assertEquals(emptyList(), values.filter { ID.containsMatchIn(it) || '/' in it || it == SUPER_ADMIN.subject })
    }

    private fun delete(configSet: ConfigSetId) {
        mvc
            .delete("/api/v1/admin/configsets/${configSet.toApiId()}") { bearer(SUPER_ADMIN) }
            .andExpect { status { isNoContent() } }
    }

    private fun attempts(outcome: String, code: String) =
        CounterGrowth(meters, ATTEMPTS, "outcome", outcome, "code", code)

    private fun fetchDuration() = meters.get("folio.sync.fetch.duration").timer().count()

    private fun gauge(name: String) = meters.get(name).gauge().value()

    private companion object {
        const val ATTEMPTS = "folio.sync.attempts"
        const val CACHE_SIZE = "folio.git.cache.size"
        const val CACHE_REPOSITORIES = "folio.git.cache.repositories"
        val DESIGN_METERS =
            setOf(
                ATTEMPTS,
                "folio.sync.fetch.duration",
                "folio.content.fetches",
                "folio.authorization.decisions",
                CACHE_SIZE,
                CACHE_REPOSITORIES,
            )
        val ALLOWED_TAGS = setOf("outcome", "code", "result", "action", "reason")

        /** This pattern matches an API ID, the start of a UUID or a commit ID. */
        val ID = Regex("[0-9a-f]{32}|[0-9a-f]{8}-[0-9a-f]{4}|[0-9a-f]{40}")
    }
}
