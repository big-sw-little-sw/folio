package io.github.big_sw_little_sw.folio.web

import java.time.Duration

/** The kinds of expected failure. The web module gives each kind one HTTP status (design 8.6). */
enum class ErrorKind {
    NOT_FOUND,
    CONFLICT,
    INVALID,
    FORBIDDEN,
    UNAUTHENTICATED,
    UNAVAILABLE,
    UPSTREAM_FAILED,
    UPSTREAM_TIMEOUT,
    TOO_LARGE,
}

/**
 * The base type of each expected failure. Each module declares a sealed subtype in its public API, with one subclass
 * for each failure. Modules never select HTTP statuses.
 *
 * [code] is the stable part for clients, for example `ACTION_NOT_ALLOWED`. [detail] is for people and can change. It
 * goes to the client, so it must never contain a secret, a token or transport output. [retryAfter] tells the client
 * of an [ErrorKind.UNAVAILABLE] failure when to try again.
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
