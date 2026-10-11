package io.github.big_sw_little_sw.testing

import kotlin.test.assertTrue

private val TAG = Regex("<[a-zA-Z][^>]*>")
private val SCRIPT = Regex("<script\\b[^>]*>")
private val SOURCE = Regex("\\ssrc\\s*=")
private val EVENT_HANDLER = Regex("\\son[a-z]+\\s*=", RegexOption.IGNORE_CASE)
private val STYLE = Regex("\\sstyle\\s*=", RegexOption.IGNORE_CASE)

/**
 * The UI content security policy blocks inline scripts and styles (design 12.3). A browser would then break the page,
 * so tests examine the HTML: each script has a source and no content, and no tag has an event handler or a style.
 */
fun assertNoInlineScriptsOrStyles(html: String) {
    SCRIPT.findAll(html).forEach { script ->
        assertTrue(SOURCE.containsMatchIn(script.value), "A script without src: ${script.value}")
        assertTrue(html.startsWith("</script>", script.range.last + 1), "A script with content: ${script.value}")
    }
    TAG.findAll(html).forEach { tag ->
        assertTrue(!EVENT_HANDLER.containsMatchIn(tag.value), "An inline event handler: ${tag.value}")
        assertTrue(!STYLE.containsMatchIn(tag.value), "An inline style: ${tag.value}")
    }
}
