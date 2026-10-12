package io.github.big_sw_little_sw.folio.content.internal

import io.github.big_sw_little_sw.folio.catalog.ConfigSet
import io.github.big_sw_little_sw.folio.content.ConfigItem
import io.github.big_sw_little_sw.folio.content.RevisionId
import java.io.InputStream
import java.io.OutputStream

/**
 * One open ConfigItem at an exact [revision]. The stream is open only in the block of [ContentService.read], so use an
 * ItemRead only there. [item] needs no read of the content. [bytes], [validation] and [writeTo] read the content.
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
     * The content of a ConfigItem of [ConfigFormat.MAX_VALIDATED_BYTES] or less, or null for a larger ConfigItem. A
     * larger ConfigItem stays a stream, so memory use does not increase with the size of a file. Only one thread uses
     * an ItemRead, so the lazy values need no lock.
     */
    val bytes: ByteArray? by lazy(LazyThreadSafetyMode.NONE) {
        if (item.size <= ConfigFormat.MAX_VALIDATED_BYTES) stream.readNBytes(item.size.toInt()) else null
    }

    /** Folio validates the bytes only when a caller reads [validation]. Thus a download parses nothing. */
    val validation: ValidationStatus by lazy(LazyThreadSafetyMode.NONE) {
        bytes?.let { ConfigFormat.validate(item.path, it) } ?: ValidationStatus.UNKNOWN
    }

    fun writeTo(output: OutputStream) {
        val inMemory = bytes
        if (inMemory != null) output.write(inMemory) else stream.transferTo(output)
    }
}
