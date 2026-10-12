package io.github.big_sw_little_sw.folio.catalog

import io.github.big_sw_little_sw.folio.web.ErrorKind
import io.github.big_sw_little_sw.folio.web.FolioException

/**
 * The expected failures of the catalog. A detail repeats a value from the client only after validation, for example a
 * slug. Thus a detail never contains text that Folio did not validate.
 */
sealed class CatalogException(kind: ErrorKind, code: String, detail: String) : FolioException(kind, code, detail) {
    class NamespaceNotFound(id: NamespaceId) :
        CatalogException(ErrorKind.NOT_FOUND, "NAMESPACE_NOT_FOUND", "Namespace ${id.toApiId()} does not exist.")

    class ConfigSetNotFound(id: ConfigSetId) :
        CatalogException(ErrorKind.NOT_FOUND, "CONFIG_SET_NOT_FOUND", "ConfigSet ${id.toApiId()} does not exist.")

    /** Resolve gives this failure for a path that does not exist and for a path that the caller cannot read. */
    class PathNotFound :
        CatalogException(ErrorKind.NOT_FOUND, "PATH_NOT_FOUND", "No ConfigSet that the caller can read has this path.")

    /** An authenticated caller or `Caller.System` cannot do the action on [target]. A null [target] means the root. */
    class Forbidden(action: Action, target: ResourceId?) :
        CatalogException(
            ErrorKind.FORBIDDEN,
            "ACTION_NOT_ALLOWED",
            "The caller does not have $action on ${target?.toApiId() ?: "the root"}.",
        )

    /**
     * An anonymous caller cannot do the action. With a bearer token or after a sign-in, the caller can have the action.
     * The detail names only the action, so resolve gives the same detail for all paths.
     */
    class Unauthenticated(action: Action) :
        CatalogException(
            ErrorKind.UNAUTHENTICATED,
            "AUTHENTICATION_REQUIRED",
            "An anonymous caller does not have $action. Send a bearer token.",
        )

    class InvalidSlug :
        CatalogException(
            ErrorKind.INVALID,
            "INVALID_SLUG",
            "A slug has 1 to 100 lowercase letters, digits and single hyphens between them.",
        )

    class InvalidPath :
        CatalogException(
            ErrorKind.INVALID,
            "INVALID_PATH",
            "A path is slugs joined by '/', with no slash at the start or the end. A ConfigSet path has two or " +
                "more segments.",
        )

    class InvalidDescription :
        CatalogException(
            ErrorKind.INVALID,
            "INVALID_DESCRIPTION",
            "A description has 1,000 characters or fewer and no NUL characters.",
        )

    /** A rule without subjects would deny all callers below it. Such a rule is a deny rule, and Folio has none. */
    class NoSubjects : CatalogException(ErrorKind.INVALID, "NO_SUBJECTS", "A rule has one or more subjects.")

    /** A sibling already has the slug. [parent] is null at the top level. */
    class SlugTaken(slug: Slug, parent: NamespaceId?) :
        CatalogException(
            ErrorKind.CONFLICT,
            "SLUG_TAKEN",
            "The slug '$slug' is already in use in ${parent?.toApiId() ?: "the top level"}.",
        )

    class NamespaceNotEmpty(id: NamespaceId) :
        CatalogException(
            ErrorKind.CONFLICT,
            "NAMESPACE_NOT_EMPTY",
            "Namespace ${id.toApiId()} contains Namespaces, ConfigSets or credentials. Folio deletes only an empty " +
                "Namespace.",
        )

    class MoveIntoOwnSubtree(id: NamespaceId) :
        CatalogException(
            ErrorKind.CONFLICT,
            "MOVE_INTO_OWN_SUBTREE",
            "Namespace ${id.toApiId()} cannot move into itself or into a Namespace below it.",
        )
}
