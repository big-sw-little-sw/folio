package io.github.big_sw_little_sw.folio.source.internal

import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader

/**
 * Keeps JGit from reading the system, user (`~/.gitconfig`, XDG) and JGit configuration files of the host, so
 * that only the repository's own config applies (ADR 0025). JGit's system reader is process-wide; Folio is the
 * only JGit user in the process.
 */
class IsolatedSystemReader private constructor(
    delegate: SystemReader,
) : SystemReader.Delegate(delegate) {
    override fun openSystemConfig(
        parent: Config?,
        fs: FS,
    ): FileBasedConfig = EmptyConfig(parent, fs)

    override fun openUserConfig(
        parent: Config?,
        fs: FS,
    ): FileBasedConfig = EmptyConfig(parent, fs)

    override fun openJGitConfig(
        parent: Config?,
        fs: FS,
    ): FileBasedConfig = EmptyConfig(parent, fs)

    /**
     * A config without a file, as JGit's own reader uses when `GIT_CONFIG_NOSYSTEM` is set. JGit saves what it
     * learns about the file system to its user-level config; with no file, that stays in memory.
     */
    private class EmptyConfig(
        parent: Config?,
        fs: FS,
    ) : FileBasedConfig(parent, null, fs) {
        override fun load() {
            // Nothing to load.
        }

        override fun save() {
            // Nowhere to save.
        }

        override fun isOutdated() = false
    }

    companion object {
        /** Idempotent. */
        @Synchronized
        fun install() {
            val current = SystemReader.getInstance()
            if (current !is IsolatedSystemReader) SystemReader.setInstance(IsolatedSystemReader(current))
        }
    }
}
