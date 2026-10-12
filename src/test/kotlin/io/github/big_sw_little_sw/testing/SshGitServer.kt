package io.github.big_sw_little_sw.testing

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import java.security.KeyPairGenerator
import java.util.Base64

/**
 * An OpenSSH server with `git` in Testcontainers. The test builds the image from a pinned Alpine image, so the tests
 * control the host key and the authorized keys, and need no third-party image. All test classes of the JVM share the
 * server. The server generates its host key when the container starts. The repositories are in `/repos`, so the
 * repository path of a Git source is `repos/<name>.git`.
 */
object SshGitServer {
    private const val SSH_PORT = 22

    private val container: GenericContainer<*> by lazy {
        GenericContainer(
            ImageFromDockerfile("folio-test-ssh-git-server", false).withDockerfileFromBuilder {
                it
                    .from("alpine:3.24.2")
                    .run("apk add --no-cache openssh git")
                    // OpenSSH refuses a locked account ('!' in the shadow file), also with a key. The value '*'
                    // disables only the password.
                    .run(
                        "adduser -D -s /bin/sh git && sed -i 's/^git:!/git:*/' /etc/shadow && " +
                            "mkdir -p /home/git/.ssh /repos && touch /home/git/.ssh/authorized_keys && " +
                            "chown -R git:git /home/git /repos && chmod 700 /home/git/.ssh && " +
                            "chmod 600 /home/git/.ssh/authorized_keys",
                    ).cmd(
                        "sh",
                        "-c",
                        // Without PerSourcePenalties=no, sshd refuses connections for a time after the authentication
                        // failures that the tests cause.
                        "ssh-keygen -q -t ed25519 -N '' -f /etc/ssh/ssh_host_ed25519_key && " +
                            "exec /usr/sbin/sshd -D -e -o PasswordAuthentication=no -o PerSourcePenalties=no " +
                            "-h /etc/ssh/ssh_host_ed25519_key",
                    ).build()
            },
        ).withExposedPorts(SSH_PORT)
            .waitingFor(Wait.forLogMessage(".*Server listening on.*", 1))
            .apply { start() }
    }

    /** The host key of the server as an OpenSSH line with a comment. */
    val hostKey: String by lazy { exec("cat", "/etc/ssh/ssh_host_ed25519_key.pub").trim() }

    /** The command that authorizes a public key on the server, for local runs. */
    val authorizeCommand: String
        get() =
            "docker exec ${container.containerId} " +
                "sh -c \"echo '<public key line>' >> /home/git/.ssh/authorized_keys\""

    /** `[host]:port`, as a `known_hosts` file names this server. */
    val knownHostsName: String get() = "[${container.host}]:${container.getMappedPort(SSH_PORT)}"

    /**
     * Registers these Git instances:
     *
     * - `test-server`: this server with its host key.
     * - `wrong-host-key`: this server with a host key that the server does not have.
     * - `multi-key`: this server with a wrong host key before the correct host key.
     * - `unreachable`: a closed port.
     */
    fun register(registry: DynamicPropertyRegistry) {
        val port = { container.getMappedPort(SSH_PORT) }
        instance(registry, "test-server", { container.host }, port, { hostKey })
        instance(registry, "wrong-host-key", { container.host }, port, { otherHostKey })
        instance(registry, "multi-key", { container.host }, port, { otherHostKey })
        registry.add("folio.git.instances.multi-key.host-keys[1]") { hostKey }
        instance(registry, "unreachable", { "127.0.0.1" }, { 1 }, { hostKey })
    }

