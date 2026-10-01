-- ConfigSets (design 19.3, narrowed by v1-scope). Slice 5 adds the Git source.
create table config_set (
    id uuid primary key default uuidv7(),
    -- No cascade: deleting a namespace that holds ConfigSets is rejected (ADR 0001). The namespace module
    -- recognizes a violation of this constraint by its name (ADR 0015).
    namespace_id uuid not null constraint config_set_namespace_fk references namespace (id),
    slug varchar(100) not null,
    -- Also serves the foreign-key check when a namespace is deleted.
    constraint config_set_namespace_slug_unique unique (namespace_id, slug)
);

-- Rules attach to a namespace or a ConfigSet, never both (design 19.4). Deleting a ConfigSet deletes its
-- rules, as deleting a namespace does.
alter table policy_rule
    alter column namespace_id drop not null,
    add column config_set_id uuid references config_set (id) on delete cascade,
    add constraint policy_rule_one_resource check ((namespace_id is null) <> (config_set_id is null)),
    add constraint policy_rule_config_set_action_unique unique (config_set_id, action);
