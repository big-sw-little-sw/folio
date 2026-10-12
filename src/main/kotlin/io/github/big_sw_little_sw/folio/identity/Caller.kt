package io.github.big_sw_little_sw.folio.identity

/** The identity that makes a request. Services get it as an explicit parameter (see [CurrentCaller]). */
sealed interface Caller {
    /**
     * True if an entry of `folio.super-admins` names the caller. Identity sets this value with [SuperAdmins] when it
     * makes the caller. The other modules depend on `identity`, so only `identity` can compute the value for all of
     * them.
     */
    val superAdmin: Boolean

    data object Anonymous : Caller {
        override val superAdmin get() = false
    }

    /**
     * Identity makes the caller of each request with [SuperAdmins.caller], so [superAdmin] follows the configuration.
     * Code that builds this class directly, for example to describe a caller, gets no super-admin status unless it sets
     * one.
     */
    data class Authenticated(
        val subject: String,
        val groups: Set<String>,
        val applicationId: String?,
        override val superAdmin: Boolean = false,
    ) : Caller

    /**
     * The caller of background work, for example sync. This caller matches no subject (see [Subject.matching]) and is
     * never a super admin. Audit records show this caller as SYSTEM.
     */
    data object System : Caller {
        override val superAdmin get() = false
    }
}
