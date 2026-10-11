package io.github.big_sw_little_sw.folio.catalog.internal

import io.github.big_sw_little_sw.folio.catalog.CatalogException
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `folio.super-admins`: subjects that can do all actions on all resources (design 7.2). They are not rules, so no rule
 * can lock them out. Folio does not start if an entry is not valid.
 */
@ConfigurationProperties("folio")
data class SuperAdmins(private val superAdmins: List<String> = emptyList()) {
    val entries: Set<Subject> =
        superAdmins
            .mapIndexed { index, text ->
                try {
                    Subject.parse(text)
                } catch (_: CatalogException.InvalidSubject) {
                    // The message names the index, not the value, as for the other startup checks.
                    throw IllegalArgumentException("folio.super-admins[$index] is not user:, group: or application:")
                }
            }.toSet()

    init {
        // Either entry would make each caller a super admin.
        require(Subject.Public !in entries && Subject.Authenticated !in entries) {
            "folio.super-admins must name users, groups or applications, not public or authenticated"
        }
    }

    /** The first entry that matches [caller], or null. */
    fun match(caller: Caller): Subject? = entries.firstOrNull { it.matches(caller) }
}
