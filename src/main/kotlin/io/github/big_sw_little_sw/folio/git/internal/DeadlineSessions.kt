package io.github.big_sw_little_sw.folio.git.internal

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
 * Enforces a [FetchDeadline] on the SSH sessions of one fetch (design 10.7). A connect gets no more than the remaining
 * time. At the deadline, [expire] cuts each command of the sessions. To cut a command, Folio closes the streams of the
 * command and then destroys the command. The close ends a blocked read of JGit immediately.
 *
 * [expire] does not close the session itself, because a call to `SshdSession.disconnect` of JGit from another thread
 * is not safe. `Process.destroy` alone is not sufficient: it closes the channel gracefully, and a graceful close waits
 * for the server.
 */
class DeadlineSessions(private val factory: SshdSessionFactory, private val deadline: FetchDeadline) :
    SshSessionFactory() {
    private val commands = mutableListOf<Process>()

    override fun getSession(uri: URIish, credentialsProvider: CredentialsProvider?, fs: FS, tms: Int): RemoteSession {
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

    /** Marks the deadline as exceeded and cuts each command. [started] cuts a command that starts after this call. */
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
        // Each stream has its own try, so a failure to close one stream still closes the other.
        try {
            command.inputStream.close()
        } catch (_: IOException) {
            // A failed close needs no action, because destroy() ends the command.
        }
        try {
            command.errorStream.close()
        } catch (_: IOException) {
            // A failed close needs no action, because destroy() ends the command.
        }
        command.destroy()
    }

    private inner class DeadlineSession(val session: SshdSession) : RemoteSession2 {
        override fun exec(commandName: String, timeout: Int): Process = started(session.exec(commandName, timeout))

        override fun exec(commandName: String, environment: Map<String, String>, timeout: Int): Process =
            started(session.exec(commandName, environment, timeout))

        override fun getFtpChannel() = session.ftpChannel

        override fun disconnect() {
            session.disconnect()
        }
    }
}
