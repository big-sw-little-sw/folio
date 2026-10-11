package io.github.big_sw_little_sw.folio.catalog

import io.github.big_sw_little_sw.folio.catalog.internal.Ancestry
import io.github.big_sw_little_sw.folio.catalog.internal.Authorizer
import io.github.big_sw_little_sw.folio.catalog.internal.TreeRepository
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/**
 * Authorization for other modules (design 8.1). The caller finds the resource first, so this never reports "not
 * found". Super admins can do all actions. Otherwise the nearest rule for the action decides (design 7.2).
 *
 * [Caller.System] matches no rule and is never a super admin. Background work does not call this: it acts without
 * authorization.
 *
 * Each method reads the ancestry and the rules in one snapshot (see [TreeRepository.lock]). In the transaction of a
 * caller, Spring keeps the isolation of that transaction.
 */
@Service
class Access(private val tree: TreeRepository, private val authorizer: Authorizer) {
    /** Throws Forbidden (403) or Unauthenticated (401) if the caller cannot do the action. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun requireAllowed(caller: Caller, action: Action, resource: ResourceId) =
        authorizer.require(caller, action, ancestry(resource), resource)

    /** True if the caller can do the action. The UI uses it to show only the operations that the caller can do. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun allows(caller: Caller, action: Action, resource: ResourceId): Boolean =
        authorizer.decide(caller, action, ancestry(resource)).allowed

    /**
     * True if an anonymous caller can READ the resource. Content uses it for Cache-Control. Its call to [allows] does
     * not go through the Spring proxy, so it declares its own transaction.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun isPublic(resource: ResourceId): Boolean = allows(Caller.Anonymous, Action.READ, resource)

    /**
     * A resource that was deleted after the caller found it has no ancestry. It then has no rules, so only super admins
     * pass, and the next step of the caller finds nothing.
     */
    private fun ancestry(resource: ResourceId): Ancestry = tree.ancestry(resource) ?: Ancestry.ROOT
}
