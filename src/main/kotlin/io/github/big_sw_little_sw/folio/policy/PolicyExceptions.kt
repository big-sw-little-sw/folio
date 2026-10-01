package io.github.big_sw_little_sw.folio.policy

/** Expected failures of authorization and rule management (ADR 0006). */
sealed class PolicyException(
    message: String,
) : RuntimeException(message)

/** An anonymous caller was denied; a token might allow the action. */
class NotAuthenticatedException(
    val action: Action,
) : PolicyException("Authentication required for $action")

/** An authenticated caller was denied. */
class PermissionDeniedException(
    val action: Action,
) : PolicyException("Permission $action denied")

class InvalidSubjectException(
    val subject: String,
) : PolicyException("Invalid subject '$subject'")

/** A rule without subjects would deny everyone below it, which is a deny rule in disguise (ADR 0009). */
class RuleWithoutSubjectsException(
    val action: Action,
) : PolicyException("A rule for $action needs at least one subject")
