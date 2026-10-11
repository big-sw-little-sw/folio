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
 * Replaces Spring Boot's `/error` route. The servlet container sends a request here when a filter fails or calls
 * `sendError`, for example when the firewall rejects a request. The exception goes to [ProblemDetailsAdvice], so these
 * errors have the same body as all others.
 *
 * A [FolioException] from a filter keeps its code and status. Other exceptions give 500 `INTERNAL`. The container
 * already logged them, so this route gives only their status to the advice.
 */
@Controller
class ErrorPageController : ErrorController {
    @RequestMapping("/error")
    fun error(request: HttpServletRequest): Nothing =
        throw request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) as? FolioException
            ?: ResponseStatusException(statusOf(request))

    /** Only the container sends requests here. A client that requests /error directly finds nothing. */
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
