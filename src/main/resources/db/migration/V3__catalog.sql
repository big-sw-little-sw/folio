-- The catalog (design 5, 7, 16): the tree of Namespaces and ConfigSets, and the rules on them. Each catalog write
-- takes the tree lock first (design 8.1), so these constraints are the last line of defence, not the only one.

create table namespace (
    id uuid primary key default uuidv7(),
    -- Null for a top-level Namespace. No cascade: Folio deletes only an empty Namespace (design 5.4).
    parent_id uuid references namespace (id),
    slug text not null check (char_length(slug) <= 100 and slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    description text check (char_length(description) <= 1000),
    -- NULLS NOT DISTINCT makes the top-level Namespaces siblings of each other.
    constraint namespace_slug_unique unique nulls not distinct (parent_id, slug)
);

create table config_set (
    id uuid primary key default uuidv7(),
    -- No cascade: a delete of a Namespace never removes a ConfigSet that consumers use.
    namespace_id uuid not null references namespace (id),
    slug text not null check (char_length(slug) <= 100 and slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    description text check (char_length(description) <= 1000),
    -- The name of the SourceType enum. It never changes after creation (design 10.2).
    source_type text not null,
    constraint config_set_slug_unique unique (namespace_id, slug)
);

-- One rule for each target and action. A rule is deleted with its target.
create table rule (
    id uuid primary key default uuidv7(),
    namespace_id uuid references namespace (id) on delete cascade,
    config_set_id uuid references config_set (id) on delete cascade,
    action text not null check (action in ('READ', 'MANAGE', 'GRANT')),
    -- Subjects in their text form, for example group:<id>. A rule without subjects would be a deny rule.
    subjects text[] not null check (cardinality(subjects) >= 1),
    constraint rule_one_target check (num_nonnulls(namespace_id, config_set_id) = 1),
    constraint rule_namespace_action_unique unique (namespace_id, action),
    constraint rule_config_set_action_unique unique (config_set_id, action)
);
