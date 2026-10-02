-- Every revision that became a ConfigSet's synced revision (ADR 0036). Consumers may read only these revisions.
-- Written in the statement that records a new synced revision; deleted with the ConfigSet's sync state.
create table synced_revision (
    config_set_id uuid not null references sync_state (config_set_id) on delete cascade,
    commit_id char(40) not null check (commit_id ~ '^[0-9a-f]{40}$'),
    -- When the commit last became the synced revision; a branch reset to an earlier commit moves it to the top.
    synced_at timestamptz not null,
    primary key (config_set_id, commit_id)
);

create index synced_revision_newest on synced_revision (config_set_id, synced_at desc);

insert into synced_revision (config_set_id, commit_id, synced_at)
select config_set_id, last_synced_revision, coalesce(last_success_at, now())
from sync_state
where last_synced_revision is not null;
