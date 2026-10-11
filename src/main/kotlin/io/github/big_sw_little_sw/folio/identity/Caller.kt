package io.github.big_sw_little_sw.folio.identity

/** The identity that makes a request. Services get it as an explicit parameter (see [CurrentCaller]). */
sealed interface Caller {
    /**
     * True if an entry of `folio.super-admins` names the caller (design 7.2). Identity sets this value with
     * [SuperAdmins] when it makes the caller. Authorization and audit records read the value here. The other modules
     * depend on `identity`, so only `identity` can compute the value for all of them.
     */
    val superAdmin: Boolean

    data object Anonymous : Caller {
        override val superAdmin get() = false
    }

    /**
     * Identity makes an authenticated caller with [SuperAdmins.caller], so [superAdmin] follows the configuration. An
     * explanation request describes a caller without a super-admin status. `RuleService.explain` then calls
     * [SuperAdmins.caller] to apply the configuration (design 7.4). Tests can set [superAdmin] directly.
     */
    data class Authenticated(
        val subject: String,
        val groups: Set<String>,
        val applicationId: String?,
        override val superAdmin: Boolean = false,
    ) : Caller

    /**
     * The caller of background work, for example sync. Background work acts without authorization, so this caller
     * matches no rule and is never a super admin. Audit records show this caller as SYSTEM.
     */
    data object System : Caller {
        override val superAdmin get() = false
    }
}
