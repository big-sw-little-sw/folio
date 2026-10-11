-- These tables keep the Git sources and their published revisions (design 10, 16). The git module owns them.

create table git_source (
    -- A delete of a ConfigSet also deletes its Source definition and its revision history (design 5.4).
    config_set_id uuid primary key references config_set (id) on delete cascade,
    -- The foreign key does not cascade. Thus a credential delete fails while a Git source uses the credential, and a
    -- ConfigSet never loses its credential (design 11.1). The credential module changes the violation to
    -- CREDENTIAL_IN_USE.
    credential_id uuid not null references credential (id),
    -- Segments of letters, digits, '.', '_' and '-', joined by '/'. Thus the path cannot contain a scheme, a user or a
    -- host (design 10.2). The service also rejects '.' and '..' segments.
    repository_path text not null check (
        char_length(repository_path) <= 500 and repository_path ~ '^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$'
    ),
    -- The service validates the branch as a Git ref name. This constraint enforces only the length.
    branch text not null check (char_length(branch) between 1 and 250),
    -- The empty root path is the root of the repository.
    root_path text not null check (char_length(root_path) <= 1000)
);

-- Each credential delete examines this foreign key. Without the index, each delete scans git_source.
create index git_source_credential on git_source (credential_id);

-- Each row is a published revision. Sync publishes a commit again when the branch tip returns to it. The row then gets
-- a new published_at, so the newest row is always latest.
create table git_revision (
    config_set_id uuid not null references git_source (config_set_id) on delete cascade,
    commit_id text not null check (commit_id ~ '^[0-9a-f]{40}$'),
    published_at timestamptz not null default now(),
    primary key (config_set_id, commit_id)
);

create index git_revision_newest on git_revision (config_set_id, published_at desc);
