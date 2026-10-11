package io.github.big_sw_little_sw.folio.credential.web

import io.github.big_sw_little_sw.folio.catalog.Access
import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.NAMESPACE_ID
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.Slug
import io.github.big_sw_little_sw.folio.credential.CREDENTIAL_ID
import io.github.big_sw_little_sw.folio.credential.CredentialId
import io.github.big_sw_little_sw.folio.credential.KEY_ID
import io.github.big_sw_little_sw.folio.credential.internal.Credential
import io.github.big_sw_little_sw.folio.credential.internal.CredentialService
import io.github.big_sw_little_sw.folio.credential.internal.MasterKeyService
import io.github.big_sw_little_sw.folio.identity.Caller
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

/**
 * The credential pages (design 12.3). The catalog does not depend on this module, so this module serves the
 * credential list of a Namespace at `/namespaces/{id}/credentials`. The Namespace page links to that URL. Each page
 * and each form needs `MANAGE` on the Namespace that owns the credential, as the operations do (design 7.3). `MANAGE`
 * does not include `READ`, so the path of the Namespace is a link only for a caller that can read the Namespace
 * (design 12.3).
 */
@Controller
class CredentialPages(
    private val currentCaller: CurrentCaller,
    private val credentials: CredentialService,
    private val access: Access,
) {
    @GetMapping("/namespaces/{id}/credentials")
    fun list(@PathVariable id: String): ModelAndView = listPage(NAMESPACE_ID.parse(id), null, null)

    @PostMapping("/namespaces/{id}/credentials")
    fun create(
        @PathVariable id: String,
        @RequestParam name: String,
        @RequestParam gitInstance: String,
        redirect: RedirectAttributes,
    ): ModelAndView {
        val namespace = NAMESPACE_ID.parse(id)
        return submit(redirect, { listPage(namespace, name, gitInstance) }) {
            val created = credentials.create(currentCaller.get(), namespace, Slug(name), Slug(gitInstance))
            Redirect(
                credentialUrl(created.id),
                "Credential ${created.name} created. Register its public key with the Git service.",
            )
        }
    }

    @GetMapping("/credentials/{id}")
    fun credential(@PathVariable id: String): ModelAndView = credentialPage(CREDENTIAL_ID.parse(id))

    @PostMapping("/credentials/{id}/regenerate")
    fun regenerate(@PathVariable id: String, redirect: RedirectAttributes): ModelAndView = change(id, redirect, {
        "A pending key was added to credential ${it.name}. Register its public key."
    }) { caller, credential ->
        credentials.regenerate(caller, credential)
    }

    @PostMapping("/credentials/{id}/activate")
    fun activate(@PathVariable id: String, @RequestParam keyId: String, redirect: RedirectAttributes): ModelAndView =
        change(id, redirect, { "The pending key of credential ${it.name} is active." }) { caller, credential ->
            credentials.activate(caller, credential, KEY_ID.parse(keyId))
        }

    @PostMapping("/credentials/{id}/discard")
    fun discard(@PathVariable id: String, @RequestParam keyId: String, redirect: RedirectAttributes): ModelAndView =
        change(id, redirect, { "The pending key of credential ${it.name} was discarded." }) { caller, credential ->
            credentials.discard(caller, credential, KEY_ID.parse(keyId))
        }

    @PostMapping("/credentials/{id}/replace")
    fun replace(@PathVariable id: String, redirect: RedirectAttributes): ModelAndView = change(
        id,
        redirect,
        { "Credential ${it.name} has a new active key. Register its public key." },
    ) { caller, credential ->
        credentials.replace(caller, credential)
    }

    @PostMapping("/credentials/{id}/disable")
    fun disable(@PathVariable id: String, redirect: RedirectAttributes): ModelAndView =
        change(id, redirect, { "Credential ${it.name} is disabled." }) { caller, credential ->
            credentials.disable(caller, credential)
        }

    @PostMapping("/credentials/{id}/delete")
    fun delete(@PathVariable id: String, redirect: RedirectAttributes): ModelAndView {
        val credential = CREDENTIAL_ID.parse(id)
        return submit(redirect, { credentialPage(credential) }) {
            val deleted = credentials.delete(currentCaller.get(), credential)
            Redirect(credentialsUrl(deleted.namespaceId), "Credential ${deleted.name} deleted.")
        }
    }

    /** Runs a key or status change from a form on the credential page. A failure shows the credential page again. */
    private fun change(
        id: String,
        redirect: RedirectAttributes,
        notice: (Credential) -> String,
        operation: (Caller, CredentialId) -> Credential,
    ): ModelAndView {
        val credential = CREDENTIAL_ID.parse(id)
        return submit(redirect, { credentialPage(credential) }) {
            Redirect(credentialUrl(credential), notice(operation(currentCaller.get(), credential)))
        }
    }

    private fun listPage(namespace: NamespaceId, name: String?, gitInstance: String?): ModelAndView {
        val caller = currentCaller.get()
        val owned = credentials.list(caller, namespace)
        return ModelAndView("credential/web/credentials")
            .addObject("namespace", owned.namespace)
            .addObject("namespaceReadable", access.allows(caller, Action.READ, namespace))
            .addObject("credentials", owned.credentials)
            .addObject("name", name)
            .addObject("gitInstance", gitInstance)
    }

    private fun credentialPage(id: CredentialId): ModelAndView {
        val caller = currentCaller.get()
        val credential = credentials.get(caller, id)
        return ModelAndView("credential/web/credential")
            .addObject("credential", credential)
            .addObject("namespaceReadable", access.allows(caller, Action.READ, credential.namespaceId))
    }

    private fun credentialUrl(id: CredentialId) = "/credentials/${id.toApiId()}"

    private fun credentialsUrl(namespace: NamespaceId) = "/namespaces/${namespace.toApiId()}/credentials"
}

/** The master-key page for super admins (design 12.3): the key counts for each version and the re-encryption. */
@Controller
class CryptoPages(private val currentCaller: CurrentCaller, private val masterKeys: MasterKeyService) {
    @GetMapping("/crypto")
    fun crypto(): ModelAndView = cryptoPage()

    @PostMapping("/crypto/reencrypt")
    fun reencrypt(redirect: RedirectAttributes): ModelAndView = submit(redirect, ::cryptoPage) {
        val run = masterKeys.reencrypt(currentCaller.get())
        Redirect("/crypto", "The re-encryption moved ${run.reencrypted.values.sum()} keys to the active version.")
    }

    private fun cryptoPage() =
        ModelAndView("credential/web/crypto").addObject("status", masterKeys.status(currentCaller.get()))
}
