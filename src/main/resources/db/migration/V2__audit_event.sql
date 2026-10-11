-- Audit records (design 14). Each module writes them in the transaction of its operation. There are no foreign keys,
-- because records stay after their resources are deleted. Records never contain groups, keys, secrets, tokens,
-- transport output or file content.
create table audit_event (
    id uuid primary key default uuidv7(),
    -- The database clock at the start of the transaction of the operation.
    occurred_at timestamptz not null default now(),
    actor_type text not null check (actor_type in ('AUTHENTICATED', 'ANONYMOUS', 'SYSTEM')),
    actor_subject text,
    actor_application_id text,
    actor_super_admin boolean not null,
    -- The audited operation, for example NAMESPACE_RENAMED.
    operation text not null,
    resource_type text not null,
    -- Null only for a resource without an ID, for example the master-key ring.
    resource_id uuid,
    -- The path after the operation, or before it for a delete.
    path text,
    -- The path before a rename or a move.
    previous_path text,
    details jsonb not null,
    constraint audit_event_actor check ((actor_type = 'AUTHENTICATED') = (actor_subject is not null)),
    constraint audit_event_super_admin check (actor_type = 'AUTHENTICATED' or not actor_super_admin)
);

create index audit_event_resource on audit_event (resource_id, occurred_at);

-- Retention deletes records by age only. The index above cannot find them.
create index audit_event_occurred_at on audit_event (occurred_at);