    /**
     * Authorizes [publicKey], an OpenSSH line. With [command], each command of the key runs inside [command] as
     * `$SSH_ORIGINAL_COMMAND`. A fetch runs two commands: its `ls-remote` and the fetch. See [delayed] and [throttled].
     */
    fun authorize(publicKey: String, command: String? = null) {
        val options = if (command == null) "" else "command=\"${command.replace("\"", "\\\"")}\" "
        exec("sh", "-c", "echo '$options$publicKey' >> /home/git/.ssh/authorized_keys")
    }

    fun revokeAll() {
        exec("sh", "-c", ": > /home/git/.ssh/authorized_keys")
    }

    /** Returns a forced command that adds a line to the file [log] for each command, and then runs the command. */
    fun logged(log: String) = "echo \"\$SSH_ORIGINAL_COMMAND\" >> $log; eval \"\$SSH_ORIGINAL_COMMAND\""

    /** The number of commands that [logged] wrote to [log]. */
    fun loggedCommands(log: String): Int = exec("sh", "-c", "cat $log 2>/dev/null | wc -l").trim().toInt()

    /** Removes [log]. The next command of [logged] creates the file again. */
    fun clearLog(log: String) {
        exec("rm", "-f", log)
    }

    /** Returns a forced command that starts each command [seconds] late. The server sends nothing during the delay. */
    fun delayed(seconds: Int) = "sleep $seconds; eval \"\$SSH_ORIGINAL_COMMAND\""

    /**
     * Returns a forced command that sends the output of each command at a maximum of [bytes] each second. The
     * connection is never idle for long, so the idle timeout of JGit never fires. Output longer than [bytes] takes
     * several seconds.
     */
    fun throttled(bytes: Int) = "eval \"\$SSH_ORIGINAL_COMMAND\" | " +
        "while [ \"\$(dd bs=$bytes count=1 2>/dev/null | tee /dev/fd/3 | wc -c)\" -gt 0 ]; do sleep 1; done 3>&1"

    /** A key pair that the container generated. */
    data class Identity(val privateKey: String, val publicKeyLine: String) {
        override fun toString() = "Identity($publicKeyLine)"
    }

    /** A new key pair, generated in the container: the OpenSSH private key file and the public key line. */
    fun newIdentity(): Identity {
        exec("sh", "-c", "rm -f /tmp/identity /tmp/identity.pub && ssh-keygen -q -t ed25519 -N '' -f /tmp/identity")
        return Identity(exec("cat", "/tmp/identity"), exec("cat", "/tmp/identity.pub").trim())
    }

    /**
     * Creates the bare repository `/repos/<name>.git` with two commits on `main`, and returns their IDs, oldest first.
     *
     * - The first commit has `config/app.yaml` with `v1`. The annotated tag `v1` points to it.
     * - The second commit changes `config/app.yaml` to `v2`. It adds `config/sub/extra.json`, the executable file
     *   `config/run.sh`, `README.md` outside the root path `config`, a symbolic link `config/link` to `../README.md`,
     *   and `config/back\slash.txt`, whose name is not an item path.
     * - The branch `other` has one more commit after the second commit.
     */
    fun createRepository(name: String): List<String> = createFrom(
        name,
        """
            mkdir -p config/sub
            printf 'v1' > config/app.yaml
            git add -A
            git commit -q -m one
            git tag -a v1 -m v1
            printf 'v2' > config/app.yaml
            printf '{}' > config/sub/extra.json
            printf 'x' > config/run.sh
            chmod +x config/run.sh
            printf 'readme' > README.md
            ln -s ../README.md config/link
            printf 'odd' > 'config/back\slash.txt'
            git add -A
            git commit -q -m two
            git checkout -q -b other
            printf 'other' > config/other.yaml
            git add -A
            git commit -q -m other
            git checkout -q main
        """.trimIndent(),
        "git rev-parse HEAD~1 HEAD",
    ).lines().filter { it.isNotBlank() }

    /** The object ID that [ref] names in `/repos/<name>.git`. For an annotated tag, it is the ID of the tag object. */
    fun objectId(name: String, ref: String): String =
        exec("git", "-c", "safe.directory=*", "-C", "/repos/$name.git", "rev-parse", ref).trim()

    /**
     * Creates the bare repository `/repos/<name>.git` with one commit on `main` whose trees share subtrees. Below
     * `config`, four levels of ten directories end in ten files each, so the commit names 100,000 files. Git stores
     * only one tree for each level, so the pack is small. Returns the commit ID.
     */
    fun createSharedTreeRepository(name: String): String {
        val script =
            """
            set -e
            rm -rf /repos/$name.git
            git init -q --bare -b main /repos/$name.git
            cd /repos/$name.git
            blob=${'$'}(printf 'x' | git hash-object -w --stdin)
            entries=''
            for i in 0 1 2 3 4 5 6 7 8 9; do entries="${'$'}{entries}100644 blob ${'$'}blob\tf${'$'}i\n"; done
            tree=${'$'}(printf "${'$'}entries" | git mktree)
            for level in 1 2 3 4; do
              entries=''
              for i in 0 1 2 3 4 5 6 7 8 9; do entries="${'$'}{entries}040000 tree ${'$'}tree\td${'$'}i\n"; done
              tree=${'$'}(printf "${'$'}entries" | git mktree)
            done
            tree=${'$'}(printf "040000 tree ${'$'}tree\tconfig\n" | git mktree)
            commit=${'$'}(git -c user.name=test -c user.email=test@folio.test commit-tree ${'$'}tree -m shared)
            git update-ref refs/heads/main ${'$'}commit
            chown -R git:git /repos/$name.git
            echo ${'$'}commit
            """.trimIndent()
        return exec("sh", "-c", script).trim()
    }

    /**
     * Creates the bare repository `/repos/<name>.git` with one commit on `main`. Its `config/big.bin` has [bytes]
     * random bytes, which do not compress. Returns the commit ID.
     */
    fun createLargeRepository(name: String, bytes: Int): String = createFrom(
        name,
        """
            mkdir config
            head -c $bytes /dev/urandom > config/big.bin
            git add -A
            git commit -q -m big
        """.trimIndent(),
        "git rev-parse HEAD",
    ).trim()

    /** Pushes a commit to `main` that sets `config/app.yaml` to [content], and returns its ID. */
    fun addCommit(name: String, content: String): String {
        val script =
            """
            set -e
            git config --global --add safe.directory '*'
            work=${'$'}(mktemp -d)
            git clone -q /repos/$name.git ${'$'}work
            cd ${'$'}work
            git config user.email test@folio.test
            git config user.name test
            printf '$content' > config/app.yaml
            git add -A
            git commit -q -m next
            git push -q origin main
            chown -R git:git /repos/$name.git
            git rev-parse HEAD
            """.trimIndent()
        return exec("sh", "-c", script).trim()
    }

    /** Moves `main` of `/repos/<name>.git` to [commit], as a force push does. */
    fun resetBranch(name: String, commit: String) {
        exec(
            "sh",
            "-c",
            "git -c safe.directory='*' -C /repos/$name.git update-ref refs/heads/main $commit && " +
                "chown -R git:git /repos/$name.git",
        )
    }

    private fun createFrom(name: String, commits: String, result: String): String {
        val script =
            """
            set -e
            work=${'$'}(mktemp -d)
            cd ${'$'}work
            git init -q -b main
            git config user.email test@folio.test
            git config user.name test
            $commits
            rm -rf /repos/$name.git
            git clone -q --bare . /repos/$name.git
            chown -R git:git /repos/$name.git
            $result
            """.trimIndent()
        return exec("sh", "-c", script)
    }

    private fun instance(
        registry: DynamicPropertyRegistry,
        name: String,
        host: () -> String,
        port: () -> Int,
        hostKey: () -> String,
    ) {
        registry.add("folio.git.instances.$name.host", host)
        registry.add("folio.git.instances.$name.port", port)
        registry.add("folio.git.instances.$name.host-keys[0]", hostKey)
    }

    /** A valid Ed25519 host key line that the server does not have. */
    private val otherHostKey: String by lazy {
        val raw =
            KeyPairGenerator
                .getInstance("Ed25519")
                .generateKeyPair()
                .public.encoded
                .takeLast(ED25519_KEY_BYTES)
        val blob = lengthPrefixed("ssh-ed25519".toByteArray()) + lengthPrefixed(raw.toByteArray())
        "ssh-ed25519 ${Base64.getEncoder().encodeToString(blob)}"
    }

    private const val ED25519_KEY_BYTES = 32

    private fun lengthPrefixed(bytes: ByteArray) =
        byteArrayOf(0, 0, (bytes.size shr Byte.SIZE_BITS).toByte(), bytes.size.toByte()) + bytes

    private fun exec(vararg command: String): String {
        val result = container.execInContainer(*command)
        check(result.exitCode == 0) { "${command.joinToString(" ")} failed: ${result.stderr}" }
        return result.stdout
    }
}
