package io.github.big_sw_little_sw.folio.web

import org.springframework.security.web.csrf.CsrfToken

/**
 * The facts of the request that the layout shows on each page. The web module adds it to each view model as `page`.
 * A page template declares `@param page: PageContext` and gives it to `web/layout.kte`.
 */
class PageContext(
    /** The subject of the signed-in caller, or null for an anonymous caller. */
    val signedInAs: String?,
    /** A message from the previous request, for example after a redirect. Set it as the flash attribute [NOTICE]. */
    val notice: String?,
    /** The CSRF token for forms and htmx. Null for an anonymous caller, so that a page view makes no session. */
    val csrf: CsrfToken?,
) {
    companion object {
        /** The flash attribute that a controller sets to show a notice on the next page. */
        const val NOTICE = "notice"
    }
}
