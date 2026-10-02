-- Credential names, unique per Git instance (ADR 0029). Pre-release: a database that already holds credentials cannot
-- take the not-null column and must be recreated.
alter table credential
    add column name varchar(100) not null,
    add constraint credential_git_instance_name_unique unique (git_instance, name);
