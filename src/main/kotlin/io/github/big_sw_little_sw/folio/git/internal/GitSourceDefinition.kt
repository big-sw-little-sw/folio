package io.github.big_sw_little_sw.folio.git.internal

import io.github.big_sw_little_sw.folio.content.ItemPath
import io.github.big_sw_little_sw.folio.credential.CredentialId
import io.github.big_sw_little_sw.folio.git.GitException
import org.eclipse.jgit.lib.Repository

/**
 * The Source definition of a Git ConfigSet (design 10.2). It does not change after creation, so published revisions
 * stay immutable (design 5.5). The credential names the Git instance, so the definition does not name it again.
 */
data class GitSourceDefinition(
    val credential: CredentialId,
    val repositoryPath: RepositoryPath,
    val branch: Branch,
    val rootPath: RootPath,
)

/**
 * The path of a repository on its Git instance, for example `org/repo.git`. It has segments of letters, digits, `.`,
 * `_` and `-`, joined by `/`. Thus it cannot contain a scheme, a user or a host. The constructor throws
 * [GitException.InvalidRepositoryPath] for other text.
 */
@JvmInline
value class RepositoryPath(val value: String) {
    init {
        val valid =
            value.length <= MAX_LENGTH &&
                SEGMENTS.matches(value) &&
                value.split('/').none { it == "." || it == ".." }
        if (!valid) throw GitException.InvalidRepositoryPath()
    }

    override fun toString(): String = value

    private companion object {
        const val MAX_LENGTH = 500
        val SEGMENTS = Regex("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*")
    }
}

/**
 * A branch name without `refs/heads/`, for example `main`. JGit validates it as a Git ref name. v1 supports no tags
 * and no pinned commits (design 10.2). The constructor throws [GitException.InvalidBranch] for other text.
 */
@JvmInline
value class Branch(val value: String) {
    init {
        if (value.length > MAX_LENGTH || !Repository.isValidRefName(refOf(value))) throw GitException.InvalidBranch()
    }

    /** The full ref name, for example `refs/heads/main`. */
    val ref: String get() = refOf(value)

    override fun toString(): String = value

    private companion object {
        const val MAX_LENGTH = 250

        fun refOf(name: String) = "refs/heads/$name"
    }
}

/**
 * The directory of the repository that contains the ConfigItems. The empty root path is the root of the repository.
 * Another root path has the normal form of an item path (design 9), so a ConfigItem path below it cannot go outside
 * it. The constructor throws [GitException.InvalidRootPath] for other text.
 */
@JvmInline
value class RootPath(val value: String) {
    init {
        // The root path uses the rules of the item path, but with its own error code.
        if (value.isNotEmpty() && (value.length > MAX_LENGTH || !ItemPath.isValid(value))) {
            throw GitException.InvalidRootPath()
        }
    }

    val isRepositoryRoot: Boolean get() = value.isEmpty()

    /** Returns the path in the repository of the ConfigItem [item]. */
    fun resolve(item: ItemPath): String = if (isRepositoryRoot) item.value else "$value/${item.value}"

    override fun toString(): String = value

    private companion object {
        const val MAX_LENGTH = 1000
    }
}
