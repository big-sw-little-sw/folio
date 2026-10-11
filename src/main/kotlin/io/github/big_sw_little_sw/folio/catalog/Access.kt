package io.github.big_sw_little_sw.folio.catalog

import io.github.big_sw_little_sw.folio.catalog.internal.Ancestry
import io.github.big_sw_little_sw.folio.catalog.internal.Authorizer
import io.github.big_sw_little_sw.folio.catalog.internal.TreeRepository
import io.github.big_sw_little_sw.folio.identity.Caller
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Other modules authorize through this class, because only the catalog reads rules. Other modules find the resource
 * first, so Access never reports "not found".
 *
 * Background work acts without authorization and does not call Access. If background work calls Access,
 * [Caller.System] gets status 403.
 *
 * A service that finds a resource and then calls Access must use [SnapshotRead]. A write of another module, for example
 * a credential change, runs at `READ COMMITTED`. Access then takes the shared form of the tree lock before it reads the
 * ancestry. Without the lock, a catalog write can commit between the find and the ancestry read.
 *
 * A write that also takes the exclusive tree lock, for example through [ConfigSets.create], must take that lock before
 * it calls Access. Otherwise two such writes can each hold the shared form and wait for the exclusive form, and they
 * deadlock.
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
     * True if an anonymous caller can READ the resource. The call to [allows] does not go through the Spring proxy, so
     * this method needs its own [SnapshotRead]. Without it, the method runs without a snapshot.
     */
    @SnapshotRead
    fun isPublic(resource: ResourceId): Boolean = allows(Caller.Anonymous, Action.READ, resource)

    /**
     * Throws Forbidden (403) on the root or Unauthenticated (401) if the caller is not a super admin. The root has no
     * rules, so this check is the same as `MANAGE` at the root. Thus a master-key operation and a top-level create give
     * the same errors.
     */
    fun requireSuperAdmin(caller: Caller) = authorizer.require(caller, Action.MANAGE, Ancestry.ROOT)

    /**
     * A resource that was deleted after the calling module found it has no ancestry. The root ancestry then applies, so
     * only a super admin passes, and the next step of the calling module finds nothing.
     */
    private fun ancestry(resource: ResourceId): Ancestry {
        // A read-only transaction is a SnapshotRead. Its one snapshot already covers both reads, so the read needs no
        // lock.
        if (!TransactionSynchronizationManager.isCurrentTransactionReadOnly()) tree.lockShared()
        return tree.ancestry(resource) ?: Ancestry.ROOT
    }
}
