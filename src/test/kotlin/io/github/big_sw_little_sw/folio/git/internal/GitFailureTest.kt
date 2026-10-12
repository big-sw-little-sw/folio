package io.github.big_sw_little_sw.folio.git.internal

import org.apache.sshd.common.SshConstants
import org.apache.sshd.common.SshException
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.errors.NoRemoteRepositoryException
import org.eclipse.jgit.transport.URIish
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import kotlin.test.*

class GitFailureTest {
    private val uri = URIish("ssh://git@example.com:22/org/repo.git")

    @Test
    fun `a rejected host key wins over whatever JGit reports`() {
        listOf(SshException("Server key did not validate"), ConnectException()).forEach {
            assertEquals(
                FailureCode.HOST_KEY_REJECTED,
                classify(wrapped(it), hostKeyRejected = true, deadlineExceeded = false),
            )
        }
    }

    @Test
    fun `a fetch cancelled at its deadline is reported as such, not as the transport failure JGit reports`() {
        assertEquals(
            FailureCode.DEADLINE_EXCEEDED,
            classify(wrapped(IOException("Download cancelled")), hostKeyRejected = false, deadlineExceeded = true),
        )
    }

    @Test
    fun `a missing repository anywhere in the chain`() {
        val notFound = NoRemoteRepositoryException(uri, "fatal: not a git repository")

        assertEquals(FailureCode.REPOSITORY_NOT_FOUND, classifyPlain(wrapped(notFound)))
    }

    @Test
    fun `sshd running out of authentication methods is an authentication failure`() {
        val noMoreMethods =
            SshException(SshConstants.SSH2_DISCONNECT_NO_MORE_AUTH_METHODS_AVAILABLE, "No more authentication methods")

        assertEquals(FailureCode.AUTH_FAILED, classifyPlain(wrapped(noMoreMethods)))
        assertEquals(
            FailureCode.TRANSPORT_FAILURE,
            classifyPlain(wrapped(SshException(SshConstants.SSH2_DISCONNECT_PROTOCOL_ERROR, "x"))),
        )
    }

    @Test
    fun `connection failures and timeouts are unreachable`() {
        val failures =
            listOf(
                ConnectException(),
                UnknownHostException(),
                NoRouteToHostException(),
                SocketTimeoutException(),
                InterruptedIOException(),
                TimeoutException(),
            )
        failures.forEach { assertEquals(FailureCode.UNREACHABLE, classifyPlain(wrapped(it)), "$it") }
    }

    @Test
    fun `anything else is a transport failure, judged by type and never by message`() {
        assertEquals(
            FailureCode.TRANSPORT_FAILURE,
            classifyPlain(wrapped(IOException("Connection refused"))),
        )
        assertEquals(
            FailureCode.TRANSPORT_FAILURE,
            classifyPlain(TransportException("repository not found")),
        )
    }

    @Test
    fun `a cause chain that loops ends`() {
        val first = IOException("first")
        val second = IOException("second", first)
        first.initCause(second)

        assertEquals(FailureCode.TRANSPORT_FAILURE, classifyPlain(first))
    }

    @Test
    fun `the exception types name each type of the chain and no message`() {
        val types = exceptionTypes(wrapped(ConnectException("secret host name")))

        assertEquals("TransportException < TransportException < ConnectException", types)
    }

    /** Classifies [error] without a host-key rejection and before the deadline. */
    private fun classifyPlain(error: Throwable) = classify(error, hostKeyRejected = false, deadlineExceeded = false)

    /** Wraps [cause] as the JGit commands report a failure: an API exception around a transport exception. */
    private fun wrapped(cause: Throwable) = TransportException(
        "failed",
        org.eclipse.jgit.errors
            .TransportException(uri, "failed", cause),
    )
}
