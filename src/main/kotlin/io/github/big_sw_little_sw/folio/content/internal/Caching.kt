package io.github.big_sw_little_sw.folio.content.internal

import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat

/**
 * The `Cache-Control` value of a content response (design 12.1). A response is `public` if an anonymous caller can
 * READ the ConfigSet. Otherwise it is `private`, and only the cache of the caller can keep it.
 *
 * - An exact revision never changes, so caches can keep it for a year. Shared caches revalidate a public exact revision
 *   after [latestMaxAge]. Thus they stop serving it soon after an administrator removes a `public` grant.
 * - `latest` changes with each publish, so all caches keep it for [latestMaxAge] only. Metadata and revision lists
 *   name the latest revision, so they use the policy of [RevisionSelector.Latest].
 */
fun cacheControl(selector: RevisionSelector, public: Boolean, latestMaxAge: Duration): String {
    val visibility = if (public) "public" else "private"
    return when (selector) {
        RevisionSelector.Latest -> {
            "$visibility, max-age=${latestMaxAge.seconds}"
        }

        is RevisionSelector.Exact -> {
            val shared = if (public) ", s-maxage=${latestMaxAge.seconds}" else ""
            "$visibility, max-age=${IMMUTABLE_MAX_AGE.seconds}$shared, immutable"
        }
    }
}

/**
 * A Cached value holds a response body, its entity tag without quotes, and its `Cache-Control` value. The HTTP layer
 * of the `content` module sends the entity tag and the `Cache-Control` value as headers. Spring MVC then answers a
 * matching `If-None-Match` with 304. The service authorized the caller before it made the value, so a 304 never
 * bypasses a denial (design 12.1).
 */
data class Cached<T>(val body: T, val etag: String, val cacheControl: String)

/**
 * Returns an entity tag for a body that has no content hash of its own. The tag is the first 128 bits of the SHA-256
 * of [parts]. The caller must give each part that decides the body. Otherwise a changed body keeps its old tag, and
 * caches keep the old body. A NUL separates the parts, because no part contains NUL. Thus two different lists of parts
 * never give the same text.
 */
fun digestTag(parts: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0000").toByteArray())
    return HexFormat.of().formatHex(digest, 0, DIGEST_TAG_BYTES)
}

private const val DIGEST_TAG_BYTES = 16

private val IMMUTABLE_MAX_AGE: Duration = Duration.ofDays(365)
