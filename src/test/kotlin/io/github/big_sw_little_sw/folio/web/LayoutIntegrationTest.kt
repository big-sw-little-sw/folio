package io.github.big_sw_little_sw.folio.web

import io.github.big_sw_little_sw.testing.FolioIntegrationTest
import io.github.big_sw_little_sw.testing.TestRoutes
import org.hamcrest.Matchers.containsString
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test

/** The shared layout of the UI (design 12.3), through the start page. */
@FolioIntegrationTest
class LayoutIntegrationTest(@Autowired private val mvc: MockMvc) {
    @Test
    fun `static assets are public`() {
        listOf(HTMX, "/js/folio.js", "/css/folio.css").forEach { path ->
            mvc.get(path).andExpect { status { isOk() } }
        }
    }

    @Test
    fun `the integrity attribute matches the pinned htmx file`() {
        val htmx =
            mvc
                .get(HTMX)
                .andReturn()
                .response.contentAsByteArray
        val digest = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-384").digest(htmx))

        mvc.get("/").andExpect {
            content { string(containsString("""<script src="$HTMX" integrity="sha384-$digest" defer>""")) }
        }
    }

    @Test
    fun `a flash notice shows on the next page`() {
        val redirect = mvc.get("/test/notice").andExpect { redirectedUrl("/") }.andReturn()
        val session = requireNotNull(redirect.response.getCookie("SESSION"))

        mvc.get("/") { cookie(session) }.andExpect {
            content { string(containsString("""<p class="notice" role="status">${TestRoutes.NOTICE}</p>""")) }
        }
    }

    private companion object {
        const val HTMX = "/js/htmx-2.0.11.min.js"
    }
}
