-- Audit records. There are no foreign keys, because records stay after their resources are deleted.
create table audit_event (
    id uuid primary key default uuidv7(),
    -- The database clock at the start of the transaction of the operation.
    occurred_at timestamptz not null default now(),
    actor_type text not null check (actor_type in ('AUTHENTICATED', 'ANONYMOUS', 'SYSTEM')),
    actor_subject text,
    actor_application_id text,
    actor_super_admin boolean not null,
    operation text not null,
    resource_type text not null,
    resource_id uuid,
    path text,
    previous_path text,
    details jsonb not null,
    constraint audit_event_actor check ((actor_type = 'AUTHENTICATED') = (actor_subject is not null)),
    constraint audit_event_super_admin check (actor_type = 'AUTHENTICATED' or not actor_super_admin)
);

create index audit_event_resource on audit_event (resource_id, occurred_at);

-- Retention deletes records by age only. The index above cannot find them.
create index audit_event_occurred_at on audit_event (occurred_at);
