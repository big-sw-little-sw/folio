package io.github.big_sw_little_sw.folio.web

import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/** The page that a form goes to after its operation, and the notice that the page shows. */
data class Redirect(val url: String, val notice: String)

/**
 * Runs the [operation] of a form on a UI page. On success, the browser goes to the page that the [Redirect] names, and
 * that page shows the notice. On an expected failure, [submit] renders the [form] again with the failure as `problem`.
 * The response then has the status that the API gives for the same failure. A form template declares
 * `@param problem: FolioException? = null` to show the failure.
 */
fun submit(redirect: RedirectAttributes, form: () -> ModelAndView, operation: () -> Redirect): ModelAndView = try {
    val next = operation()
    redirect.addFlashAttribute(PageContext.NOTICE, next.notice)
    ModelAndView("redirect:${next.url}")
} catch (exception: FolioException) {
    form().apply {
        addObject("problem", exception)
        status = exception.kind.status
    }
}
