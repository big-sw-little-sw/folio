package io.github.big_sw_little_sw.folio.catalog.web

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.ConfigSetId
import io.github.big_sw_little_sw.folio.catalog.NAMESPACE_ID
import io.github.big_sw_little_sw.folio.catalog.NamespaceId
import io.github.big_sw_little_sw.folio.catalog.ResourceId
import io.github.big_sw_little_sw.folio.catalog.Slug
import io.github.big_sw_little_sw.folio.catalog.internal.ConfigSetService
import io.github.big_sw_little_sw.folio.catalog.internal.DescriptionChange
import io.github.big_sw_little_sw.folio.catalog.internal.Edit
import io.github.big_sw_little_sw.folio.catalog.internal.NamespaceService
import io.github.big_sw_little_sw.folio.catalog.internal.ResourceService
import io.github.big_sw_little_sw.folio.identity.CurrentCaller
import io.github.big_sw_little_sw.folio.web.Redirect
import io.github.big_sw_little_sw.folio.web.submit
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * The edit, move and delete pages of Namespaces and ConfigSets. Each form needs `MANAGE`, as its operation does. The
 * services authorize the operations.
 */
@Controller
@RequestMapping("/{collection:namespaces|configsets}/{id}")
class ResourcePages(
    private val currentCaller: CurrentCaller,
    private val namespaces: NamespaceService,
    private val configSets: ConfigSetService,
    private val resources: ResourceService,
) {
    @GetMapping("/edit")
    fun editForm(@PathVariable collection: String, @PathVariable id: String): ModelAndView {
        val resource = view(resourceOf(collection, id))
        return editForm(resource, resource.slug, resource.description)
    }

    @PostMapping("/edit")
    fun edit(
        @PathVariable collection: String,
        @PathVariable id: String,
        @RequestParam slug: String,
        @RequestParam(required = false) description: String?,
        redirect: RedirectAttributes,
    ): ModelAndView {
        val resource = resourceOf(collection, id)
        return submit(redirect, { editForm(view(resource), slug, description) }) {
            val edit = Edit(Slug(slug), DescriptionChange.Replace(description))
            val path =
                when (resource) {
                    is NamespaceId -> namespaces.edit(currentCaller.get(), resource, edit).path
                    is ConfigSetId -> configSets.edit(currentCaller.get(), resource, edit).path
                }
            Redirect(ResourceView.pageOf(resource), "${ResourceView.kindOf(resource)} $path saved.")
        }
    }

    @GetMapping("/move")
    fun moveForm(@PathVariable collection: String, @PathVariable id: String): ModelAndView =
        moveForm(view(resourceOf(collection, id)), null)

    /** [target] is the API ID of the new parent Namespace. An empty [target] moves a Namespace to the top level. */
    @PostMapping("/move")
    fun move(
        @PathVariable collection: String,
        @PathVariable id: String,
        @RequestParam(required = false) target: String?,
        redirect: RedirectAttributes,
    ): ModelAndView {
        val resource = resourceOf(collection, id)
        return submit(redirect, { moveForm(view(resource), target) }) {
            val text = target.orEmpty().trim()
            val path =
                when (resource) {
                    is NamespaceId -> {
                        val parent = text.takeIf { it.isNotEmpty() }?.let(NAMESPACE_ID::parse)
                        namespaces.move(currentCaller.get(), resource, parent).path
                    }

                    is ConfigSetId -> {
                        configSets.move(currentCaller.get(), resource, NAMESPACE_ID.parse(text)).path
                    }
                }
            Redirect(ResourceView.pageOf(resource), "${ResourceView.kindOf(resource)} moved to $path.")
        }
    }

    @GetMapping("/delete")
    fun deleteForm(@PathVariable collection: String, @PathVariable id: String): ModelAndView =
        deleteForm(view(resourceOf(collection, id)))

    @PostMapping("/delete")
    fun delete(
        @PathVariable collection: String,
        @PathVariable id: String,
        redirect: RedirectAttributes,
    ): ModelAndView {
        val resource = resourceOf(collection, id)
        val view = view(resource)
        return submit(redirect, { deleteForm(view) }) {
            when (resource) {
                is NamespaceId -> namespaces.delete(currentCaller.get(), resource)
                is ConfigSetId -> configSets.delete(currentCaller.get(), resource)
            }
            Redirect(view.parentUrl, "${view.kind} ${view.path} deleted.")
        }
    }

    private fun view(resource: ResourceId) =
        ResourceView.of(resources.summary(currentCaller.get(), resource, Action.MANAGE))

    private fun editForm(resource: ResourceView, slug: String?, description: String?) = ModelAndView("catalog/web/edit")
        .addObject("resource", resource)
        .addObject("slug", slug)
        .addObject("description", description)

    private fun moveForm(resource: ResourceView, target: String?) =
        ModelAndView("catalog/web/move").addObject("resource", resource).addObject("target", target)

    private fun deleteForm(resource: ResourceView) = ModelAndView("catalog/web/delete").addObject("resource", resource)
}
