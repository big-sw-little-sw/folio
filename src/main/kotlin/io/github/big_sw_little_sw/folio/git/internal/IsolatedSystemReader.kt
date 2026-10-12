package io.github.big_sw_little_sw.folio.git.internal

import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader

/**
 * Stops JGit from reading the system, user (`~/.gitconfig` and XDG) and JGit config files of the host.
 * Thus only the config of each cache repository applies, and the environment of the server cannot change Git
 * behaviour. The system reader of JGit is global for the process. Folio is the only user of JGit in the process, so
 * the change affects no other code.
 */
class IsolatedSystemReader private constructor(delegate: SystemReader) : SystemReader.Delegate(delegate) {
    override fun openSystemConfig(parent: Config?, fs: FS): FileBasedConfig = EmptyConfig(parent, fs)

    override fun openUserConfig(parent: Config?, fs: FS): FileBasedConfig = EmptyConfig(parent, fs)

    override fun openJGitConfig(parent: Config?, fs: FS): FileBasedConfig = EmptyConfig(parent, fs)

    /**
     * This config has no file. The JGit reader uses the same kind of config when `GIT_CONFIG_NOSYSTEM` is set. JGit
     * saves facts about the file system in its own config. Without a file, these facts stay in memory.
     */
    private class EmptyConfig(parent: Config?, fs: FS) : FileBasedConfig(parent, null, fs) {
        override fun load() {
            // The config has no file to load.
        }

        override fun save() {
            // The config has no file to save.
        }

        override fun isOutdated() = false
    }

    companion object {
        /** A second call changes nothing, so each application context in the JVM can call this method. */
        @Synchronized
        fun install() {
            val current = SystemReader.getInstance()
            if (current !is IsolatedSystemReader) SystemReader.setInstance(IsolatedSystemReader(current))
        }
    }
}
