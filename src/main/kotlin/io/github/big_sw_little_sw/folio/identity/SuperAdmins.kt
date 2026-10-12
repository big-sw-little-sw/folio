package io.github.big_sw_little_sw.folio.identity

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `folio.super-admins`: the subjects of the super admins. Folio does not start if an entry is not valid. Other modules
 * read [Caller.superAdmin]. Outside `identity`, only an explanation calls this class. The caller that an explanation
 * describes comes from a request, not from a token, so the explanation must give it the status that a token with the
 * same claims gets.
 */
@ConfigurationProperties("folio")
class SuperAdmins(superAdmins: List<String> = emptyList()) {
    private val entries: List<Subject> =
        superAdmins.mapIndexed { index, text ->
            try {
                Subject.parse(text)
            } catch (_: IdentityException.InvalidSubject) {
                // The message names the index, not the value, as the other startup checks do. Thus the startup log
                // never contains a subject.
                throw IllegalArgumentException("folio.super-admins[$index] is not user:, group: or application:")
            }
        }

    init {
        // A `public` or an `authenticated` entry would make each caller a super admin.
        require(Subject.Public !in entries && Subject.Authenticated !in entries) {
            "folio.super-admins must name users, groups or applications, not public or authenticated"
        }
    }

    /**
     * Makes the authenticated caller with the super-admin status from the configuration. Bearer tokens and sign-in use
     * this one function, so a person has the same status in the API and in the UI.
     */
    fun caller(subject: String, groups: Set<String>, applicationId: String?): Caller.Authenticated {
        val caller = Caller.Authenticated(subject, groups, applicationId)
        return caller.copy(superAdmin = entryFor(caller) != null)
    }

    /** The first entry that names [caller], or null. An explanation shows this entry as its reason. */
    fun entryFor(caller: Caller): Subject? {
        val names = Subject.matching(caller)
        return entries.firstOrNull { it in names }
    }
}
