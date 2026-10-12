package io.github.big_sw_little_sw.folio.catalog

import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/**
 * Marks a read that authorizes. The read runs in one read-only `REPEATABLE READ` transaction, so the ancestry and the
 * rules of a decision come from one snapshot. Without the snapshot, a decision could combine a tree and rules that
 * never existed together.
 *
 * A service of another module that finds a resource and then calls [Access] must use this annotation too. [Access]
 * joins the transaction of that service, and that transaction sets the isolation.
 *
 * Writes do not use this annotation. Writes keep `READ COMMITTED`, because their snapshot must start after the tree
 * lock. Without that, a write would not see the commit of the previous write.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
annotation class SnapshotRead
