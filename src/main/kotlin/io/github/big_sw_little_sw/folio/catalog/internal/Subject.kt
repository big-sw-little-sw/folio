package io.github.big_sw_little_sw.folio.catalog.internal

import io.github.big_sw_little_sw.folio.catalog.CatalogException
import io.github.big_sw_little_sw.folio.identity.Caller

/**
 * The identity that a rule or a super-admin entry names (design 7.2). The text form is `public`, `authenticated`,
 * `user:<id>`, `group:<id>` or `application:<id>`. [toString] gives the text form.
 *
 * [Caller.System] matches no subject, also not `public`. Background work acts without authorization.
 */
sealed interface Subject {
    fun matches(caller: Caller): Boolean

    /** All callers, also anonymous callers. */
    data object Public : Subject {
        override fun matches(caller: Caller) = caller !is Caller.System

        override fun toString() = "public"
    }

    /** All callers with a valid token. */
    data object Authenticated : Subject {
        override fun matches(caller: Caller) = caller is Caller.Authenticated

        override fun toString() = "authenticated"
    }

    data class User(val id: String) : Subject {
        override fun matches(caller: Caller) = caller is Caller.Authenticated && caller.subject == id

        override fun toString() = "user:$id"
    }

    data class Group(val id: String) : Subject {
        override fun matches(caller: Caller) = caller is Caller.Authenticated && id in caller.groups

        override fun toString() = "group:$id"
    }

    data class Application(val id: String) : Subject {
        override fun matches(caller: Caller) = caller is Caller.Authenticated && caller.applicationId == id

        override fun toString() = "application:$id"
    }

    companion object {
        /** IDs come from token claims. The limit keeps rules small. */
        private const val MAX_ID_LENGTH = 500

        private val TYPES: Map<String, (String) -> Subject> =
            mapOf("user" to ::User, "group" to ::Group, "application" to ::Application)

        /** Throws [CatalogException.InvalidSubject] for other forms. The ID is exact: case and spaces count. */
        fun parse(text: String): Subject {
            if (text == Public.toString()) return Public
            if (text == Authenticated.toString()) return Authenticated
            val type = TYPES[text.substringBefore(':')]
            val id = text.substringAfter(':', missingDelimiterValue = "")
            val validId = id.isNotBlank() && id.length <= MAX_ID_LENGTH && id.none { it.isISOControl() }
            if (type == null || !validId) throw CatalogException.InvalidSubject()
            return type(id)
        }
    }
}
