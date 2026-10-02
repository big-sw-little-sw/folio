package io.github.big_sw_little_sw.folio.namespace

import io.github.big_sw_little_sw.folio.policy.ResourceRef

/**
 * Published synchronously during a namespace move, inside its transaction and under the tree lock, after the move is
 * authorized and before the subtree moves. [subtree] is the moved namespace and its descendants; [targetPath] is the
 * policy path of the new parent, empty for the root. A listener vetoes the move by throwing.
 *
 * Modules that place resources in namespaces use it to check what moves with the subtree, which this module cannot
 * see (ADR 0015, ADR 0031).
 */
data class NamespaceMoving(
    val subtree: List<NamespaceId>,
    val targetPath: List<ResourceRef>,
)
