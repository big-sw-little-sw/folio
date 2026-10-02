package io.github.big_sw_little_sw.folio.source.internal

import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class IsolatedSystemReaderTest {
    @Test
    fun `JGit reads no system, user or JGit config file once installed`() {
        IsolatedSystemReader.install()
        val reader = SystemReader.getInstance()

        assertNull(reader.openSystemConfig(null, FS.DETECTED).file)
        assertNull(reader.openUserConfig(null, FS.DETECTED).file)
        assertNull(reader.openJGitConfig(null, FS.DETECTED).file)
    }

    @Test
    fun `saving JGit's own settings, as it does for file system timestamps, writes nothing`() {
        IsolatedSystemReader.install()

        SystemReader.getInstance().jGitConfig.save()
    }

    @Test
    fun `installing twice keeps one reader`() {
        IsolatedSystemReader.install()
        val first = SystemReader.getInstance()

        IsolatedSystemReader.install()

        assertSame(first, SystemReader.getInstance())
    }
}
