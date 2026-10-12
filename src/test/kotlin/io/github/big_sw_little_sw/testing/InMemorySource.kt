package io.github.big_sw_little_sw.testing

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.catalog.SourceType
import io.github.big_sw_little_sw.folio.content.ConfigItem
import io.github.big_sw_little_sw.folio.content.ConfigItemInfo
import io.github.big_sw_little_sw.folio.content.ConfigSource
import io.github.big_sw_little_sw.folio.content.ContentException
import io.github.big_sw_little_sw.folio.content.ItemPath
import io.github.big_sw_little_sw.folio.content.Revision
import io.github.big_sw_little_sw.folio.content.RevisionId
import org.springframework.boot.test.context.TestComponent
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

/**
 * An in-memory [ConfigSource] that lets tests publish revisions with [publish], so the tests of the `content` module
 * need no Git. The adapter registers for [SourceType.GIT], because the catalog has no other Source type and
 * [TestCatalog] creates Git ConfigSets. In the context of [FolioIntegrationTest], this adapter replaces the Git adapter
 * ([InMemorySourceOnly]).
 */
@TestComponent
class InMemorySource : ConfigSource {
    override val type = SourceType.GIT

    /** The map holds the published revisions of each ConfigSet, newest first. */
    private val published = ConcurrentHashMap<ConfigSetId, List<Published>>()
    private val failures = ConcurrentHashMap<ConfigSetId, ContentException>()

    /** The new revision gets a random ID in the form of a Git commit ID. */
    fun publish(configSet: ConfigSetId, files: Map<String, String>): RevisionId =
        publishItems(configSet, files.mapValues { (_, text) -> TestItem.of(text.toByteArray()) })

    fun publishItems(configSet: ConfigSetId, items: Map<String, TestItem>): RevisionId {
        val revision = Revision(RevisionId(randomHex(COMMIT_ID_BYTES)), Instant.now())
        val entry = Published(revision, items.mapKeys { (path, _) -> ItemPath(path) })
        published.merge(configSet, listOf(entry)) { old, new -> new + old }
        return revision.id
    }

    /** After this call, each adapter call for [configSet] throws [failure] until a call to [recover]. */
    fun fail(configSet: ConfigSetId, failure: ContentException) {
        failures[configSet] = failure
    }

    fun recover(configSet: ConfigSetId) {
        failures.remove(configSet)
    }

    override fun latest(configSet: ConfigSetId): RevisionId? = revisionsOf(configSet).firstOrNull()?.revision?.id

    override fun revisions(configSet: ConfigSetId, limit: Int): List<Revision> =
        revisionsOf(configSet).take(limit).map { it.revision }

    override fun list(configSet: ConfigSetId, revision: RevisionId): List<ConfigItemInfo> = find(configSet, revision)
        .items
        .map { (path, item) ->
            ConfigItemInfo(path, item.size)
        }.sortedBy { it.path.value }

    override fun <T> read(
        configSet: ConfigSetId,
        revision: RevisionId,
        path: ItemPath,
        maxBytes: Long,
        consume: (ConfigItem, InputStream) -> T,
    ): T {
        requireNoTransaction()
        val item = find(configSet, revision).items[path] ?: throw ContentException.ItemNotFound()
        if (item.size > maxBytes) throw ContentException.ItemTooLarge(maxBytes)
        return item.open().use { consume(ConfigItem(path, item.size, item.etag), it) }
    }

    private fun revisionsOf(configSet: ConfigSetId): List<Published> {
        requireNoTransaction()
        failures[configSet]?.let { throw it }
        return published[configSet].orEmpty()
    }

    /**
     * The `content` module must call the adapter outside a transaction. The integration tests are not transactional,
     * so each content test checks this rule on each route that it calls.
     */
    private fun requireNoTransaction() = check(!TransactionSynchronizationManager.isActualTransactionActive()) {
        "Content called the adapter in a transaction."
    }

    private fun find(configSet: ConfigSetId, revision: RevisionId): Published =
        revisionsOf(configSet).find { it.revision.id == revision } ?: throw ContentException.RevisionNotFound(revision)

    private class Published(val revision: Revision, val items: Map<ItemPath, TestItem>)

    /** A TestItem is a file of a test revision. [open] gives a new stream of the content at each call. */
    class TestItem(val size: Long, val etag: String, val open: () -> InputStream) {
        companion object {
            /** The ETag is the Git blob ID of the content, as the Git adapter gives it. */
            fun of(bytes: ByteArray) = TestItem(bytes.size.toLong(), blobId(bytes)) { ByteArrayInputStream(bytes) }

            /**
             * Returns a file of [size] bytes that throws an error if an adapter opens it. Use it for a file above the
             * size limit.
             */
            fun unopened(size: Long) = TestItem(size, "unopened") { error("The adapter opened a file of $size bytes") }

            /** Returns a file whose stream fails at the first read, as a Git adapter can fail during a stream. */
            fun failing(size: Long) = TestItem(size, "failing") {
                object : InputStream() {
                    override fun read(): Int =
                        throw ContentException.SourceFailed("UNREACHABLE", "Folio cannot connect to the Git server.")
                }
            }

            fun blobId(bytes: ByteArray): String {
                val digest = MessageDigest.getInstance("SHA-1")
                digest.update("blob ${bytes.size}\u0000".toByteArray())
                return HexFormat.of().formatHex(digest.digest(bytes))
            }
        }
    }

    private companion object {
        const val COMMIT_ID_BYTES = 20

        fun randomHex(bytes: Int): String {
            val random = ByteArray(bytes)
            SecureRandom().nextBytes(random)
            return HexFormat.of().formatHex(random)
        }
    }
}
