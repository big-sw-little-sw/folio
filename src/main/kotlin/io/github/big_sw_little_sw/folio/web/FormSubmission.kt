package io.github.big_sw_little_sw.folio.web

import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * Runs a form [operation] of a UI page (design 12.3). The operation gives the URL of the next page and a notice. On an
 * expected failure, it shows the [form] again with the failure as `problem`, and with the status that the API gives
 * for the failure. A page template declares `@param problem: FolioException? = null`.
 */
fun submit(
    redirect: RedirectAttributes,
    form: () -> ModelAndView,
    operation: () -> Pair<String, String>,
): ModelAndView = try {
    val (url, notice) = operation()
    redirect.addFlashAttribute(PageContext.NOTICE, notice)
    ModelAndView("redirect:$url")
} catch (exception: FolioException) {
    form().apply {
        addObject("problem", exception)
        status = exception.kind.status
    }
}
