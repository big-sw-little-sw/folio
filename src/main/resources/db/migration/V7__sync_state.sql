-- Sync state per ConfigSet (design 14.4, ADR 0032), created with the ConfigSet and deleted with it.
-- Revisions are commit IDs. Error codes and summaries are the source module's fixed texts, never transport output.
create table sync_state (
    config_set_id uuid primary key references config_set (id) on delete cascade,
    -- The branch tip at the most recent successful fetch, and the revision served as latest. Equal in v1.
    last_seen_revision char(40) check (last_seen_revision ~ '^[0-9a-f]{40}$'),
    last_synced_revision char(40) check (last_synced_revision ~ '^[0-9a-f]{40}$'),
    last_attempt_at timestamptz,
    last_success_at timestamptz,
    last_error_code varchar(50),
    last_error_summary varchar(500),
    consecutive_failures integer not null default 0 check (consecutive_failures >= 0),
    -- While leased, next_due_at is lease_until, unless a manual request moved it earlier: a crashed instance's
    -- ConfigSets become due when their leases expire.
    next_due_at timestamptz not null,
    -- The instance holding the lease, a random ID per process start.
    lease_owner uuid,
    lease_until timestamptz,
    constraint sync_state_error_check check ((last_error_code is null) = (last_error_summary is null)),
    constraint sync_state_lease_check check ((lease_owner is null) = (lease_until is null))
);

create index sync_state_next_due_at on sync_state (next_due_at);

insert into sync_state (config_set_id, next_due_at)
select id, now() from config_set;
