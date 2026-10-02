package io.github.big_sw_little_sw.folio.policy.internal

import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Fails startup if the old key `folio.bootstrap.admins` is set. It has no alias, and silently ignoring it would leave a
 * deployment without super admins (ADR 0028).
 */
@Component
class RenamedSuperAdminsCheck(
    environment: Environment,
) {
    init {
        // Binding finds the key in every form: a list in YAML, indexed or comma-separated properties.
        val oldKeySet = Binder.get(environment).bind(OLD_KEY, Bindable.listOf(String::class.java)).isBound
        check(!oldKeySet) { "$OLD_KEY was renamed to folio.super-admins (ADR 0028); rename it in the configuration" }
    }

    private companion object {
        const val OLD_KEY = "folio.bootstrap.admins"
    }
}
