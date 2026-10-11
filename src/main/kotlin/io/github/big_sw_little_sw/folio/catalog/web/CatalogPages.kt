package io.github.big_sw_little_sw.folio.catalog.web

import io.github.big_sw_little_sw.folio.catalog.Access
import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.CONFIG_SET_ID
import io.github.big_sw_little_sw.folio.catalog.NAMESPACE_ID
import io.github.big_sw_little_sw.folio.catalog.ResourceId
import io.github.big_sw_little_sw.folio.catalog.Slug
import io.github.big_sw_little_sw.folio.catalog.internal.ConfigSetService
import io.github.big_sw_little_sw.folio.catalog.internal.NamespaceService
import io.github.big_sw_little_sw.folio.catalog.internal.StartService
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
 * The operations that the caller can do on a resource. The pages show only these operations. Hiding is for convenience,
 * not for security: the services authorize each operation (design 12.3).
 */
data class Operations(val manage: Boolean, val grant: Boolean)

/**
 * The tree browser (design 12.3). It has the start page, the Namespace and ConfigSet pages and the form that creates a
 * Namespace. The start page shows the top-most resources that the caller can READ. The pages of each Source create the
 * ConfigSets of that Source.
 */
@Controller
class CatalogPages(
    private val currentCaller: CurrentCaller,
    private val namespaces: NamespaceService,
    private val configSets: ConfigSetService,
    private val access: Access,
    private val start: StartService,
    private val views: ResourceViews,
) {
    @GetMapping("/")
    fun home(): ModelAndView {
        val caller = currentCaller.get()
        return ModelAndView("catalog/web/home")
            .addObject("items", start.entries(caller).map(StartItem::of))
            .addObject("superAdmin", caller.superAdmin)
    }

    @GetMapping("/$NAMESPACES/{id}")
    fun namespace(@PathVariable id: String): ModelAndView {
        val caller = currentCaller.get()
        val details = namespaces.get(caller, NAMESPACE_ID.parse(id))
        return ModelAndView("catalog/web/namespace")
            .addObject("resource", ResourceView.of(details.ancestry, details.namespace.description, details.readable))
            .addObject("details", details)
            .addObject("operations", operations(caller, details.namespace.id))
    }

    @GetMapping("/$CONFIG_SETS/{id}")
    fun configSet(@PathVariable id: String): ModelAndView {
        val caller = currentCaller.get()
        val details = configSets.get(caller, CONFIG_SET_ID.parse(id))
        return ModelAndView("catalog/web/configSet")
            .addObject("resource", ResourceView.of(details.ancestry, details.configSet.description, details.readable))
            .addObject("configSet", details.configSet)
            .addObject("operations", operations(caller, details.configSet.id))
    }

    /** Opens the ConfigSet at a path. The responses are the same as for the resolve route of the API. */
    @GetMapping("/$CONFIG_SETS", params = ["path"])
    fun resolve(@RequestParam path: String): String =
        "redirect:${ResourceView.pageOf(configSets.resolve(currentCaller.get(), path).id)}"

    @GetMapping("/$NAMESPACES/new")
    fun newNamespace(@RequestParam(required = false) parentId: String?): ModelAndView = createForm(parentId, null, null)

    @PostMapping("/$NAMESPACES")
    fun create(
        @RequestParam(required = false) parentId: String?,
        @RequestParam slug: String,
        @RequestParam(required = false) description: String?,
        redirect: RedirectAttributes,
    ): ModelAndView = submit(redirect, { createForm(parentId, slug, description) }) {
        val parent = parentId?.takeIf { it.isNotEmpty() }?.let(NAMESPACE_ID::parse)
        val namespace = namespaces.create(currentCaller.get(), parent, Slug(slug), description)
        Redirect(ResourceView.pageOf(namespace.id), "Namespace ${namespace.path} created.")
    }

    /**
     * The form needs `MANAGE` on the parent, as the create does (design 7.3). At the top level, only a super admin has
     * that permission. A failed post shows this form again, and this check then fails too. Thus a post without
     * permission shows the error page, as on the other forms.
     */
    private fun createForm(parentId: String?, slug: String?, description: String?): ModelAndView {
        val parent = parentId?.takeIf { it.isNotEmpty() }?.let { views.of(NAMESPACE_ID.parse(it), Action.MANAGE) }
        if (parent == null) access.requireSuperAdmin(currentCaller.get())
        return ModelAndView("catalog/web/createNamespace")
            .addObject("parent", parent)
            .addObject("slug", slug)
            .addObject("description", description)
    }

    private fun operations(caller: Caller, resource: ResourceId) = Operations(
        manage = access.allows(caller, Action.MANAGE, resource),
        grant = access.allows(caller, Action.GRANT, resource),
    )
}
