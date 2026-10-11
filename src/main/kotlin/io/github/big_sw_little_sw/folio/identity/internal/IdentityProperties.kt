package io.github.big_sw_little_sw.folio.identity.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** `folio.security`: how Folio reads a caller from a token (design 6.1, 6.3). */
@ConfigurationProperties("folio.security")
data class IdentityProperties(
    val claims: ClaimNames = ClaimNames(),
    val directory: DirectoryType = DirectoryType.TOKEN,
) {
    /** The names of the claims that give the caller. Entra ID needs `subject: oid` (design 6.4). */
    data class ClaimNames(
        val subject: String = "sub",
        val groups: String = "groups",
        val applicationId: String = "azp",
    ) {
        init {
            require(subject.isNotBlank() && groups.isNotBlank() && applicationId.isNotBlank()) {
                "folio.security.claims names must not be blank"
            }
        }
    }

    /** The origin of the groups of a caller. The `google` directory comes in a later slice (design 20). */
    enum class DirectoryType {
        TOKEN,
    }
}

/** `folio.ui`: the browser sessions of the web UI (design 6.2). */
@ConfigurationProperties("folio.ui")
data class UiProperties(
    /** A session ends after this time without use. */
    val sessionTimeout: Duration = Duration.ofHours(1),
) {
    init {
        require(sessionTimeout.isPositive) { "folio.ui.session-timeout must be positive" }
    }
}
