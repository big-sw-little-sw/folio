package io.github.big_sw_little_sw.folio.source.internal

import org.eclipse.jgit.errors.TransportException
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RemoteSession
import org.eclipse.jgit.transport.RemoteSession2
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.sshd.SshdSession
import org.eclipse.jgit.transport.sshd.SshdSessionFactory
import org.eclipse.jgit.util.FS
import java.io.IOException

/**
 * Enforces a [FetchDeadline] on the sessions of one operation (ADR 0033). Connects get no more than the time left, and
 * [expire] cuts every command the sessions started by closing its streams, which ends JGit's blocked read at once,
 * whatever the server sends or withholds. Closing the session itself is not safe from another thread: JGit's
 * `SshdSession.disconnect` clears its fields without synchronization. `Process.destroy` alone closes the channel
 * gracefully, which waits for the server.
 */
class DeadlineSessions(
    private val factory: SshdSessionFactory,
    private val deadline: FetchDeadline,
) : SshSessionFactory() {
    private val commands = mutableListOf<Process>()

    override fun getSession(
        uri: URIish,
        credentialsProvider: CredentialsProvider?,
        fs: FS,
        tms: Int,
    ): RemoteSession {
        val remaining = deadline.remaining().toMillis()
        if (remaining <= 0) {
            deadline.expire()
            throw TransportException(uri, "deadline exceeded")
        }
        val timeout = if (tms > 0) minOf(tms.toLong(), remaining) else remaining
        return DeadlineSession(factory.getSession(uri, credentialsProvider, fs, timeout.toInt()))
    }

    override fun getType(): String = factory.type

    override fun releaseSession(session: RemoteSession) {
        factory.releaseSession((session as DeadlineSession).session)
    }

    /** Marks the deadline exceeded and cuts every command started so far; later commands are cut as they start. */
    @Synchronized
    fun expire() {
        deadline.expire()
        commands.forEach(::cut)
    }

    @Synchronized
    private fun started(command: Process): Process {
        commands += command
        if (deadline.exceeded) cut(command)
        return command
    }

    private fun cut(command: Process) {
        try {
            command.inputStream.close()
            command.errorStream.close()
        } catch (_: IOException) {
            // Already closed.
        }
        command.destroy()
    }

    private inner class DeadlineSession(
        val session: SshdSession,
    ) : RemoteSession2 {
        override fun exec(
            commandName: String,
            timeout: Int,
        ): Process = started(session.exec(commandName, timeout))

        override fun exec(
            commandName: String,
            environment: Map<String, String>,
            timeout: Int,
        ): Process = started(session.exec(commandName, environment, timeout))

        override fun getFtpChannel() = session.ftpChannel

        override fun disconnect() {
            session.disconnect()
        }
    }
}
