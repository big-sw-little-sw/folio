-- These tables keep the Catalog: the tree of Namespaces and ConfigSets, and the rules on them. The catalog services
-- check the invariants under the tree lock. These constraints are a second check. Without them, a bug in a service
-- could store a tree that is not valid.

create table namespace (
    id uuid primary key default uuidv7(),
    -- parent_id is null for a top-level Namespace. The foreign key has no cascade, because Folio deletes only an
    -- empty Namespace.
    parent_id uuid references namespace (id),
    slug text not null check (char_length(slug) <= 100 and slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    description text check (char_length(description) <= 1000),
    -- NULLS NOT DISTINCT makes two top-level Namespaces with the same slug a conflict. Without it, PostgreSQL treats
    -- each null parent_id as different, and two top-level Namespaces can have the same slug.
    constraint namespace_slug_unique unique nulls not distinct (parent_id, slug)
);

create table config_set (
    id uuid primary key default uuidv7(),
    -- The foreign key has no cascade, so a delete of a Namespace never removes a ConfigSet that consumers use.
    namespace_id uuid not null references namespace (id),
    slug text not null check (char_length(slug) <= 100 and slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    description text check (char_length(description) <= 1000),
    -- source_type holds the name of a SourceType constant. The value never changes after creation, so published
    -- revisions stay immutable.
    source_type text not null,
    constraint config_set_slug_unique unique (namespace_id, slug)
);

-- Each Namespace or ConfigSet has at most one rule for each action. A delete of a resource also deletes its rules.
create table rule (
    id uuid primary key default uuidv7(),
    namespace_id uuid references namespace (id) on delete cascade,
    config_set_id uuid references config_set (id) on delete cascade,
    action text not null check (action in ('READ', 'MANAGE', 'GRANT')),
    -- subjects holds the text form of each subject, for example group:<id>. A rule without subjects would deny the
    -- action to all callers, but Folio has grants only.
    subjects text[] not null check (cardinality(subjects) >= 1),
    constraint rule_one_target check (num_nonnulls(namespace_id, config_set_id) = 1),
    constraint rule_namespace_action_unique unique (namespace_id, action),
    constraint rule_config_set_action_unique unique (config_set_id, action)
);
