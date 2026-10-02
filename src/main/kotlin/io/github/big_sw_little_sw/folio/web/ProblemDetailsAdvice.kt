package io.github.big_sw_little_sw.folio.web

import io.github.big_sw_little_sw.folio.configset.ConfigSetException
import io.github.big_sw_little_sw.folio.configset.ConfigSetNotFoundException
import io.github.big_sw_little_sw.folio.configset.ConfigSetPathNotFoundException
import io.github.big_sw_little_sw.folio.configset.DuplicateConfigSetSlugException
import io.github.big_sw_little_sw.folio.configset.InvalidConfigSetIdException
import io.github.big_sw_little_sw.folio.configset.InvalidConfigSetPathException
import io.github.big_sw_little_sw.folio.consumption.ConsumptionException
import io.github.big_sw_little_sw.folio.consumption.InvalidRevisionException
import io.github.big_sw_little_sw.folio.consumption.NotYetSyncedException
import io.github.big_sw_little_sw.folio.consumption.RevisionNotAvailableException
import io.github.big_sw_little_sw.folio.consumption.UnknownRevisionException
import io.github.big_sw_little_sw.folio.credential.CredentialDisabledException
import io.github.big_sw_little_sw.folio.credential.CredentialException
import io.github.big_sw_little_sw.folio.credential.CredentialNotFoundException
import io.github.big_sw_little_sw.folio.credential.DuplicateCredentialNameException
import io.github.big_sw_little_sw.folio.credential.GitInstanceNotConfiguredException
import io.github.big_sw_little_sw.folio.credential.InvalidCredentialIdException
import io.github.big_sw_little_sw.folio.credential.InvalidCredentialNameException
import io.github.big_sw_little_sw.folio.credential.InvalidKeyIdException
import io.github.big_sw_little_sw.folio.credential.KeyNotPendingException
import io.github.big_sw_little_sw.folio.credential.PendingKeyExistsException
import io.github.big_sw_little_sw.folio.credential.UnknownGitInstanceException
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
import io.github.big_sw_little_sw.folio.source.InvalidBranchException
import io.github.big_sw_little_sw.folio.source.InvalidCommitIdException
import io.github.big_sw_little_sw.folio.source.InvalidRepositoryPathException
import io.github.big_sw_little_sw.folio.source.InvalidSourcePathException
import io.github.big_sw_little_sw.folio.source.RevisionNotFoundException
import io.github.big_sw_little_sw.folio.source.SourceAccessFailedException
import io.github.big_sw_little_sw.folio.source.SourceException
import io.github.big_sw_little_sw.folio.source.SourceFailure
import io.github.big_sw_little_sw.folio.source.SourceFileNotFoundException
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
    fun credential(exception: CredentialException): ProblemDetail =
        when (exception) {
            is CredentialNotFoundException -> {
                problem(HttpStatus.NOT_FOUND, "Credential not found")
            }

            is InvalidCredentialIdException,
            is InvalidKeyIdException,
            is InvalidCredentialNameException,
            is UnknownGitInstanceException,
            -> {
                problem(HttpStatus.BAD_REQUEST, exception.message)
            }

            is DuplicateCredentialNameException -> {
                problem(HttpStatus.CONFLICT, exception.message)
            }

            is CredentialDisabledException -> {
                problem(HttpStatus.CONFLICT, "Credential is disabled")
            }

            is PendingKeyExistsException -> {
                problem(HttpStatus.CONFLICT, "Credential already has a pending key")
            }

            is KeyNotPendingException -> {
                problem(HttpStatus.CONFLICT, "Key is not the credential's pending key")
            }

            is GitInstanceNotConfiguredException -> {
                problem(HttpStatus.CONFLICT, "The credential's Git instance is not configured")
            }
        }

    @ExceptionHandler
    fun source(exception: SourceException): ProblemDetail =
        when (exception) {
            is InvalidRepositoryPathException,
            is InvalidBranchException,
            is InvalidSourcePathException,
            is InvalidCommitIdException,
            -> {
                problem(HttpStatus.BAD_REQUEST, exception.message)
            }

            is RevisionNotFoundException, is SourceFileNotFoundException -> {
                problem(HttpStatus.NOT_FOUND, exception.message)
            }

            // The fixed summary and code only; never the transport output behind them (ADR 0027).
            is SourceAccessFailedException -> {
                val status =
                    if (exception.failure == SourceFailure.DEADLINE_EXCEEDED) {
                        HttpStatus.GATEWAY_TIMEOUT
                    } else {
                        HttpStatus.BAD_GATEWAY
                    }
                problem(status, exception.failure.summary).apply {
                    setProperty("code", exception.failure.name)
                }
            }
        }

    @ExceptionHandler
    fun consumption(exception: ConsumptionException): ProblemDetail =
        when (exception) {
            is InvalidRevisionException -> {
                problem(HttpStatus.BAD_REQUEST, exception.message)
            }

            is NotYetSyncedException, is UnknownRevisionException, is RevisionNotAvailableException -> {
                problem(HttpStatus.NOT_FOUND, exception.message)
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
