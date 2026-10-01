package io.github.big_sw_little_sw.folio.policy

/**
 * What a rule grants. Stored by name, so renaming a constant needs a migration.
 * Only the actions v1 features need (design 9.1, narrowed by v1-scope).
 */
enum class Action {
    NAMESPACE_VIEW,
    NAMESPACE_CREATE,
    NAMESPACE_RENAME,
    NAMESPACE_MOVE,
    NAMESPACE_DELETE,

    CONFIG_SET_VIEW,
    CONFIG_SET_CREATE,
    CONFIG_SET_MOVE,
    CONFIG_SET_DELETE,

    /** File listing and file reads at latest or an exact revision. */
    CONFIG_ITEM_READ,
    CONFIG_VERSION_LIST,

    /** Also allows explaining decisions on the resource. */
    POLICY_VIEW,
    POLICY_UPDATE,

    SOURCE_VIEW,
    SOURCE_SYNC,

    CREDENTIAL_VIEW,
    CREDENTIAL_MANAGE,

    CRYPTO_REENCRYPT,
}
