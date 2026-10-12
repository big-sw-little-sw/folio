package io.github.big_sw_little_sw.folio.identity

/** The identity that makes a request. Services get it as an explicit parameter (see [CurrentCaller]). */
sealed interface Caller {
    data object Anonymous : Caller

    data class Authenticated(val subject: String, val groups: Set<String>, val applicationId: String?) : Caller

    /** Background work such as sync. It matches no rule. Audit records show it as SYSTEM. */
    data object System : Caller
}
