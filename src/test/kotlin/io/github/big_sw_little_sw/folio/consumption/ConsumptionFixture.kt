package io.github.big_sw_little_sw.folio.consumption

import io.github.big_sw_little_sw.folio.configset.ConfigSet
import io.github.big_sw_little_sw.folio.configset.toApiId
import io.github.big_sw_little_sw.folio.security.SUPER_ADMIN
import io.github.big_sw_little_sw.folio.sync.SyncFixture
import io.github.big_sw_little_sw.folio.sync.internal.Synchronizer
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.net.URI

/**
 * One ConfigSet on a repository of the SSH Git server (ADR 0004), syncing it, and calls to the consumption API.
 * [reset] runs first in each test. The ConfigSet's root path is `config`; see `SshGitServer.createRepository` for
 * the commits.
 */
class ConsumptionFixture(
    private val mvc: MockMvc,
    private val synchronizer: Synchronizer,
    private val sync: SyncFixture,
) {
    lateinit var commits: List<String>
    lateinit var configSet: ConfigSet

    /** The consumption URL of the ConfigSet. */
    val url: String get() = "/api/v1/configsets/${configSet.id.toApiId()}"

    /** Recreates the repository and a ConfigSet on it that has not synced yet; the thread ends unauthenticated. */
    fun reset() {
        commits = sync.reset()
        configSet = sync.configSet(sync.authorizedCredential())
        SecurityContextHolder.clearContext()
    }

    /** Syncs the ConfigSet now, as the poller would. */
    fun sync() {
        sync.makeDue(configSet)
        synchronizer.claimDue(MAX_CLAIMS).forEach { synchronizer.sync(it) }
    }

    /** GETs [url] as is, without encoding it again, so that percent-encoded paths reach the server unchanged. */
    fun get(
        url: String,
        token: RequestPostProcessor? = ADMIN,
        ifNoneMatch: String? = null,
    ): ResultActionsDsl =
        mvc.get(URI.create(url)) {
            token?.let { with(it) }
            ifNoneMatch?.let { header(HttpHeaders.IF_NONE_MATCH, it) }
        }

    /** Grants [action] on the ConfigSet to [subjects], as the super admin. */
    fun grant(
        action: String,
        vararg subjects: String,
    ) {
        mvc
            .put("/api/v1/admin/configsets/${configSet.id.toApiId()}/rules/$action") {
                with(ADMIN)
                contentType = MediaType.APPLICATION_JSON
                content = """{"subjects": [${subjects.joinToString { "\"$it\"" }}]}"""
            }.andExpect { status { isOk() } }
    }

    companion object {
        val ADMIN: RequestPostProcessor = jwt().jwt { it.subject(SUPER_ADMIN) }
        val ALICE: RequestPostProcessor = jwt().jwt { it.subject("alice") }
        private const val MAX_CLAIMS = 100
    }
}
