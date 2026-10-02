package io.github.big_sw_little_sw.folio.source

import io.github.big_sw_little_sw.folio.credential.CredentialId
import org.eclipse.jgit.lib.Repository

/**
 * Where a ConfigSet's content comes from (design 12.1, ADR 0022): a branch of a repository on the Git service
 * instance of [credentialId], and the directory in it that holds the ConfigSet's files. The host, port and SSH
 * user come from the credential's instance, so a source cannot point at a host its credential does not belong to.
 */
data class SourceDefinition(
    val credentialId: CredentialId,
    val repositoryPath: RepositoryPath,
    val branch: Branch,
    val rootPath: SourcePath,
)

/** A repository's path on its Git service instance, such as `org/repo.git`; it becomes the SSH URL's path. */
@JvmInline
value class RepositoryPath(
    val value: String,
) {
    init {
        val dotSegment = value.split('/').any { it == "." || it == ".." }
        if (value.length > MAX_LENGTH || !SEGMENTS.matches(value) ||
            dotSegment
        ) {
            throw InvalidRepositoryPathException(value)
        }
    }

    override fun toString() = value

    private companion object {
        /** Matches the `config_set.repository_path` column. */
        const val MAX_LENGTH = 500
        val SEGMENTS = Regex("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*")
    }
}

/** A branch name, without `refs/heads/`; v1 sources follow branches only (ADR 0022). */
@JvmInline
value class Branch(
    val value: String,
) {
    init {
        if (value.length > MAX_LENGTH || !Repository.isValidRefName(ref(value))) throw InvalidBranchException(value)
    }

    val ref: String get() = ref(value)

    override fun toString() = value

    private companion object {
        /** Matches the `config_set.branch` column. */
        const val MAX_LENGTH = 250

        fun ref(name: String) = "refs/heads/$name"
    }
}
