package io.github.big_sw_little_sw.folio.git.internal

import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.credential.CredentialException
import io.github.big_sw_little_sw.folio.credential.KeyId
import io.github.big_sw_little_sw.folio.identity.Caller
import org.eclipse.jgit.lib.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** The result of a Git check. A failed check is a correct answer, not an error. */
sealed interface CheckResult {
    /** The key reaches the repository, the branch exists, and the root path is a directory at the tip [commitId]. */
    data class Passed(val commitId: ObjectId) : CheckResult

    data class Failed(val code: FailureCode) : CheckResult
}

/**
 * The Git check fetches the branch into the cache and makes sure that the root path is a directory at the tip. The
 * check does not publish a revision.
 *
 * The check runs outside a transaction, so a long fetch holds no database connection. Thus the check can also catch
 * `CredentialDisabled` and report it as a result. In a transaction, Spring marks the transaction for rollback when the
 * credential module throws that error.
 */
@Service
class GitCheck(
    private val sources: GitSourceService,
    private val fetcher: Fetcher,
    private val cache: RepositoryCache,
) {
    /**
     * Checks the Git source with the active key, or with [pendingKey] when the caller gives one. Thus an administrator
     * can test a new key before the activation. The check gives its answers in this order:
     *
     * 1. The errors of [GitSourceService.requireCheckable], except `CredentialDisabled`.
     * 2. The result `CREDENTIAL_DISABLED`.
     * 3. The error `KeyNotPending` (409), if [pendingKey] is not the pending key.
     * 4. The other results.
     */
    fun check(caller: Caller, id: ConfigSetId, pendingKey: KeyId?): CheckResult {
        val started = System.nanoTime()
        val source =
            try {
                sources.requireCheckable(caller, id)
            } catch (_: CredentialException.CredentialDisabled) {
                return failed(id, FailureCode.CREDENTIAL_DISABLED, started)
            }
        val tip =
            try {
                fetcher.fetch(id, source, pendingKey)
            } catch (failure: GitFailure) {
                // The fetcher already logged the failure.
                return CheckResult.Failed(failure.code)
            }
        val rootFound = cache.reading(id, { false }) { rootTreeOf(it, commitOf(it, tip), source.rootPath) != null }
        return if (rootFound) CheckResult.Passed(tip) else failed(id, FailureCode.ROOT_PATH_NOT_FOUND, started)
    }

    /** Logs a failure that the fetcher does not see, and returns the failure as a result. */
    private fun failed(id: ConfigSetId, code: FailureCode, started: Long): CheckResult {
        logFailure(log, "check", id, GitFailure(code), started)
        return CheckResult.Failed(code)
    }

    private companion object {
        val log = LoggerFactory.getLogger(GitCheck::class.java)
    }
}
