package io.github.big_sw_little_sw.folio.source.internal

import io.github.big_sw_little_sw.folio.credential.CredentialKeyPair
import io.github.big_sw_little_sw.folio.source.SourceAccessFailedException
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
import java.nio.file.Files
import java.security.KeyPair
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * SSH sessions for JGit with one credential's key and one instance's trusted host keys, and nothing from the
 * user's environment (ADR 0025): the home and `.ssh` directories are an empty directory of Folio's, there is no
 * SSH config, no default identity, no known_hosts file and no SSH agent, and only public-key authentication.
 */
@Component
class SshConnections(
    private val hostKeys: TrustedHostKeys,
    properties: SourceProperties,
) {
    // Empty and never written: JGit resolves `~` and `~/.ssh` against it instead of the process user's home.
    private val home: File = Files.createDirectories(properties.cacheDirectory.resolve("ssh-home")).toFile()

    /**
     * Runs [operation], whose JGit commands must apply the callback it receives so they connect with [credential].
     * Throws [SourceAccessFailedException] with the classified failure if a command fails.
     */
    fun <T> run(
        credential: CredentialKeyPair,
        operation: (TransportConfigCallback) -> T,
    ): T {
        val database = TrustedKeyDatabase(hostKeys.of(credential.gitInstance))
        val keyPair = forSshd(credential.keyPair)
        val factory =
            SshdSessionFactoryBuilder()
                .setHomeDirectory(home)
                .setSshDirectory(File(home, ".ssh"))
                .setConfigStoreFactory { _, _, _ -> null }
                .setDefaultKnownHostsFiles { emptyList() }
                .setDefaultIdentities { emptyList() }
                .setDefaultKeysProvider { listOf(keyPair) }
                .setServerKeyDatabase { _, _ -> database }
                .setPreferredAuthentications("publickey")
                // Set to null rather than left unset, which would load an agent connector through the ServiceLoader.
                .setConnectorFactory(null)
                .build(null)
        try {
            return operation { (it as SshTransport).sshSessionFactory = factory }
        } catch (e: GitAPIException) {
            throw SourceAccessFailedException(classify(e, database.rejected))
        } catch (e: JGitInternalException) {
            throw SourceAccessFailedException(classify(e, database.rejected))
        } finally {
            factory.close()
        }
    }

    /**
     * Trusts exactly the configured keys of one instance. Unknown and changed keys are rejected, never added, and a
     * rejection is recorded so that the failure can be reported as a host-key failure.
     */
    private class TrustedKeyDatabase(
        private val trusted: List<PublicKey>,
    ) : ServerKeyDatabase {
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
            // Key classes differ between providers, so equals() is unreliable; compareKeys compares key material.
            val accepted = trusted.any { KeyUtils.compareKeys(it, serverKey) }
            if (!accepted) rejected = true
            return accepted
        }
    }

    companion object {
        /**
         * The credential's JDK Ed25519 key pair as keys of sshd's EdDSA provider, Bouncy Castle (ADR 0026). The
         * standard encodings carry the keys across; Folio's copy of the PKCS#8 bytes is zeroed.
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
