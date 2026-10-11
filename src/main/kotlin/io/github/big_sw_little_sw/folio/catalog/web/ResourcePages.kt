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
import io.github.big_sw_little_sw.folio.identity.CurrentCaller
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
 * The rename and describe, move and delete pages of Namespaces and ConfigSets. The forms need `MANAGE`, as their
 * operations do (design 7.3). The services authorize the operations.
 */
@Controller
@RequestMapping("/{collection:namespaces|configsets}/{id}")
class ResourcePages(
    private val currentCaller: CurrentCaller,
    private val namespaces: NamespaceService,
    private val configSets: ConfigSetService,
    private val views: ResourceViews,
) {
    @GetMapping("/edit")
    fun editForm(@PathVariable collection: String, @PathVariable id: String): ModelAndView {
        val resource = views.of(resourceOf(collection, id), Action.MANAGE)
        return editForm(resource, resource.slug, resource.description)
    }

    /** An empty description clears it. */
    @PostMapping("/edit")
    fun edit(
        @PathVariable collection: String,
        @PathVariable id: String,
        @RequestParam slug: String,
        @RequestParam(required = false) description: String?,
        redirect: RedirectAttributes,
    ): ModelAndView {
        val resource = resourceOf(collection, id)
        return submit(redirect, { editForm(views.of(resource, Action.MANAGE), slug, description) }) {
            val edit = Edit(Slug(slug), DescriptionChange.Replace(description))
            val path =
                when (resource) {
                    is NamespaceId -> namespaces.edit(currentCaller.get(), resource, edit).path
                    is ConfigSetId -> configSets.edit(currentCaller.get(), resource, edit).path
                }
            ResourceView.pageOf(resource) to "${ResourceView.kindOf(resource)} $path saved."
        }
    }

    @GetMapping("/move")
    fun moveForm(@PathVariable collection: String, @PathVariable id: String): ModelAndView =
        moveForm(views.of(resourceOf(collection, id), Action.MANAGE), null)

    /** [target] is the API ID of the new parent Namespace. Empty moves a Namespace to the top level. */
    @PostMapping("/move")
    fun move(
        @PathVariable collection: String,
        @PathVariable id: String,
        @RequestParam(required = false) target: String?,
        redirect: RedirectAttributes,
    ): ModelAndView {
        val resource = resourceOf(collection, id)
        return submit(redirect, { moveForm(views.of(resource, Action.MANAGE), target) }) {
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
            ResourceView.pageOf(resource) to "${ResourceView.kindOf(resource)} moved to $path."
        }
    }

    @GetMapping("/delete")
    fun deleteForm(@PathVariable collection: String, @PathVariable id: String): ModelAndView =
        deleteForm(views.of(resourceOf(collection, id), Action.MANAGE))

    @PostMapping("/delete")
    fun delete(
        @PathVariable collection: String,
        @PathVariable id: String,
        redirect: RedirectAttributes,
    ): ModelAndView {
        val resource = resourceOf(collection, id)
        val view = views.of(resource, Action.MANAGE)
        return submit(redirect, { deleteForm(view) }) {
            when (resource) {
                is NamespaceId -> namespaces.delete(currentCaller.get(), resource)
                is ConfigSetId -> configSets.delete(currentCaller.get(), resource)
            }
            view.parentUrl to "${view.kind} ${view.path} deleted."
        }
    }

    private fun editForm(resource: ResourceView, slug: String?, description: String?) = ModelAndView("catalog/web/edit")
        .addObject("resource", resource)
        .addObject("slug", slug)
        .addObject("description", description)

    private fun moveForm(resource: ResourceView, target: String?) =
        ModelAndView("catalog/web/move").addObject("resource", resource).addObject("target", target)

    private fun deleteForm(resource: ResourceView) = ModelAndView("catalog/web/delete").addObject("resource", resource)
}
