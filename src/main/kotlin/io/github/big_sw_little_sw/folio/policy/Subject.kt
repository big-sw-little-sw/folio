package io.github.big_sw_little_sw.folio.policy

import io.github.big_sw_little_sw.folio.security.ApplicationPrincipal

/**
 * Who a rule grants to. The text form is `public`, `authenticated`, `user:<subject>`, `group:<group>` or
 * `application:<id>`, the same form super admins use (ADR 0002). IDs are the mapped JWT claim values.
 */
sealed interface Subject {
    fun matches(principal: ApplicationPrincipal): Boolean

    /** Everyone, including anonymous callers. */
    data object Public : Subject {
        override fun matches(principal: ApplicationPrincipal) = true

        override fun toString() = "public"
    }

    data object Authenticated : Subject {
        override fun matches(principal: ApplicationPrincipal) = principal is ApplicationPrincipal.Authenticated

        override fun toString() = "authenticated"
    }

    data class User(
        val id: String,
    ) : Subject {
        override fun matches(principal: ApplicationPrincipal) =
            principal is ApplicationPrincipal.Authenticated && principal.subject == id

        override fun toString() = "user:$id"
    }

    data class Group(
        val id: String,
    ) : Subject {
        override fun matches(principal: ApplicationPrincipal) =
            principal is ApplicationPrincipal.Authenticated && id in principal.groups

        override fun toString() = "group:$id"
    }

    data class Application(
        val id: String,
    ) : Subject {
        override fun matches(principal: ApplicationPrincipal) =
            principal is ApplicationPrincipal.Authenticated && principal.applicationId == id

        override fun toString() = "application:$id"
    }

    companion object {
        /** Matches the `external_id` column. */
        const val MAX_ID_LENGTH = 500

        fun parse(text: String): Subject {
            val type = text.substringBefore(':')
            val id = text.substringAfter(':', missingDelimiterValue = "")
            val subject =
                when {
                    text == "public" -> Public
                    text == "authenticated" -> Authenticated
                    id.isBlank() || id.length > MAX_ID_LENGTH -> null
                    type == "user" -> User(id)
                    type == "group" -> Group(id)
                    type == "application" -> Application(id)
                    else -> null
                }
            return subject ?: throw InvalidSubjectException(text)
        }
    }
}
