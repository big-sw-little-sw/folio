-- Audit records (design 24.2, ADR 0038), each written in the transaction of the operation it describes. No foreign keys:
-- records outlive the resources they name. Details never hold key material, secrets, tokens, transport output or
-- file contents.
create table audit_event (
    id uuid primary key default uuidv7(),
    -- The database clock at the start of the operation's transaction.
    occurred_at timestamptz not null,
    -- AUTHENTICATED: a caller with a token. SYSTEM: Folio itself, such as sync recording an outcome.
    actor_type varchar(20) not null check (actor_type in ('AUTHENTICATED', 'ANONYMOUS', 'SYSTEM')),
    actor_subject varchar(500),
    actor_application_id varchar(500),
    -- The caller is a configured super admin, so every authorization decision came from that status (ADR 0028).
    actor_super_admin boolean not null,
    action varchar(50) not null,
    resource_type varchar(30) not null
        check (resource_type in ('NAMESPACE', 'CONFIG_SET', 'CREDENTIAL', 'MASTER_KEY_RING')),
    -- Null for the master-key ring, which has no ID.
    resource_id uuid,
    -- The namespace or ConfigSet path at the time; null for credentials and the master-key ring.
    resource_path text,
    details jsonb not null,
    constraint audit_event_actor_check check ((actor_type = 'AUTHENTICATED') = (actor_subject is not null))
);

create index audit_event_resource on audit_event (resource_id, occurred_at);
