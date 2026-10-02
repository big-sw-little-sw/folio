package io.github.big_sw_little_sw.folio.source.internal

import io.github.big_sw_little_sw.folio.source.SourceFailure
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
import kotlin.test.Test
import kotlin.test.assertEquals

class FailureClassifierTest {
    private val uri = URIish("ssh://git@example.com:22/org/repo.git")

    @Test
    fun `a rejected host key wins over whatever JGit reports`() {
        assertEquals(
            SourceFailure.HOST_KEY_REJECTED,
            classify(wrapped(SshException("Server key did not validate")), true),
        )
        assertEquals(SourceFailure.HOST_KEY_REJECTED, classify(wrapped(ConnectException()), true))
    }

    @Test
    fun `a missing repository anywhere in the chain`() {
        val notFound = NoRemoteRepositoryException(uri, "fatal: not a git repository")

        assertEquals(SourceFailure.REPOSITORY_NOT_FOUND, classify(wrapped(notFound), false))
    }

    @Test
    fun `sshd running out of authentication methods is an authentication failure`() {
        val noMoreMethods =
            SshException(SshConstants.SSH2_DISCONNECT_NO_MORE_AUTH_METHODS_AVAILABLE, "No more authentication methods")

        assertEquals(SourceFailure.AUTH_FAILED, classify(wrapped(noMoreMethods), false))
        assertEquals(
            SourceFailure.TRANSPORT_FAILURE,
            classify(wrapped(SshException(SshConstants.SSH2_DISCONNECT_PROTOCOL_ERROR, "x")), false),
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
        failures.forEach { assertEquals(SourceFailure.UNREACHABLE, classify(wrapped(it), false), "$it") }
    }

    @Test
    fun `anything else is a transport failure, judged by type and never by message`() {
        assertEquals(SourceFailure.TRANSPORT_FAILURE, classify(wrapped(IOException("Connection refused")), false))
        assertEquals(SourceFailure.TRANSPORT_FAILURE, classify(TransportException("repository not found"), false))
    }

    @Test
    fun `a cause chain that loops ends`() {
        val first = IOException("first")
        val second = IOException("second", first)
        first.initCause(second)

        assertEquals(SourceFailure.TRANSPORT_FAILURE, classify(first, false))
    }

    /** As JGit's commands report them: an API exception around a transport exception around the cause. */
    private fun wrapped(cause: Throwable) =
        TransportException(
            "failed",
            org.eclipse.jgit.errors
                .TransportException(uri, "failed", cause),
        )
}
