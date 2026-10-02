-- Each ConfigSet's Git source (design 12.1 and 19.6, narrowed by ADR 0022). The host comes from the credential's Git
-- instance; root_path is relative and empty for the repository root. Pre-release: a database that already holds
-- ConfigSets cannot take the not-null columns and must be recreated (ADR 0022).
alter table config_set
    add column credential_id uuid not null references credential (id),
    add column repository_path varchar(500) not null,
    add column branch varchar(250) not null,
    add column root_path varchar(1000) not null;
