package io.github.big_sw_little_sw.folio.catalog.internal

import io.github.big_sw_little_sw.folio.catalog.CatalogException
import io.github.big_sw_little_sw.folio.catalog.Slug

/** The changes of a rename, a describe or both. A null [slug] stays the same. */
data class Edit(val slug: Slug? = null, val description: DescriptionChange = DescriptionChange.Keep) {
    fun slugOf(current: Slug): Slug = slug ?: current

    fun descriptionOf(current: String?): String? = when (description) {
        DescriptionChange.Keep -> current
        is DescriptionChange.Replace -> description.text
    }
}

/** What an [Edit] does to the description. */
sealed interface DescriptionChange {
    data object Keep : DescriptionChange

    /**
     * Sets the description. A null or blank text clears it. Throws [CatalogException.InvalidDescription] when it is
     * built, so a request fails with 400 before Folio finds the resource.
     */
    class Replace(text: String?) : DescriptionChange {
        val text: String? = validDescription(text)
    }
}

private const val MAX_DESCRIPTION_LENGTH = 1000

/**
 * The description to store (design 5.1). A blank description is no description, so that an empty form field clears
 * it. CRLF line breaks become LF, because browsers send textarea values with CRLF and API clients send LF. Thus the
 * same text from the UI and the API is the same value. Throws [CatalogException.InvalidDescription] above 1,000
 * characters (code points, as `char_length` in SQL counts them) or for a NUL character, which PostgreSQL rejects.
 */
fun validDescription(text: String?): String? {
    val normalized = text?.replace("\r\n", "\n") ?: return null
    if (normalized.codePointCount(0, normalized.length) > MAX_DESCRIPTION_LENGTH || '\u0000' in normalized) {
        throw CatalogException.InvalidDescription()
    }
    return normalized.takeIf { it.isNotBlank() }
}
