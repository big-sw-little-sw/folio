package io.github.big_sw_little_sw.folio.policy.internal

import io.github.big_sw_little_sw.folio.policy.Subject
import org.springframework.boot.context.properties.ConfigurationProperties

/** `folio.bootstrap.admins`: entries `user:<subject>`, `group:<group>` or `application:<id>` (ADR 0002). */
@ConfigurationProperties("folio.bootstrap")
data class BootstrapProperties(
    val admins: List<String> = emptyList(),
) {
    val adminSubjects: Set<Subject> = admins.map(Subject::parse).toSet()

    init {
        require(adminSubjects.none { it == Subject.Public || it == Subject.Authenticated }) {
            "folio.bootstrap.admins must name users, groups or applications, not public or authenticated"
        }
    }
}
