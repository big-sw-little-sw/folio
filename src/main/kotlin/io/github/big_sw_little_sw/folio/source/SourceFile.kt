package io.github.big_sw_little_sw.folio.source

/**
 * A regular file beneath a ConfigSet's root path: its [path] relative to the root path, the ID of its blob and its
 * size in bytes. The blob ID is content-addressed: the same bytes always have the same ID.
 */
data class SourceFile(
    val path: SourcePath,
    val blobId: String,
    val size: Long,
)
