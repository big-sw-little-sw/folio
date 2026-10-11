package io.github.big_sw_little_sw.folio.catalog

/**
 * The operation that a rule permits (design 7.1). The actions are independent: `MANAGE` does not include `READ`.
 */
enum class Action {
    /** See the resource, its path and its children. Resolve it. Read the content of a ConfigSet. */
    READ,

    /** Create children. Rename, describe, move and delete. Use the credentials that a Namespace owns. */
    MANAGE,

    /** List, put and delete rules. Explain decisions. */
    GRANT,
}
