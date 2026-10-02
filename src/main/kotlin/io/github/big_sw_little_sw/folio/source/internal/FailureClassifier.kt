package io.github.big_sw_little_sw.folio.source.internal

import io.github.big_sw_little_sw.folio.source.SourceFailure
import org.apache.sshd.common.SshConstants
import org.apache.sshd.common.SshException
import org.eclipse.jgit.errors.NoRemoteRepositoryException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/** Exception chains are short; the bound only guards against a pathological one. */
private const val MAX_CAUSES = 20

/**
 * The stable code for a failed Git operation (ADR 0027). Looks only at exception types and sshd's disconnect code,
 * never at messages, which carry transport output. [hostKeyRejected] comes from the key database of the connection,
 * because JGit reports a rejected host key like any other failed connection, and [deadlineExceeded] from the fetch's
 * [FetchDeadline], because JGit reports a cancelled fetch like any other transport failure.
 */
fun classify(
    error: Throwable,
    hostKeyRejected: Boolean,
    deadlineExceeded: Boolean,
): SourceFailure {
    val causes = generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }.take(MAX_CAUSES).toList()
    return when {
        hostKeyRejected -> SourceFailure.HOST_KEY_REJECTED
        deadlineExceeded -> SourceFailure.DEADLINE_EXCEEDED
        causes.any { it is NoRemoteRepositoryException } -> SourceFailure.REPOSITORY_NOT_FOUND
        causes.any { it is SshException && it.disconnectCode == NO_MORE_AUTH_METHODS } -> SourceFailure.AUTH_FAILED
        causes.any { it.isUnreachable() } -> SourceFailure.UNREACHABLE
        else -> SourceFailure.TRANSPORT_FAILURE
    }
}

private const val NO_MORE_AUTH_METHODS = SshConstants.SSH2_DISCONNECT_NO_MORE_AUTH_METHODS_AVAILABLE

// Timeouts surface as InterruptedIOException (SocketTimeoutException is one) or as TimeoutException, depending on
// where they occur.
private fun Throwable.isUnreachable() =
    this is ConnectException ||
        this is NoRouteToHostException ||
        this is UnknownHostException ||
        this is InterruptedIOException ||
        this is TimeoutException
