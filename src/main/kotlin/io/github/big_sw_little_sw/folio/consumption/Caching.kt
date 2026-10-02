package io.github.big_sw_little_sw.folio.consumption

import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat

/**
 * How a response may be cached (design 16.7, ADR 0035). [shared] when everyone may read it, so that shared caches
 * may store it; otherwise only the caller's own cache may.
 */
data class CachePolicy(
    val shared: Boolean,
    val maxAge: Duration,
    val immutable: Boolean,
) {
    /** The `Cache-Control` header value. */
    val headerValue: String
        get() =
            listOfNotNull(
                if (shared) "public" else "private",
                "max-age=${maxAge.seconds}",
                "immutable".takeIf { immutable },
            ).joinToString(", ")

    companion object {
        /** One year, the longest `max-age` that caches are expected to honour. */
        val IMMUTABLE_MAX_AGE: Duration = Duration.ofDays(365)

        /** An exact revision never changes. `latest` moves with every sync, so caches keep it for [latestMaxAge]. */
        fun of(
            selector: RevisionSelector,
            shared: Boolean,
            latestMaxAge: Duration,
        ): CachePolicy =
            when (selector) {
                RevisionSelector.Latest -> CachePolicy(shared, latestMaxAge, immutable = false)
                is RevisionSelector.Exact -> CachePolicy(shared, IMMUTABLE_MAX_AGE, immutable = true)
            }
    }
}

/** A response body with its entity tag, unquoted, and its cache policy. */
data class Cacheable<T>(
    val body: T,
    val etag: String,
    val cache: CachePolicy,
)

/**
 * An entity tag for a representation that has no content-addressed ID of its own: the first 128 bits of the SHA-256
 * of [parts], which must together determine the representation and contain no line breaks.
 */
fun digestTag(vararg parts: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\n").toByteArray())
    return HexFormat.of().formatHex(digest, 0, DIGEST_TAG_BYTES)
}

private const val DIGEST_TAG_BYTES = 16
