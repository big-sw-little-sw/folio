package io.github.big_sw_little_sw.folio.catalog

import io.github.big_sw_little_sw.folio.catalog.internal.Ancestry
import io.github.big_sw_little_sw.folio.catalog.internal.Authorizer
import io.github.big_sw_little_sw.folio.catalog.internal.TreeRepository
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.stereotype.Service

/**
 * Other modules authorize through this class (design 8.1). The caller finds the resource first, so Access never reports
 * "not found". A super admin ([Caller.superAdmin]) can do all actions. For other callers, the nearest rule for the
 * action decides (design 7.2).
 *
 * [Caller.System] matches no rule and is never a super admin. Background work acts without authorization, so it does
 * not call Access. If background work calls Access, Access denies it with status 403.
 *
 * Each method that reads rules reads the ancestry and the rules in one snapshot ([SnapshotRead]). When Access joins the
 * transaction of a caller, that transaction sets the isolation. Thus a caller that authorizes a read must use
 * [SnapshotRead] too.
 */
@Service
class Access(private val tree: TreeRepository, private val authorizer: Authorizer) {
    /** Throws Forbidden (403) or Unauthenticated (401) if the caller cannot do the action. */
    @SnapshotRead
    fun requireAllowed(caller: Caller, action: Action, resource: ResourceId) =
        authorizer.require(caller, action, ancestry(resource), resource)

    /** True if the caller can do the action. The UI uses it to show only the operations that the caller can do. */
    @SnapshotRead
    fun allows(caller: Caller, action: Action, resource: ResourceId): Boolean =
        authorizer.decide(caller, action, ancestry(resource)).allowed

    /**
     * True if an anonymous caller can READ the resource. The `content` module uses this method for Cache-Control. The
     * call to [allows] does not go through the Spring proxy. Thus this method needs its own [SnapshotRead] annotation,
     * or it runs without a snapshot.
     */
    @SnapshotRead
    fun isPublic(resource: ResourceId): Boolean = allows(Caller.Anonymous, Action.READ, resource)

    /**
     * Throws Forbidden (403) on the root or Unauthenticated (401) if the caller is not a super admin. Master-key
     * operations use this check (design 7.3). The root has no rules, so this check is the same as `MANAGE` at the root.
     * Thus a top-level create gives the same errors. The check needs no query.
     */
    fun requireSuperAdmin(caller: Caller) = authorizer.require(caller, Action.MANAGE, Ancestry.ROOT)

    /**
     * A resource that was deleted after the caller found it has no ancestry. The root ancestry then applies, and the
     * root has no rules. Thus only super admins pass, and the next step of the caller finds nothing.
     */
    private fun ancestry(resource: ResourceId): Ancestry = tree.ancestry(resource) ?: Ancestry.ROOT
}
