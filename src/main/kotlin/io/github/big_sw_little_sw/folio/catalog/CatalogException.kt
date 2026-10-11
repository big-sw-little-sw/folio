package io.github.big_sw_little_sw.folio.catalog

import io.github.big_sw_little_sw.folio.web.ErrorKind
import io.github.big_sw_little_sw.folio.web.FolioException

/**
 * The expected failures of the catalog (design 8.6). A detail repeats a client value only after validation, for
 * example a slug.
 */
sealed class CatalogException(kind: ErrorKind, code: String, detail: String) : FolioException(kind, code, detail) {
    class NamespaceNotFound(id: NamespaceId) :
        CatalogException(ErrorKind.NOT_FOUND, "NAMESPACE_NOT_FOUND", "Namespace ${id.toApiId()} does not exist.")

    class ConfigSetNotFound(id: ConfigSetId) :
        CatalogException(ErrorKind.NOT_FOUND, "CONFIG_SET_NOT_FOUND", "ConfigSet ${id.toApiId()} does not exist.")

    /**
     * Resolve gives the same failure for a path that does not exist and for a path that the caller cannot read,
     * because a person can guess a path (design 7.4).
     */
    class PathNotFound :
        CatalogException(ErrorKind.NOT_FOUND, "PATH_NOT_FOUND", "No ConfigSet that the caller can read has this path.")

    /** An authenticated caller cannot do the action. [target] is an API ID, or the root. */
    class Forbidden(action: Action, target: String) :
        CatalogException(ErrorKind.FORBIDDEN, "ACTION_NOT_ALLOWED", "The caller does not have $action on $target.")

    /**
     * An anonymous caller cannot do the action. A bearer token or a sign-in can permit it. [target] is null for
     * resolve, so that the detail is the same for all paths.
     */
    class Unauthenticated(action: Action, target: String?) :
        CatalogException(
            ErrorKind.UNAUTHENTICATED,
            "AUTHENTICATION_REQUIRED",
            "An anonymous caller does not have $action on ${target ?: "this path"}. Send a bearer token.",
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

    class InvalidSubject :
        CatalogException(
            ErrorKind.INVALID,
            "INVALID_SUBJECT",
            "A subject is public, authenticated, user:<id>, group:<id> or application:<id>.",
        )

    /** A rule without subjects would deny all callers below it, and v1 has no deny rules (design 7.2). */
    class NoSubjects : CatalogException(ErrorKind.INVALID, "NO_SUBJECTS", "A rule has one or more subjects.")

    /** A sibling already has the slug. [parent] is the path or API ID of the parent, or the top level. */
    class SlugTaken(slug: Slug, parent: String) :
        CatalogException(ErrorKind.CONFLICT, "SLUG_TAKEN", "The slug '$slug' is already in use in $parent.")

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
