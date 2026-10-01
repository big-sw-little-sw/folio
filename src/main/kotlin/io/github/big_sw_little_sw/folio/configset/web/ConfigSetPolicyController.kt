package io.github.big_sw_little_sw.folio.configset.web

import io.github.big_sw_little_sw.folio.configset.ConfigSetId
import io.github.big_sw_little_sw.folio.configset.ConfigSetPolicyService
import io.github.big_sw_little_sw.folio.namespace.NamespaceId
import io.github.big_sw_little_sw.folio.namespace.toApiId
import io.github.big_sw_little_sw.folio.policy.Action
import io.github.big_sw_little_sw.folio.policy.DecisionResponse
import io.github.big_sw_little_sw.folio.policy.ExplainRequest
import io.github.big_sw_little_sw.folio.policy.PutRuleRequest
import io.github.big_sw_little_sw.folio.policy.ResourceRef
import io.github.big_sw_little_sw.folio.policy.RuleResponse
import io.github.big_sw_little_sw.folio.policy.toResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin/configsets")
class ConfigSetPolicyController(
    private val policies: ConfigSetPolicyService,
) {
    /** Rules on the ConfigSet itself, not inherited ones. */
    @GetMapping("/{id}/rules")
    fun rules(
        @PathVariable id: String,
    ): List<RuleResponse> = policies.rules(id.toConfigSetId()).map { it.toResponse() }

    /** Adds the rule for [action], replacing the ConfigSet's existing rule for it. */
    @PutMapping("/{id}/rules/{action}")
    fun putRule(
        @PathVariable id: String,
        @PathVariable action: Action,
        @RequestBody request: PutRuleRequest,
    ): RuleResponse = policies.putRule(id.toConfigSetId(), request.toRule(action)).toResponse()

    @DeleteMapping("/{id}/rules/{action}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteRule(
        @PathVariable id: String,
        @PathVariable action: Action,
    ) {
        policies.deleteRule(id.toConfigSetId(), action)
    }

    @PostMapping("/{id}:explain")
    fun explain(
        @PathVariable id: String,
        @RequestBody request: ExplainRequest,
    ): DecisionResponse =
        policies
            .explain(id.toConfigSetId(), request.toPrincipal(), request.action)
            .toResponse(request.action, id, ::apiId)

    private fun apiId(source: ResourceRef) =
        when (source) {
            is ResourceRef.NamespaceRef -> NamespaceId(source.id).toApiId()
            is ResourceRef.ConfigSetRef -> ConfigSetId(source.id).toApiId()
        }
}
