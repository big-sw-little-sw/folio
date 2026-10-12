package io.github.big_sw_little_sw.folio.web.internal

import io.github.big_sw_little_sw.folio.web.FolioException
import jakarta.servlet.DispatcherType
import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.webmvc.error.ErrorController
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.server.ResponseStatusException

/**
 * Replaces the `/error` route of Spring Boot. The servlet container sends a request here when a filter throws or calls
 * `sendError`. For example, the firewall of Spring Security calls `sendError` when it rejects a request. This route
 * throws an exception to [ProblemDetailsAdvice], so these errors have the same body as all others.
 *
 * A [FolioException] from a filter keeps its code and status. For each other error, this route throws a
 * [ResponseStatusException] with only the status from the container. The container already logged the cause, so the
 * advice must not log the cause again.
 */
@Controller
class ErrorPageController : ErrorController {
    @RequestMapping("/error")
    fun error(request: HttpServletRequest): Nothing =
        throw request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) as? FolioException
            ?: ResponseStatusException(statusOf(request))

    /**
     * A client can request /error directly. Such a request is not an error dispatch, so the client gets 404, as for
     * an unknown path.
     */
    private fun statusOf(request: HttpServletRequest): HttpStatusCode {
        val status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE)
        return when {
            request.dispatcherType != DispatcherType.ERROR || status == null -> HttpStatus.NOT_FOUND
            else -> HttpStatusCode.valueOf(status as? Int ?: INTERNAL_SERVER_ERROR)
        }
    }

    private companion object {
        const val INTERNAL_SERVER_ERROR = 500
    }
}
