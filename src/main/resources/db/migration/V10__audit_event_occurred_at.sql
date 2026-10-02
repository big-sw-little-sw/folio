-- Pruning deletes records by age alone (ADR 0041); the (resource_id, occurred_at) index cannot serve that.
create index audit_event_occurred_at on audit_event (occurred_at);
