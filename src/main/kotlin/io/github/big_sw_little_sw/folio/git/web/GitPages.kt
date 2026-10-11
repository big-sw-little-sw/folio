package io.github.big_sw_little_sw.folio.git.web

import io.github.big_sw_little_sw.folio.catalog.Access
import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.CONFIG_SET_ID
import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.catalog.NAMESPACE_ID
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.Namespaces
import io.github.big_sw_little_sw.folio.catalog.Slug
import io.github.big_sw_little_sw.folio.credential.KEY_ID
import io.github.big_sw_little_sw.folio.git.internal.CheckResult
import io.github.big_sw_little_sw.folio.git.internal.GitCheck
import io.github.big_sw_little_sw.folio.git.internal.GitSourceService
import io.github.big_sw_little_sw.folio.identity.CurrentCaller
import io.github.big_sw_little_sw.folio.web.Redirect
import io.github.big_sw_little_sw.folio.web.submit
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/** The values of the connect form. The form shows them again after a failure. */
data class ConnectForm(
    val slug: String = "",
    val description: String = "",
    val credentialId: String = "",
    val repositoryPath: String = "",
    val branch: String = "main",
    val rootPath: String = "",
)

/**
 * The Git pages: the form that connects a Git repository to a new ConfigSet, and the Git source of a ConfigSet with its
 * sync state, the check and the manual sync. Each page needs `MANAGE`, as its operations do. The path of a resource is
 * a link only for a caller that can read the resource.
 */
@Controller
class GitPages(
    private val currentCaller: CurrentCaller,
    private val sources: GitSourceService,
    private val check: GitCheck,
    private val namespaces: Namespaces,
    private val access: Access,
) {
    @GetMapping("/git/configsets/new")
    fun connectForm(@RequestParam namespaceId: String): ModelAndView =
        connectPage(NAMESPACE_ID.parse(namespaceId), ConnectForm())

    @PostMapping("/git/configsets")
    fun connect(@RequestParam namespaceId: String, form: ConnectForm, redirect: RedirectAttributes): ModelAndView {
        val namespace = NAMESPACE_ID.parse(namespaceId)
        return submit(redirect, { connectPage(namespace, form) }) {
            val source = sourceDefinitionOf(form.credentialId, form.repositoryPath, form.branch, form.rootPath)
            val created = sources.create(currentCaller.get(), namespace, Slug(form.slug), form.description, source)
            Redirect(
                sourceUrl(created.configSet.id),
                "ConfigSet ${created.configSet.path} created. Register the public key of the credential with the " +
                    "Git service, then run the check.",
            )
        }
    }

    @GetMapping("/configsets/{id}/git")
    fun source(@PathVariable id: String): ModelAndView = sourcePage(CONFIG_SET_ID.parse(id), null)

    /** The notice of the next page gives the result. A failed check is a result, not a form error. */
    @PostMapping("/configsets/{id}/git/check")
    fun check(
        @PathVariable id: String,
        @RequestParam(required = false) keyId: String?,
        redirect: RedirectAttributes,
    ): ModelAndView {
        val configSet = CONFIG_SET_ID.parse(id)
        return submit(redirect, { sourcePage(configSet, keyId) }) {
            val pendingKey = keyId?.takeIf { it.isNotBlank() }?.let(KEY_ID::parse)
            val notice =
                when (val result = check.check(currentCaller.get(), configSet, pendingKey)) {
                    is CheckResult.Passed -> "The check passed. The tip of the branch is ${result.commitId.name}."
                    is CheckResult.Failed -> "The check failed with ${result.code}. ${result.code.summary}"
                }
            Redirect(sourceUrl(configSet), notice)
        }
    }

    /** The notice of the next page says that the next poll syncs the ConfigSet. */
    @PostMapping("/configsets/{id}/git/sync")
    fun sync(@PathVariable id: String, redirect: RedirectAttributes): ModelAndView {
        val configSet = CONFIG_SET_ID.parse(id)
        return submit(redirect, { sourcePage(configSet, null) }) {
            sources.requestSync(currentCaller.get(), configSet)
            Redirect(sourceUrl(configSet), "The ConfigSet is due now. The next poll of an instance syncs it.")
        }
    }

    private fun connectPage(namespace: NamespaceId, form: ConnectForm): ModelAndView {
        val caller = currentCaller.get()
        return ModelAndView("git/web/connect")
            .addObject("namespace", namespaces.require(caller, Action.MANAGE, namespace))
            .addObject("namespaceReadable", access.allows(caller, Action.READ, namespace))
            .addObject("form", form)
    }

    private fun sourcePage(id: ConfigSetId, keyId: String?): ModelAndView {
        val caller = currentCaller.get()
        return ModelAndView("git/web/source")
            .addObject("view", sources.get(caller, id))
            .addObject("configSetReadable", access.allows(caller, Action.READ, id))
            .addObject("keyId", keyId)
    }

    private fun sourceUrl(id: ConfigSetId) = "/configsets/${id.toApiId()}/git"
}
