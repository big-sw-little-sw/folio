package io.github.big_sw_little_sw.folio.consumption

import io.github.big_sw_little_sw.folio.configset.ConfigSetId

/** Expected failures of the consumption API (ADR 0006). */
sealed class ConsumptionException(
    message: String,
) : RuntimeException(message)

/** A revision selector that is neither `latest` nor a commit ID of 40 lowercase hex characters. */
class InvalidRevisionException(
    val value: String,
) : ConsumptionException("Invalid revision '$value'")

/** `latest` of a ConfigSet that has never synced: there is nothing to serve yet. */
class NotYetSyncedException(
    val id: ConfigSetId,
) : ConsumptionException("The ConfigSet has not synced yet")

/** A commit that was never the ConfigSet's synced revision; consumers see only revisions Folio synced (ADR 0036). */
class UnknownRevisionException(
    val commitId: String,
) : ConsumptionException("Revision '$commitId' not found")

/**
 * A synced revision that this instance's cache lacks even after fetching the branch, for example because it was
 * force-pushed away (ADR 0036).
 */
class RevisionNotAvailableException(
    val commitId: String,
) : ConsumptionException("Revision '$commitId' is no longer available")
