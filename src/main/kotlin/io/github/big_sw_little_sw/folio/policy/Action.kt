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

    CONFIG_SET_VIEW,

    /** Checked on the namespace that will hold the ConfigSet (ADR 0014). */
    CONFIG_SET_CREATE,
    CONFIG_SET_RENAME,
    CONFIG_SET_MOVE,
    CONFIG_SET_DELETE,

    /** Requesting a sync now (ADR 0032). */
    CONFIG_SET_SYNC,

    /** Listing and reading files at the latest or any synced revision (ADR 0035). */
    CONFIG_ITEM_READ,

    /** Listing synced revisions (ADR 0035). */
    CONFIG_VERSION_LIST,

    /** Also allows explaining decisions on the resource. */
    POLICY_VIEW,
    POLICY_UPDATE,

    // Checked at the root, where only super admins hold actions in v1 (ADR 0018).
    CREDENTIAL_VIEW,
    CREDENTIAL_MANAGE,

    /** Attaching a credential to a ConfigSet's source and checking access with it (ADR 0023). */
    CREDENTIAL_USE,

    /** Master-key usage and re-encryption. */
    CRYPTO_MANAGE,
}
