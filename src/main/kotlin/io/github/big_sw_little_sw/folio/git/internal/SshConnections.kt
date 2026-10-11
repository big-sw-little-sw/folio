package io.github.big_sw_little_sw.folio.git.internal

import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.util.security.SecurityUtils
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.errors.JGitInternalException
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder
import org.springframework.stereotype.Component
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * SSH connections for JGit with one key and the trusted host keys of one Git instance (design 10.4). The environment of
 * the server cannot change what Folio trusts or which key it sends:
 *
 * - The home directory and the `.ssh` directory are [RepositoryCache.sshHome], an empty directory.
 * - There is no SSH config, no agent, no default identity and no `known_hosts` file.
 * - The only authentication is `publickey`, and the key of the credential is the only key.
 *
 * [RepositoryCache] installs the [IsolatedSystemReader], so JGit reads no Git config of the host.
 */
@Component
class SshConnections(cache: RepositoryCache) {
    private val home: File = cache.sshHome.toFile()

    /**
     * Runs [operation]. Each JGit command of [operation] must apply the callback that it gets, so that the command
     * connects with [key] to [instance]. Throws [GitFailure] with the code of a failed command. At [deadline], a
     * watchdog cuts the commands, so [operation] ends with `DEADLINE_EXCEEDED`.
     */
    fun <T> run(
        instance: GitInstance,
        key: KeyPair,
        deadline: FetchDeadline,
        operation: (TransportConfigCallback) -> T,
    ): T {
        val hostKeys = TrustedHostKeys(instance.hostKeys)
        val identity = forSshd(key)
        val factory =
            SshdSessionFactoryBuilder()
                .setHomeDirectory(home)
                .setSshDirectory(File(home, ".ssh"))
                .setConfigStoreFactory { _, _, _ -> null }
                .setDefaultKnownHostsFiles { emptyList() }
                .setDefaultIdentities { emptyList() }
                .setDefaultKeysProvider { listOf(identity) }
                .setServerKeyDatabase { _, _ -> hostKeys }
                .setPreferredAuthentications("publickey")
                // The null value is necessary. Without it, JGit loads an SSH agent connector through the ServiceLoader.
                .setConnectorFactory(null)
                .build(null)
        val sessions = DeadlineSessions(factory, deadline)
        // A virtual thread sleeps until the deadline. A pool is not necessary, because a sleeping virtual thread is
        // cheap (design 8.7).
        val watchdog = Thread.ofVirtual().start { expireAtDeadline(sessions, deadline) }
        try {
            return operation { (it as SshTransport).sshSessionFactory = sessions }
        } catch (e: GitAPIException) {
            throw GitFailure(classify(e, hostKeys.rejected, deadline.exceeded), exceptionTypes(e))
        } catch (e: JGitInternalException) {
            throw GitFailure(classify(e, hostKeys.rejected, deadline.exceeded), exceptionTypes(e))
        } finally {
            watchdog.interrupt()
            factory.close()
        }
    }

    private fun expireAtDeadline(sessions: DeadlineSessions, deadline: FetchDeadline) {
        try {
            Thread.sleep(deadline.remaining())
            sessions.expire()
        } catch (_: InterruptedException) {
            // The operation ended before the deadline.
        }
    }

    /**
     * Trusts exactly the configured host keys of one Git instance. Folio rejects unknown and changed keys, and never
     * adds them (design 10.1). A rejection sets [rejected], so that the failure gets the code `HOST_KEY_REJECTED`.
     */
    private class TrustedHostKeys(private val trusted: List<PublicKey>) : ServerKeyDatabase {
        @Volatile
        var rejected = false
            private set

        override fun lookup(
            connectAddress: String,
            remoteAddress: InetSocketAddress,
            config: ServerKeyDatabase.Configuration,
        ): List<PublicKey> = trusted

        override fun accept(
            connectAddress: String,
            remoteAddress: InetSocketAddress,
            serverKey: PublicKey,
            config: ServerKeyDatabase.Configuration,
            provider: CredentialsProvider?,
        ): Boolean {
            // The key classes differ between providers, so equals() is not reliable. compareKeys compares the key
            // material.
            val accepted = trusted.any { KeyUtils.compareKeys(it, serverKey) }
            if (!accepted) rejected = true
            return accepted
        }
    }

    companion object {
        /**
         * Converts the JDK Ed25519 key pair of a credential to the key types of the EdDSA provider of sshd, which is
         * Bouncy Castle (design 10.4). The conversion uses the standard X.509 and PKCS#8 encodings. Folio sets its copy
         * of the PKCS#8 bytes to zero, so the plain private key does not stay in memory.
         */
        fun forSshd(keyPair: KeyPair): KeyPair {
            val factory = SecurityUtils.getKeyFactory(SecurityUtils.ED25519)
            val pkcs8 = keyPair.private.encoded
            try {
                return KeyPair(
                    factory.generatePublic(X509EncodedKeySpec(keyPair.public.encoded)),
                    factory.generatePrivate(PKCS8EncodedKeySpec(pkcs8)),
                )
            } finally {
                pkcs8.fill(0)
            }
        }
    }
}
