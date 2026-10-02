package io.github.big_sw_little_sw.folio.policy.internal

import io.github.big_sw_little_sw.folio.policy.Subject
import org.springframework.boot.context.properties.ConfigurationProperties

/** `folio.super-admins`: entries `user:<subject>`, `group:<group>` or `application:<id>` (ADR 0002, ADR 0028). */
@ConfigurationProperties("folio")
data class SuperAdminProperties(
    val superAdmins: List<String> = emptyList(),
) {
    val subjects: Set<Subject> = superAdmins.map(Subject::parse).toSet()

    init {
        require(subjects.none { it == Subject.Public || it == Subject.Authenticated }) {
            "folio.super-admins must name users, groups or applications, not public or authenticated"
        }
    }
}
