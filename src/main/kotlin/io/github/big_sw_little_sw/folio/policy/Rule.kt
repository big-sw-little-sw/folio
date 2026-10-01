package io.github.big_sw_little_sw.folio.policy

/** A grant of [action] to [subjects] on one namespace or ConfigSet, which has at most one rule per action. */
data class Rule(
    val action: Action,
    val subjects: Set<Subject>,
)
