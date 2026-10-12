package io.github.big_sw_little_sw.testing

import io.github.big_sw_little_sw.folio.identity.Caller
import io.github.big_sw_little_sw.folio.identity.CurrentCaller
import io.github.big_sw_little_sw.folio.web.ApiId
import io.github.big_sw_little_sw.folio.web.ErrorKind
import io.github.big_sw_little_sw.folio.web.FolioException
import io.github.big_sw_little_sw.folio.web.PageContext
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.time.Duration
import java.util.UUID

/**
 * Routes for tests of the shared HTTP behaviour: the caller, errors, API IDs and notices. With these routes, the tests
 * do not depend on a product module. This package is outside the application package, so only tests that import
 * [TestRoutes] have these routes.
 */
@RestController
class TestRoutes(private val currentCaller: CurrentCaller) {
    @GetMapping(CALLER)
    fun caller(): Map<String, Any?> = when (val caller = currentCaller.get()) {
        is Caller.Authenticated -> {
            mapOf(
                "type" to "AUTHENTICATED",
                "subject" to caller.subject,
                "groups" to caller.groups.sorted(),
                "applicationId" to caller.applicationId,
                "superAdmin" to caller.superAdmin,
            )
        }

        Caller.Anonymous -> {
            mapOf("type" to "ANONYMOUS")
        }

        Caller.System -> {
            mapOf("type" to "SYSTEM")
        }
    }

    @GetMapping("/api/test/failures/{kind}", "/test/failures/{kind}")
    fun failure(@PathVariable kind: ErrorKind): Nothing = throw TestFailure(kind)

    @GetMapping("/api/test/unexpected", "/test/unexpected")
    fun unexpected(): Nothing = throw IllegalStateException(SECRET)

    /** Redirects with a notice, as a UI page does after a successful change. */
    @GetMapping("/test/notice")
    fun notice(redirect: RedirectAttributes): ModelAndView {
        redirect.addFlashAttribute(PageContext.NOTICE, NOTICE)
        return ModelAndView("redirect:/")
    }

    @GetMapping("/api/test/thread")
    fun thread(): Boolean = Thread.currentThread().isVirtual

    @PostMapping("/api/test/echo")
    fun echo(@RequestBody body: Echo): Echo = body

    @GetMapping("/api/test/count")
    fun count(@RequestParam n: Int): Int = n

    @GetMapping("/api/test/ids/{id}")
    fun id(@PathVariable id: String): String = TEST_ID.format(TEST_ID.parse(id))

    data class Echo(val text: String)

    class TestFailure(kind: ErrorKind) :
        FolioException(
            kind,
            "TEST_${kind.name}",
            "A test failure of kind ${kind.name}.",
            // The delay is 4.2 seconds, so the test shows that Folio rounds a fraction of a second up to
            // RETRY_AFTER_SECONDS.
            if (kind == ErrorKind.UNAVAILABLE) Duration.ofSeconds(RETRY_AFTER_SECONDS).minusMillis(800) else null,
        )

    @JvmInline
    value class TestId(val value: UUID)

    companion object {
        /** The route that gives the caller of the request as JSON. */
        const val CALLER = "/api/test/caller"
        const val SECRET = "secret internal detail"
        const val NOTICE = "Namespace created."
        const val RETRY_AFTER_SECONDS = 5L
        val TEST_ID = ApiId("tst", ::TestId, TestId::value)
    }
}
