package io.github.big_sw_little_sw.folio.catalog.internal

import io.github.big_sw_little_sw.folio.catalog.CatalogException
import io.github.big_sw_little_sw.folio.catalog.Slug

/** The changes of a rename, a describe or both. A null [slug] keeps the current slug. */
data class Edit(val slug: Slug? = null, val description: DescriptionChange = DescriptionChange.Keep) {
    fun slugOf(current: Slug): Slug = slug ?: current

    fun descriptionOf(current: String?): String? = when (description) {
        DescriptionChange.Keep -> current
        is DescriptionChange.Replace -> description.text
    }
}

/** The change that an [Edit] makes to the description. */
sealed interface DescriptionChange {
    data object Keep : DescriptionChange

    /**
     * Sets the description. A null or blank text clears the description. The constructor throws
     * [CatalogException.InvalidDescription], so a request with an invalid description fails with 400 before Folio finds
     * the resource.
     */
    class Replace(text: String?) : DescriptionChange {
        val text: String? = validDescription(text)
    }
}

private const val MAX_DESCRIPTION_LENGTH = 1000

/**
 * Returns the description to store. A blank description becomes null, so an empty form field clears the
 * description. The function changes CRLF line breaks to LF. Browsers send textarea values with CRLF, and API clients
 * send LF. Without the change, the same text from the UI and the API would give two different values.
 *
 * Throws [CatalogException.InvalidDescription] for more than 1,000 characters or for a NUL character. The function
 * counts code points, as `char_length` in SQL does. PostgreSQL text rejects a NUL character.
 */
fun validDescription(text: String?): String? {
    val normalized = text?.replace("\r\n", "\n") ?: return null
    if (normalized.codePointCount(0, normalized.length) > MAX_DESCRIPTION_LENGTH || '\u0000' in normalized) {
        throw CatalogException.InvalidDescription()
    }
    return normalized.takeIf { it.isNotBlank() }
}
