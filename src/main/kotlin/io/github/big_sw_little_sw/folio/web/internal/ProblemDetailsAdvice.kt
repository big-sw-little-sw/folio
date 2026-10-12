package io.github.big_sw_little_sw.folio.web.internal

import gg.jte.TemplateEngine
import gg.jte.output.StringOutput
import io.github.big_sw_little_sw.folio.web.CONTENT_SECURITY_POLICY
import io.github.big_sw_little_sw.folio.web.ErrorKind
import io.github.big_sw_little_sw.folio.web.FolioException
import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.WebRequest
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * The one place that turns failures into responses. API routes get an RFC 9457 problem detail with a `code`. UI routes
 * get an HTML page with the same code and detail. The security filters of `identity` send their failures here too, so
 * all errors have one format.
 *
 * The base class handles the failures of Spring MVC itself, for example JSON that does not parse or a missing
 * parameter.
 */
@RestControllerAdvice
class ProblemDetailsAdvice(private val templates: TemplateEngine) : ResponseEntityExceptionHandler() {
    @ExceptionHandler
    fun expected(exception: FolioException, request: HttpServletRequest): ResponseEntity<Any> {
        val headers = HttpHeaders()
        if (exception.kind == ErrorKind.UNAUTHENTICATED) headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        exception.retryAfter?.let { headers.set(HttpHeaders.RETRY_AFTER, retryAfterSeconds(it).toString()) }
        return respond(request, Problem(exception.kind.status, exception.code, exception.detail), headers)
    }

    /** The message of the exception can contain internal details. Thus only the log gets the message. */
    @ExceptionHandler
    fun unexpected(exception: Exception, request: HttpServletRequest): ResponseEntity<Any> {
        log.error("Unexpected error", exception)
        return respond(request, Problem(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL, UNEXPECTED), HttpHeaders())
    }

    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any> {
        val status = HttpStatus.valueOf(statusCode.value())
        // ErrorPageController throws ResponseStatusException only after the container logged the cause. Without this
        // check, the log would contain each such cause two times.
        if (status.is5xxServerError && ex !is ResponseStatusException) log.error("Unexpected error", ex)
        val problem =
            when {
                status == HttpStatus.BAD_REQUEST -> Problem(status, "INVALID_REQUEST", detailOf(body, status))

                status == HttpStatus.INTERNAL_SERVER_ERROR -> Problem(status, INTERNAL, UNEXPECTED)

                // The detail of another server error can contain internal details. Thus the client gets only the
                // status.
                status.is5xxServerError -> Problem(status, status.name, status.reasonPhrase)

                else -> Problem(status, status.name, detailOf(body, status))
            }
        // Spring MVC always gives a ServletWebRequest, so the cast is safe. A null return would make the failure an
        // empty 200 response.
        return respond((request as ServletWebRequest).request, problem, HttpHeaders(headers))
    }

    private fun respond(request: HttpServletRequest, problem: Problem, headers: HttpHeaders): ResponseEntity<Any> {
        headers.setCacheControl("no-store")
        if (isApi(request)) {
            val body = ProblemDetail.forStatusAndDetail(problem.status, problem.detail)
            body.setProperty("code", problem.code)
            // Without an instance, Spring sets the request URI. On the error dispatch, the request URI is /error.
            body.instance = URI.create(originalUri(request))
            return ResponseEntity.status(problem.status).headers(headers).body(body)
        }
        // Spring Security writes no headers on the error dispatch. Thus the advice sets the UI headers on each error
        // page.
        headers.set(CONTENT_SECURITY_POLICY_HEADER, CONTENT_SECURITY_POLICY)
        headers.set(CONTENT_TYPE_OPTIONS_HEADER, "nosniff")
        return ResponseEntity
            .status(problem.status)
            .headers(headers)
            .contentType(HTML)
            .body(errorPage(request, problem))
    }

    private fun errorPage(request: HttpServletRequest, problem: Problem): String {
        val output = StringOutput()
        val params =
            mapOf(
                "page" to pageContextOf(request),
                "title" to problem.status.reasonPhrase,
                "code" to problem.code,
                "detail" to problem.detail,
            )
        templates.render("web/internal/errorPage.kte", params, output)
        return output.toString()
    }

    private data class Problem(val status: HttpStatus, val code: String, val detail: String)

    private companion object {
        const val INTERNAL = "INTERNAL"
        const val CONTENT_SECURITY_POLICY_HEADER = "Content-Security-Policy"
        const val CONTENT_TYPE_OPTIONS_HEADER = "X-Content-Type-Options"
        const val UNEXPECTED = "An unexpected error occurred."
        val HTML = MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8)
        val log = LoggerFactory.getLogger(ProblemDetailsAdvice::class.java)

        /** Rounds up to whole seconds, with a minimum of 1 second, so that a client never retries too early. */
        fun retryAfterSeconds(delay: Duration): Long = delay
            .plusSeconds(1)
            .minusNanos(1)
            .toSeconds()
            .coerceAtLeast(1)

        fun detailOf(body: Any?, status: HttpStatus): String = (body as? ProblemDetail)?.detail ?: status.reasonPhrase

        /**
         * The URI that the client requested. On the `/error` dispatch, the container keeps that URI in a request
         * attribute.
         */
        fun originalUri(request: HttpServletRequest): String =
            request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) as? String ?: request.requestURI

        fun isApi(request: HttpServletRequest): Boolean {
            val path = originalUri(request).removePrefix(request.contextPath)
            return path == "/api" || path.startsWith("/api/")
        }
    }
}
