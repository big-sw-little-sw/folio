package io.github.big_sw_little_sw.testing

import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver

/**
 * Before each test, this extension revokes all keys on [SshGitServer] and creates the repository `configs` again. All
 * tests share the server, so without the reset, a key or a commit of an earlier test can change the result. A test
 * method gets the commits of the new repository as a [Commits] parameter. A class or a single test opts in, because
 * the reset takes time.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(FreshGitRepositoryExtension::class)
annotation class FreshGitRepository

/** The commits on `main` of the repository `configs`, oldest first (see [SshGitServer.createRepository]). */
data class Commits(val all: List<String>) {
    val first: String get() = all.first()
    val tip: String get() = all.last()
}

class FreshGitRepositoryExtension :
    BeforeEachCallback,
    ParameterResolver {
    override fun beforeEach(context: ExtensionContext) {
        SshGitServer.revokeAll()
        context.getStore(NAMESPACE).put(Commits::class.java, Commits(SshGitServer.createRepository("configs")))
    }

    override fun supportsParameter(parameter: ParameterContext, context: ExtensionContext) =
        parameter.parameter.type == Commits::class.java

    override fun resolveParameter(parameter: ParameterContext, context: ExtensionContext): Commits =
        checkNotNull(context.getStore(NAMESPACE).get(Commits::class.java, Commits::class.java))

    private companion object {
        val NAMESPACE: ExtensionContext.Namespace = ExtensionContext.Namespace.create(FreshGitRepository::class.java)
    }
}
