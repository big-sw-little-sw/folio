package io.github.big_sw_little_sw.folio.web

import org.springframework.http.HttpStatus
import java.time.Duration

/** The kinds of expected failure. Each kind has one HTTP status. */
enum class ErrorKind(val status: HttpStatus) {
    NOT_FOUND(HttpStatus.NOT_FOUND),
    CONFLICT(HttpStatus.CONFLICT),
    INVALID(HttpStatus.BAD_REQUEST),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    UPSTREAM_FAILED(HttpStatus.BAD_GATEWAY),
    UPSTREAM_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT),

    // Not 413, because 413 means that the request is too large. Here the request is correct, but the requested content
    // is larger than Folio serves.
    TOO_LARGE(HttpStatus.UNPROCESSABLE_CONTENT),
}

/**
 * The base type of each expected failure. Each module declares a sealed subtype in its public API, with one subclass
 * for each failure. A module selects an [ErrorKind], never an HTTP status. Thus one map in the web module decides the
 * status of each failure.
 *
 * [code] is the stable part for clients, for example `ACTION_NOT_ALLOWED`. [detail] is for people and can change. The
 * client receives [detail], so [detail] must never contain a secret, a token or transport output. [retryAfter] tells
 * the client of an [ErrorKind.UNAVAILABLE] failure when to try again.
 */
abstract class FolioException(
    val kind: ErrorKind,
    val code: String,
    val detail: String,
    val retryAfter: Duration? = null,
) : RuntimeException(detail) {
    init {
        require(CODE.matches(code)) { "An error code is uppercase words joined by underscores: $code" }
    }

    private companion object {
        val CODE = Regex("[A-Z]+(_[A-Z]+)*")
    }
}
