create table namespace (
    -- uuidv7 is time-ordered, so inserts stay at the right edge of the primary-key index.
    id uuid primary key default uuidv7(),
    parent_id uuid references namespace (id),
    slug varchar(100) not null,
    -- NULLS NOT DISTINCT makes root namespaces (parent_id is null) siblings of each other.
    constraint namespace_sibling_slug_unique unique nulls not distinct (parent_id, slug)
);

-- One row per (ancestor, descendant) pair, including each namespace with itself at depth 0.
create table namespace_closure (
    ancestor_id uuid not null references namespace (id),
    descendant_id uuid not null references namespace (id),
    depth integer not null check (depth >= 0),
    primary key (ancestor_id, descendant_id)
);

create index namespace_closure_descendant_id_idx on namespace_closure (descendant_id);
