package io.github.big_sw_little_sw.folio.credential

import java.util.UUID

/** A name no other credential has: test classes share one database, and not all of them clear credentials. */
fun uniqueCredentialName() = CredentialName("test-${UUID.randomUUID()}")
