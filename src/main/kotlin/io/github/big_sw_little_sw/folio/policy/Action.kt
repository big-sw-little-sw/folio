package io.github.big_sw_little_sw.folio.policy

/**
 * What a rule grants. Stored by name, so renaming a constant needs a migration.
 * Each slice adds the actions of the features it builds (design 9.1, narrowed by v1-scope).
 */
enum class Action {
    NAMESPACE_VIEW,
    NAMESPACE_CREATE,
    NAMESPACE_RENAME,
    NAMESPACE_MOVE,
    NAMESPACE_DELETE,

    /** Also allows explaining decisions on the resource. */
    POLICY_VIEW,
    POLICY_UPDATE,
}
