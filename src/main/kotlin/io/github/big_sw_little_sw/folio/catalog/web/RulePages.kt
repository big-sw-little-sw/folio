package io.github.big_sw_little_sw.folio.catalog.web

import io.github.big_sw_little_sw.folio.catalog.Action
import io.github.big_sw_little_sw.folio.catalog.ResourceId
import io.github.big_sw_little_sw.folio.catalog.internal.Rule
import io.github.big_sw_little_sw.folio.catalog.internal.RuleService
import io.github.big_sw_little_sw.folio.catalog.internal.Subject
import io.github.big_sw_little_sw.folio.identity.Caller
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
 * The own rule of a resource for one action. [inherits] is true if the resource has no rule for the action. [text] is
 * what the text area shows: the subjects of the rule, one on each line, or the text of a form that failed.
 */
data class RuleRow(val action: Action, val inherits: Boolean, val text: String)

/** The values of the explain form. A blank subject is an anonymous caller. */
data class ExplainForm(val action: Action?, val subject: String?, val groups: String?, val applicationId: String?) {
    fun caller(): Caller {
        val subject = subject?.trim().orEmpty()
        if (subject.isEmpty()) return Caller.Anonymous
        return Caller.Authenticated(subject, lines(groups).toSet(), applicationId?.trim()?.takeIf { it.isNotEmpty() })
    }
}

/** The rules page of a Namespace or ConfigSet, with its explain form. All of it needs `GRANT`. */
@Controller
@RequestMapping("/{collection:namespaces|configsets}/{id}")
class RulePages(
    private val currentCaller: CurrentCaller,
    private val rules: RuleService,
    private val views: ResourceViews,
) {
    /** With an [action], the page also explains the decision for the caller of the explain form. */
    @GetMapping("/rules")
    fun rules(
        @PathVariable collection: String,
        @PathVariable id: String,
        @RequestParam(required = false) action: Action?,
        @RequestParam(required = false) subject: String?,
        @RequestParam(required = false) groups: String?,
        @RequestParam(required = false) applicationId: String?,
    ): ModelAndView {
        val resource = resourceOf(collection, id)
        val form = ExplainForm(action, subject, groups, applicationId)
        val explanation = action?.let { rules.explain(currentCaller.get(), resource, it, form.caller()).toResponse() }
        return page(resource, form).addObject("explanation", explanation)
    }

    /** Replaces the rule for the action with the subjects of the text area, one on each line. */
    @PostMapping("/rules/{action}")
    fun put(
        @PathVariable collection: String,
        @PathVariable id: String,
        @PathVariable action: Action,
        @RequestParam(required = false) subjects: String?,
        redirect: RedirectAttributes,
    ): ModelAndView {
        val resource = resourceOf(collection, id)
        val form = { page(resource, ExplainForm(null, null, null, null), submitted = action to subjects.orEmpty()) }
        return submit(redirect, form) {
            rules.put(currentCaller.get(), resource, Rule(action, lines(subjects).map(Subject::parse).toSet()))
            "${ResourceView.pageOf(resource)}/rules" to "The $action rule is saved."
        }
    }

    @PostMapping("/rules/{action}/delete")
    fun delete(
        @PathVariable collection: String,
        @PathVariable id: String,
        @PathVariable action: Action,
        redirect: RedirectAttributes,
    ): ModelAndView {
        val resource = resourceOf(collection, id)
        return submit(redirect, { page(resource, ExplainForm(null, null, null, null)) }) {
            rules.delete(currentCaller.get(), resource, action)
            "${ResourceView.pageOf(resource)}/rules" to "The $action rule is deleted. $action inherits again."
        }
    }

    /** [submitted] is the action and the text of a rule form that failed. Its text area shows that text again. */
    private fun page(resource: ResourceId, form: ExplainForm, submitted: Pair<Action, String>? = null): ModelAndView {
        val own = rules.rules(currentCaller.get(), resource).associateBy { it.action }
        val rows =
            Action.entries.map { action ->
                val stored = own[action]?.subjects?.joinToString("\n")
                val text = submitted?.takeIf { it.first == action }?.second ?: stored.orEmpty()
                RuleRow(action, inherits = stored == null, text = text)
            }
        return ModelAndView("catalog/web/rules")
            .addObject("resource", views.of(resource, Action.GRANT))
            .addObject("rules", rows)
            .addObject("explain", form)
    }
}
