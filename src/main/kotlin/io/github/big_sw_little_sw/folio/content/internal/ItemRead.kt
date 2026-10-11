package io.github.big_sw_little_sw.folio.content.internal

import io.github.big_sw_little_sw.folio.catalog.ConfigSet
import io.github.big_sw_little_sw.folio.content.ConfigItem
import io.github.big_sw_little_sw.folio.content.ItemPath
import io.github.big_sw_little_sw.folio.content.RevisionId
import java.io.InputStream
import java.io.OutputStream

/**
 * An ItemRead is one open ConfigItem at an exact [revision]. The stream is open only in the block of
 * [ContentService.read], so use an ItemRead only there. The HTTP layer can answer `If-None-Match` from [item] before
 * it reads [content]. Then Folio reads none of the content.
 */
class ItemRead(
    val configSet: ConfigSet,
    val revision: RevisionId,
    val item: ConfigItem,
    val cacheControl: String,
    private val stream: InputStream,
) {
    val mediaType: String get() = ConfigFormat.mediaTypeOf(item.path)

    /**
     * The first read of [content] reads a ConfigItem of 3 MB or less into memory. A larger ConfigItem stays a stream,
     * and its validation status is UNKNOWN (design 9). Thus memory does not increase with the size of a file. Only one
     * thread uses an ItemRead, in the block of [ContentService.read], so the lazy value needs no lock.
     */
    val content: ItemContent by lazy(LazyThreadSafetyMode.NONE) {
        if (item.size <= ConfigFormat.MAX_VALIDATED_BYTES) {
            ItemContent.InMemory(item.path, stream.readNBytes(item.size.toInt()))
        } else {
            ItemContent.Streamed(stream)
        }
    }
}

sealed interface ItemContent {
    val validation: ValidationStatus

    fun writeTo(output: OutputStream)

    /** Folio validates the bytes only when a caller reads [validation]. Thus a download parses nothing. */
    class InMemory(path: ItemPath, val bytes: ByteArray) : ItemContent {
        override val validation by lazy(LazyThreadSafetyMode.NONE) { ConfigFormat.validate(path, bytes) }

        override fun writeTo(output: OutputStream) = output.write(bytes)
    }

    class Streamed(private val stream: InputStream) : ItemContent {
        override val validation get() = ValidationStatus.UNKNOWN

        override fun writeTo(output: OutputStream) {
            stream.transferTo(output)
        }
    }
}
