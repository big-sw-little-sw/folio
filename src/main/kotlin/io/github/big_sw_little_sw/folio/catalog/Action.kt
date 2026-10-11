package io.github.big_sw_little_sw.folio.catalog

/**
 * The operation that a rule permits (design 7.1). The actions are independent: `MANAGE` does not include `READ`. A
 * person who needs both actions needs a grant of each.
 */
enum class Action {
    /**
     * The caller can see the resource, its path and its children, and can resolve the resource. On a ConfigSet, the
     * caller can also read the metadata, the revisions and the files.
     */
    READ,

    /**
     * The caller can create children, and can rename, describe, move and delete the resource. On a ConfigSet, the
     * caller can also create and check a Git source and request sync. On a Namespace, the caller can also create,
     * rotate, disable and use the credentials that the Namespace owns.
     */
    MANAGE,

    /** The caller can list, put and delete the rules of the resource, and can explain the decisions on the resource. */
    GRANT,
}
