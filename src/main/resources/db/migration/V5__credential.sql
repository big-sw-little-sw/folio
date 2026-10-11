-- These tables keep the credentials and their SSH keys (design 11, 16). The credential module owns them. The catalog
-- reads the credential table only to examine if a Namespace is empty (design 5.4).

create table credential (
    id uuid primary key default uuidv7(),
    -- The foreign key does not cascade. Folio deletes only an empty Namespace, and a Namespace that owns a credential
    -- is not empty (design 5.4). Thus the foreign key stops the delete of a Namespace that still owns a credential.
    namespace_id uuid not null references namespace (id),
    -- git_instance names an entry of folio.git.instances. Git instances are deployment configuration, not rows
    -- (design 10.1). Thus Folio validates the name with the slug rules only. The Git check reports a name that the
    -- configuration does not have.
    git_instance text not null check (char_length(git_instance) <= 100 and git_instance ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    name text not null check (char_length(name) <= 100 and name ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    status text not null check (status in ('ENABLED', 'DISABLED')),
    constraint credential_name_unique unique (namespace_id, name)
);

-- The id has no default. Folio gets the ID with "select uuidv7()" before the insert, because the associated data of
-- the encryption contains it (design 5.2, 11.2).
create table credential_key (
    id uuid primary key,
    credential_id uuid not null references credential (id) on delete cascade,
    status text not null check (status in ('PENDING', 'ACTIVE', 'RETIRED')),
    -- public_key is the OpenSSH line without its comment. The comment is the API ID of the key, so Folio adds it when
    -- it shows the line.
    public_key text not null,
    fingerprint text not null,
    algorithm text,
    master_key_version integer check (master_key_version > 0),
    salt bytea,
    nonce bytea,
    ciphertext bytea,
    -- The five encryption columns are all null for a retired key and all set for other keys. A retired key keeps
    -- its public key and fingerprint, but Folio erases its encrypted private key (design 11.1).
    constraint credential_key_encryption check (
        num_nulls(algorithm, master_key_version, salt, nonce, ciphertext)
            = case when status = 'RETIRED' then 5 else 0 end
    )
);

create index credential_key_credential on credential_key (credential_id);

-- A credential has a maximum of one ACTIVE key and one PENDING key. Each key change locks the credential row first.
-- Thus these indexes are a second check, after the row lock.
create unique index credential_key_one_active on credential_key (credential_id) where status = 'ACTIVE';
create unique index credential_key_one_pending on credential_key (credential_id) where status = 'PENDING';
