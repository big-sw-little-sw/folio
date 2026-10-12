package io.github.big_sw_little_sw.folio.git.internal

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.content.ContentException
import org.apache.sshd.common.SshConstants
import org.apache.sshd.common.SshException
import org.eclipse.jgit.errors.NoRemoteRepositoryException
import org.slf4j.Logger
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.time.Duration
import java.util.Locale
import java.util.concurrent.TimeoutException

/**
 * The failure codes of Git operations. Each code has a fixed summary that is safe to show and to store.
 * A summary never contains transport output, keys or secrets.
 */
enum class FailureCode(val summary: String) {
    HOST_KEY_REJECTED("The host key of the Git server is not a trusted host key of the Git instance."),
    AUTH_FAILED("The Git server did not accept the key of the credential."),

    /** Some servers report "not found" when a key has no access, so the summary gives both causes. */
    REPOSITORY_NOT_FOUND("The repository does not exist, or the key of the credential has no access to it."),
    BRANCH_NOT_FOUND("The repository has no branch with this name."),
    ROOT_PATH_NOT_FOUND("The root path is not a directory at the tip of the branch."),

    /** [Locale.ROOT] keeps the number format of the summary the same on each JVM. */
    TOO_MANY_FILES(
        "The revision has more than ${"%,d".format(Locale.ROOT, MAX_LIST_ENTRIES)} files and directories below the " +
            "root path.",
    ),
    UNREACHABLE("Folio could not connect to the Git server, or the server stopped answering."),
    TRANSPORT_FAILURE("The Git transfer failed."),
    DEADLINE_EXCEEDED("The fetch did not end before the fetch deadline."),
    REPOSITORY_TOO_LARGE("The repository is larger than the maximum repository size."),
    CREDENTIAL_DISABLED("The credential of the Git source is disabled."),
    GIT_INSTANCE_NOT_CONFIGURED("The configuration has no Git instance with the name that the credential gives."),
}

/**
 * A Git operation failed with [code]. The message is the fixed summary of the code. The exception has no cause,
 * because the message of a cause can contain transport output. [exceptionTypes] names the types of the cause chain
 * for the log, for example `TransportException < SshException < ConnectException`.
 */
class GitFailure(val code: FailureCode, val exceptionTypes: String = "") : RuntimeException(code.summary)

/**
 * Returns the read error for a failed Git operation. A deadline failure is a timeout of the Source (504). Each other
 * code is a failure of the Source (502). The detail is the fixed summary of the code, so a read never shows transport
 * output.
 */
fun FailureCode.toContentException(): ContentException = if (this == FailureCode.DEADLINE_EXCEEDED) {
    ContentException.SourceTimedOut(name, summary)
} else {
    ContentException.SourceFailed(name, summary)
}

/** A catch block passes the caught failure here. Without this overload, detekt reports the failure as swallowed. */
fun GitFailure.toContentException(): ContentException = code.toContentException()

/** Real exception chains are short. The limit stops a chain that loops, so the walk of the chain always ends. */
private const val MAX_CAUSES = 20

private fun causesOf(error: Throwable): List<Throwable> =
    generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }.take(MAX_CAUSES).toList()

/** Returns the simple names of the types in the cause chain of [error], outermost first. The names have no messages. */
fun exceptionTypes(error: Throwable): String = causesOf(error).joinToString(" < ") { it.javaClass.simpleName }

/**
 * Logs a failed Git [step], for example `fetch` or `check`, in one line. The line never contains an exception message,
 * because a message can contain server output.
 */
fun logFailure(log: Logger, step: String, configSet: ConfigSetId, failure: GitFailure, startedNanos: Long) {
    log.warn(
        "The Git {} of ConfigSet {} failed with {} after {} ms. Exception types: {}",
        step,
        configSet.toApiId(),
        failure.code,
        Duration.ofNanos(System.nanoTime() - startedNanos).toMillis(),
        failure.exceptionTypes.ifEmpty { "none" },
    )
}

/**
 * Selects the failure code of a failed Git operation from exception types and results only. Messages differ between
 * servers and can contain server output, so the code never depends on them. JGit reports a rejected host key as a
 * failed connection, so [hostKeyRejected] comes from the host-key check of the connection. A command that the deadline
 * cut fails as a broken connection, so [deadlineExceeded] comes from the [FetchDeadline].
 */
fun classify(error: Throwable, hostKeyRejected: Boolean, deadlineExceeded: Boolean): FailureCode {
    val causes = causesOf(error)
    return when {
        hostKeyRejected -> FailureCode.HOST_KEY_REJECTED
        deadlineExceeded -> FailureCode.DEADLINE_EXCEEDED
        causes.any { it is NoRemoteRepositoryException } -> FailureCode.REPOSITORY_NOT_FOUND
        causes.any { it is SshException && it.disconnectCode == NO_MORE_AUTH_METHODS } -> FailureCode.AUTH_FAILED
        causes.any(::isUnreachable) -> FailureCode.UNREACHABLE
        else -> FailureCode.TRANSPORT_FAILURE
    }
}

private const val NO_MORE_AUTH_METHODS = SshConstants.SSH2_DISCONNECT_NO_MORE_AUTH_METHODS_AVAILABLE

// A timeout is an InterruptedIOException (SocketTimeoutException is one) or a TimeoutException. The type depends on
// the place where the timeout occurs. A timeout of a connect and a timeout of an idle read during the transfer both
// give UNREACHABLE, because the types do not show which one occurred.
private fun isUnreachable(cause: Throwable) = cause is ConnectException ||
    cause is NoRouteToHostException ||
    cause is UnknownHostException ||
    cause is InterruptedIOException ||
    cause is TimeoutException
