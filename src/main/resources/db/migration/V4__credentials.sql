-- Credentials and their SSH keys (design 19.7, narrowed by v1-scope; ADRs 0016, 0017 and 0019).
create table credential (
    id uuid primary key default uuidv7(),
    -- A name from folio.git.instances; instances are deployment configuration, not rows (ADR 0016).
    git_instance varchar(100) not null,
    status varchar(20) not null check (status in ('ENABLED', 'DISABLED'))
);

-- IDs come from the application (select uuidv7()), because the associated data of the encryption includes them.
-- A key's private key is PKCS#8 encrypted with AES-256-GCM under an HKDF-SHA256 key from the master secret of
-- master_key_version and the salt (ADR 0019). Retiring a key wipes all five encryption columns.
create table credential_key (
    id uuid primary key,
    credential_id uuid not null references credential (id),
    status varchar(20) not null check (status in ('PENDING', 'ACTIVE', 'RETIRED')),
    public_key varchar(200) not null,
    fingerprint varchar(100) not null,
    algorithm varchar(40),
    master_key_version integer,
    salt bytea,
    nonce bytea,
    ciphertext bytea,
    constraint credential_key_encryption_check check (
        num_nulls(algorithm, master_key_version, salt, nonce, ciphertext)
            = case when status = 'RETIRED' then 5 else 0 end
    )
);

create index credential_key_credential_id_idx on credential_key (credential_id);
create unique index credential_key_one_active on credential_key (credential_id) where status = 'ACTIVE';
create unique index credential_key_one_pending on credential_key (credential_id) where status = 'PENDING';
