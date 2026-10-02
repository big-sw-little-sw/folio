package io.github.big_sw_little_sw.folio.audit

import io.github.big_sw_little_sw.folio.audit.internal.Actor
import io.github.big_sw_little_sw.folio.audit.internal.AuditEntry
import io.github.big_sw_little_sw.folio.audit.internal.AuditRepository
import io.github.big_sw_little_sw.folio.audit.internal.toAuditEntry
import io.github.big_sw_little_sw.folio.configset.ConfigSetEvent
import io.github.big_sw_little_sw.folio.credential.CredentialChanged
import io.github.big_sw_little_sw.folio.credential.MasterKeysReencrypted
import io.github.big_sw_little_sw.folio.namespace.NamespaceEvent
import io.github.big_sw_little_sw.folio.policy.PolicyService
import io.github.big_sw_little_sw.folio.security.ApplicationPrincipal
import io.github.big_sw_little_sw.folio.security.CurrentPrincipal
import io.github.big_sw_little_sw.folio.sync.SyncEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Records audited operations from their modules' events (ADR 0038). Each listener runs synchronously in the operation's
 * transaction, hence MANDATORY: the record commits with the operation, and a failed insert rolls the operation back.
 * Nothing depends on this module.
 */
@Service
class AuditRecorder(
    private val records: AuditRepository,
    private val currentPrincipal: CurrentPrincipal,
    private val policy: PolicyService,
) {
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun onNamespace(event: NamespaceEvent) {
        record(event.toAuditEntry())
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun onConfigSet(event: ConfigSetEvent) {
        record(event.toAuditEntry())
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun onCredential(event: CredentialChanged) {
        record(event.toAuditEntry())
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun onMasterKeys(event: MasterKeysReencrypted) {
        record(event.toAuditEntry())
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun onSync(event: SyncEvent) {
        record(event.toAuditEntry())
    }

    private fun record(entry: AuditEntry) {
        val actor =
            if (entry.action.bySystem) {
                Actor.System
            } else {
                when (val principal = currentPrincipal.get()) {
                    ApplicationPrincipal.Anonymous -> {
                        Actor.Anonymous
                    }

                    is ApplicationPrincipal.Authenticated -> {
                        Actor.Caller(principal.subject, principal.applicationId, policy.isSuperAdmin(principal))
                    }
                }
            }
        records.insert(actor, entry)
    }
}
