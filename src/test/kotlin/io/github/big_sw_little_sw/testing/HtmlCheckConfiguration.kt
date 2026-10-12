package io.github.big_sw_little_sw.testing

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.*
import kotlin.test.*

private val TAG = Regex("<[a-zA-Z][^>]*>")
private val SCRIPT = Regex("<script\\b[^>]*>")
private val SOURCE = Regex("\\ssrc\\s*=")
private val EVENT_HANDLER = Regex("\\son[a-z]+\\s*=", RegexOption.IGNORE_CASE)
private val STYLE = Regex("\\sstyle\\s*=", RegexOption.IGNORE_CASE)

/**
 * The UI content security policy blocks inline scripts and styles, and a browser then breaks the page. Tests have no
 * browser, so this configuration examines the HTML of each MockMvc response. A new page thus needs no extra test.
 */
@TestConfiguration(proxyBeanMethods = false)
class HtmlCheckConfiguration {
    @Bean
    fun htmlCheck() = MockMvcBuilderCustomizer { builder ->
        builder.alwaysExpect(
            ResultMatcher { result ->
                val type = result.response.contentType?.let(MediaType::parseMediaType)
                if (type != null && type.isCompatibleWith(MediaType.TEXT_HTML)) {
                    assertNoInlineScriptsOrStyles(result.response.contentAsString)
                }
            },
        )
    }
}

/** Each script must have a source and no content. No tag can have an event handler or a style. */
private fun assertNoInlineScriptsOrStyles(html: String) {
    SCRIPT.findAll(html).forEach { script ->
        assertTrue(SOURCE.containsMatchIn(script.value), "A script without src: ${script.value}")
        assertTrue(html.startsWith("</script>", script.range.last + 1), "A script with content: ${script.value}")
    }
    TAG.findAll(html).forEach { tag ->
        assertFalse(EVENT_HANDLER.containsMatchIn(tag.value), "An inline event handler: ${tag.value}")
        assertFalse(STYLE.containsMatchIn(tag.value), "An inline style: ${tag.value}")
    }
}
