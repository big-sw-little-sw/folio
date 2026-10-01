-- One rule per namespace and action (design 19.4). Slice 3 adds ConfigSet rules.
-- Deleting a namespace deletes its rules, so the namespace module needs no knowledge of policy.
create table policy_rule (
    id uuid primary key default uuidv7(),
    namespace_id uuid not null references namespace (id) on delete cascade,
    action varchar(80) not null,
    constraint policy_rule_namespace_action_unique unique (namespace_id, action)
);

-- Who a rule grants to (design 19.5). Public and authenticated subjects have no external ID;
-- NULLS NOT DISTINCT keeps them unique per rule.
create table policy_subject (
    policy_rule_id uuid not null references policy_rule (id) on delete cascade,
    subject_type varchar(40) not null
        check (subject_type in ('PUBLIC', 'AUTHENTICATED', 'USER', 'GROUP', 'APPLICATION')),
    external_id varchar(500),
    constraint policy_subject_external_id_check
        check ((subject_type in ('PUBLIC', 'AUTHENTICATED')) = (external_id is null)),
    constraint policy_subject_unique unique nulls not distinct (policy_rule_id, subject_type, external_id)
);
