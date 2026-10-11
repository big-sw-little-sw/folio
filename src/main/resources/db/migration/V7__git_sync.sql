-- These columns keep the sync state and the lease of each Git source (design 10.6). Sync in the git module writes them.
-- The last published commit is not a column: the newest git_revision row is the last published commit (design 16).
alter table git_source
    -- The branch tip that the last successful fetch got. It differs from the last published commit when the publish
    -- step refused the tip, for example because the root path is missing.
    add column last_seen_commit text check (last_seen_commit ~ '^[0-9a-f]{40}$'),
    -- Times come from the database clock, so instances with different clocks agree on due times and leases.
    add column last_attempt_at timestamptz,
    add column last_success_at timestamptz,
    -- The failure code of the last attempt, or null after a success. The code gives the fixed summary.
    add column failure_code text check (failure_code ~ '^[A-Z]+(_[A-Z]+)*$'),
    add column consecutive_failures integer not null default 0 check (consecutive_failures >= 0),
    -- A new Git source is due at creation, so sync starts immediately (design 10.3). During a lease, next_due_at is the
    -- lease end, unless a manual sync moved it earlier. Thus the ConfigSets of an instance that stops become due again
    -- when their leases end.
    add column next_due_at timestamptz not null default now(),
    -- The instance that holds the lease: a random ID for each start of the process. The owner and the lease end
    -- together identify one claim.
    add column lease_owner uuid,
    add column lease_end timestamptz,
    add constraint git_source_failure check ((failure_code is null) = (consecutive_failures = 0)),
    add constraint git_source_lease check ((lease_owner is null) = (lease_end is null));

-- Each poll finds the due Git sources through this index.
create index git_source_next_due on git_source (next_due_at);
