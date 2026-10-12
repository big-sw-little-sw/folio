package io.github.big_sw_little_sw.folio.identity

/**
 * The identity that a rule or a super-admin entry names. The text form is `public`, `authenticated`, `user:<id>`,
 * `group:<id>` or `application:<id>`. [toString] gives the text form.
 *
 * [matching] is the one definition of which subjects name a caller. Decisions, super-admin entries and the rule query
 * of the start page all use [matching]. Thus these three cannot disagree about a caller.
 */
sealed interface Subject {
    /** Names each anonymous or authenticated caller. */
    data object Public : Subject {
        override fun toString() = "public"
    }

    /** Names each caller that a valid bearer token or a browser sign-in identifies. */
    data object Authenticated : Subject {
        override fun toString() = "authenticated"
    }

    data class User(val id: String) : Subject {
        override fun toString() = "user:$id"
    }

    data class Group(val id: String) : Subject {
        override fun toString() = "group:$id"
    }

    /** Names each caller whose token gives this application ID in its application claim. */
    data class Application(val id: String) : Subject {
        override fun toString() = "application:$id"
    }

    companion object {
        /** The ID of a subject comes from a token claim. The limit keeps the size of a rule small. */
        private const val MAX_ID_LENGTH = 500

        private val TYPES: Map<String, (String) -> Subject> =
            mapOf("user" to ::User, "group" to ::Group, "application" to ::Application)

        /**
         * Reads the text form. Throws [IdentityException.InvalidSubject] for each other text. The ID is exact: case and
         * spaces count, so this function does not trim the ID or change its case.
         */
        fun parse(text: String): Subject {
            if (text == Public.toString()) return Public
            if (text == Authenticated.toString()) return Authenticated
            val type = TYPES[text.substringBefore(':')]
            val id = text.substringAfter(':', missingDelimiterValue = "")
            val validId = id.isNotBlank() && id.length <= MAX_ID_LENGTH && id.none { it.isISOControl() }
            if (type == null || !validId) throw IdentityException.InvalidSubject()
            return type(id)
        }

        /**
         * The subjects that name [caller]. [Caller.System] matches no subject, also not `public`, because background
         * work acts without authorization. The super-admin status of the caller does not change the result.
         */
        fun matching(caller: Caller): Set<Subject> = when (caller) {
            Caller.Anonymous -> {
                setOf(Public)
            }

            is Caller.Authenticated -> {
                buildSet {
                    add(Public)
                    add(Authenticated)
                    add(User(caller.subject))
                    caller.groups.mapTo(this, ::Group)
                    caller.applicationId?.let { add(Application(it)) }
                }
            }

            Caller.System -> {
                emptySet()
            }
        }
    }
}
