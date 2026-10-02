package io.github.big_sw_little_sw.folio.source

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import java.security.KeyPairGenerator
import java.util.Base64

/**
 * An OpenSSH server with `git` in Testcontainers, built from a pinned Alpine image (ADR 0004). Shared by every test
 * class in the JVM; the server's host key is generated when the container starts. Repositories live under `/repos`,
 * so a source's repository path is `repos/<name>.git`.
 */
object SshGitServer {
    private val container: GenericContainer<*> =
        GenericContainer(
            ImageFromDockerfile("folio-test-ssh-git-server", false).withDockerfileFromBuilder {
                it
                    .from("alpine:3.24.2")
                    .run("apk add --no-cache openssh git")
                    // A locked account (`!` in the shadow file) cannot log in at all, even with a key; `*` only
                    // disables the password.
                    .run(
                        "adduser -D -s /bin/sh git && sed -i 's/^git:!/git:*/' /etc/shadow && " +
                            "mkdir -p /home/git/.ssh /repos && touch /home/git/.ssh/authorized_keys && " +
                            "chown -R git:git /home/git /repos && chmod 700 /home/git/.ssh && " +
                            "chmod 600 /home/git/.ssh/authorized_keys",
                    ).cmd(
                        "sh",
                        "-c",
                        // PerSourcePenalties would refuse connections for a while after the tests' deliberate
                        // authentication failures.
                        "ssh-keygen -q -t ed25519 -N '' -f /etc/ssh/ssh_host_ed25519_key && " +
                            "exec /usr/sbin/sshd -D -e -o PasswordAuthentication=no -o PerSourcePenalties=no " +
                            "-h /etc/ssh/ssh_host_ed25519_key",
                    ).build()
            },
        ).withExposedPorts(SSH_PORT)
            .waitingFor(Wait.forLogMessage(".*Server listening on.*", 1))
            .apply { start() }

    /** The server's host key as an OpenSSH line, with a comment. */
    val hostKey: String = exec("cat", "/etc/ssh/ssh_host_ed25519_key.pub").trim()

    /**
     * Registers the server under the instance name `test-server`, plus `wrong-host-key` (same server, a host key it
     * does not have), `multi-key` (same server, a wrong key listed before the right one) and `unreachable` (a closed
     * port).
     */
    fun register(registry: DynamicPropertyRegistry) {
        instance(registry, "test-server", container.host, container.getMappedPort(SSH_PORT), hostKey)
        instance(registry, "wrong-host-key", container.host, container.getMappedPort(SSH_PORT), otherHostKey())
        instance(registry, "unreachable", "127.0.0.1", 1, hostKey)
        // The matching key is not the first one listed.
        instance(registry, "multi-key", container.host, container.getMappedPort(SSH_PORT), otherHostKey())
        registry.add("folio.git.instances.multi-key.host-keys[1]") { hostKey }
    }

    /** `[host]:port`, as a known_hosts file names this server. */
    val knownHostsName: String get() = "[${container.host}]:${container.getMappedPort(SSH_PORT)}"

    /**
     * Authorizes [publicKey]. With [delaySeconds], every command the key runs, ls-remote and fetch alike, starts that
     * much later, which makes a slow server.
     */
    fun authorize(
        publicKey: String,
        delaySeconds: Int = 0,
    ) {
        val options =
            if (delaySeconds >
                0
            ) {
                "command=\"sleep $delaySeconds; eval \\\"\$SSH_ORIGINAL_COMMAND\\\"\" "
            } else {
                ""
            }
        exec("sh", "-c", "echo '$options$publicKey' >> /home/git/.ssh/authorized_keys")
    }

    /** Deletes the branch `main` of `/repos/<name>.git`. */
    fun deleteMain(name: String) {
        exec(
            "sh",
            "-c",
            "git config --global --add safe.directory '*' && git -C /repos/$name.git update-ref -d refs/heads/main",
        )
    }

    /** A new key pair, generated in the container: the OpenSSH private key file and the public key line. */
    fun newIdentity(): Pair<String, String> {
        exec("sh", "-c", "rm -f /tmp/identity /tmp/identity.pub && ssh-keygen -q -t ed25519 -N '' -f /tmp/identity")
        return exec("cat", "/tmp/identity") to exec("cat", "/tmp/identity.pub").trim()
    }

    fun revokeAll() {
        exec("sh", "-c", ": > /home/git/.ssh/authorized_keys")
    }

    /**
     * Creates the bare repository `/repos/<name>.git` with two commits on `main` and returns their IDs, oldest
     * first. The first has `config/app.yaml` (`v1`); the second changes it to `v2` and adds `config/sub/extra.json`,
     * `README.md` outside the root path and a symlink `config/link` to `../README.md`.
     */
    fun createRepository(name: String): List<String> {
        val script =
            """
            set -e
            work=${'$'}(mktemp -d)
            cd ${'$'}work
            git init -q -b main
            git config user.email test@folio.test
            git config user.name test
            mkdir -p config/sub
            printf 'v1' > config/app.yaml
            git add -A
            git commit -q -m one
            printf 'v2' > config/app.yaml
            printf '{}' > config/sub/extra.json
            printf 'readme' > README.md
            ln -s ../README.md config/link
            git add -A
            git commit -q -m two
            rm -rf /repos/$name.git
            git clone -q --bare . /repos/$name.git
            chown -R git:git /repos/$name.git
            git rev-parse HEAD~1 HEAD
            """.trimIndent()
        return exec("sh", "-c", script).lines().filter { it.isNotBlank() }
    }

    /** Pushes a commit to `main` of `/repos/<name>.git` that sets `config/app.yaml` to [content]; returns its ID. */
    fun addCommit(
        name: String,
        content: String,
    ): String {
        val script =
            """
            set -e
            # The repository belongs to git; root may work in it here.
            git config --global --add safe.directory '*'
            work=${'$'}(mktemp -d)
            git clone -q /repos/$name.git ${'$'}work
            cd ${'$'}work
            git config user.email test@folio.test
            git config user.name test
            printf '$content' > config/app.yaml
            git commit -q -am three
            git push -q origin main
            chown -R git:git /repos/$name.git
            git rev-parse HEAD
            """.trimIndent()
        return exec("sh", "-c", script).trim()
    }

    private fun instance(
        registry: DynamicPropertyRegistry,
        name: String,
        host: String,
        port: Int,
        hostKey: String,
    ) {
        registry.add("folio.git.instances.$name.host") { host }
        registry.add("folio.git.instances.$name.port") { port }
        registry.add("folio.git.instances.$name.host-keys[0]") { hostKey }
    }

    /** A valid Ed25519 host key line that the server does not have. */
    private fun otherHostKey(): String {
        val raw =
            KeyPairGenerator
                .getInstance("Ed25519")
                .generateKeyPair()
                .public.encoded
                .takeLast(32)
        val type = "ssh-ed25519".toByteArray()
        val blob = lengthPrefixed(type) + lengthPrefixed(raw.toByteArray())
        return "ssh-ed25519 ${Base64.getEncoder().encodeToString(blob)}"
    }

    private fun lengthPrefixed(bytes: ByteArray) =
        byteArrayOf(0, 0, (bytes.size shr Byte.SIZE_BITS).toByte(), bytes.size.toByte()) + bytes

    private fun exec(vararg command: String): String {
        val result = container.execInContainer(*command)
        check(result.exitCode == 0) { "${command.joinToString(" ")} failed: ${result.stderr}" }
        return result.stdout
    }

    private const val SSH_PORT = 22
}
