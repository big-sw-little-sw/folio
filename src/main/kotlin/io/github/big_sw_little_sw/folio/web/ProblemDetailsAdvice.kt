package io.github.big_sw_little_sw.folio.web

import io.github.big_sw_little_sw.folio.configset.ConfigSetException
import io.github.big_sw_little_sw.folio.configset.ConfigSetNotFoundException
import io.github.big_sw_little_sw.folio.configset.ConfigSetPathNotFoundException
import io.github.big_sw_little_sw.folio.configset.DuplicateConfigSetSlugException
import io.github.big_sw_little_sw.folio.configset.InvalidConfigSetIdException
import io.github.big_sw_little_sw.folio.configset.InvalidConfigSetPathException
import io.github.big_sw_little_sw.folio.namespace.DuplicateSlugException
import io.github.big_sw_little_sw.folio.namespace.InvalidNamespaceIdException
import io.github.big_sw_little_sw.folio.namespace.InvalidSlugException
import io.github.big_sw_little_sw.folio.namespace.NamespaceException
import io.github.big_sw_little_sw.folio.namespace.NamespaceMoveIntoOwnSubtreeException
import io.github.big_sw_little_sw.folio.namespace.NamespaceNotEmptyException
import io.github.big_sw_little_sw.folio.namespace.NamespaceNotFoundException
import io.github.big_sw_little_sw.folio.policy.InvalidSubjectException
import io.github.big_sw_little_sw.folio.policy.NotAuthenticatedException
import io.github.big_sw_little_sw.folio.policy.PermissionDeniedException
import io.github.big_sw_little_sw.folio.policy.PolicyException
import io.github.big_sw_little_sw.folio.policy.RuleWithoutSubjectsException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Maps every module's expected failures to RFC 9457 problem details (ADR 0006). Details never carry
 * internal UUIDs; the request path in `instance` identifies the resource.
 */
@RestControllerAdvice
class ProblemDetailsAdvice {
    @ExceptionHandler
    fun namespace(exception: NamespaceException): ProblemDetail =
        when (exception) {
            is NamespaceNotFoundException -> {
                problem(HttpStatus.NOT_FOUND, "Namespace not found")
            }

            is InvalidNamespaceIdException, is InvalidSlugException -> {
                problem(HttpStatus.BAD_REQUEST, exception.message)
            }

            is DuplicateSlugException -> {
                problem(HttpStatus.CONFLICT, exception.message)
            }

            is NamespaceMoveIntoOwnSubtreeException -> {
                problem(HttpStatus.CONFLICT, "A namespace cannot move into its own subtree")
            }

            is NamespaceNotEmptyException -> {
                problem(HttpStatus.CONFLICT, "Namespace is not empty")
            }
        }

    @ExceptionHandler
    fun configSet(exception: ConfigSetException): ProblemDetail =
        when (exception) {
            is ConfigSetNotFoundException, is ConfigSetPathNotFoundException -> {
                problem(HttpStatus.NOT_FOUND, "ConfigSet not found")
            }

            is InvalidConfigSetIdException, is InvalidConfigSetPathException -> {
                problem(HttpStatus.BAD_REQUEST, exception.message)
            }

            is DuplicateConfigSetSlugException -> {
                problem(HttpStatus.CONFLICT, exception.message)
            }
        }

    @ExceptionHandler
    fun policy(exception: PolicyException): ResponseEntity<ProblemDetail> =
        when (exception) {
            is NotAuthenticatedException -> {
                ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                    .body(problem(HttpStatus.UNAUTHORIZED, exception.message))
            }

            is PermissionDeniedException -> {
                response(HttpStatus.FORBIDDEN, exception.message)
            }

            is InvalidSubjectException, is RuleWithoutSubjectsException -> {
                response(HttpStatus.BAD_REQUEST, exception.message)
            }
        }

    private fun response(
        status: HttpStatus,
        detail: String?,
    ) = ResponseEntity.status(status).body(problem(status, detail))

    private fun problem(
        status: HttpStatus,
        detail: String?,
    ) = ProblemDetail.forStatusAndDetail(status, detail)
}
