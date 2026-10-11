package io.github.big_sw_little_sw.testing

import io.github.big_sw_little_sw.folio.web.ErrorKind
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

/** Fails requests in a servlet filter, outside Spring MVC, so that tests can examine the `/error` dispatch. */
class TestFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        when (request.requestURI) {
            FAILURE -> throw TestRoutes.TestFailure(ErrorKind.CONFLICT)
            SEND_ERROR -> response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE)
            else -> chain.doFilter(request, response)
        }
    }

    companion object {
        /** A filter throws a FolioException of kind CONFLICT. */
        const val FAILURE = "/api/test/filter/failure"

        /** A filter sends status 503 with `sendError`. */
        const val SEND_ERROR = "/api/test/filter/send-error"
    }
}
