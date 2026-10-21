-- Neon: only the account API's server role accesses these tables.
begin;
create table punk_computers (
 id uuid primary key, owner_id text not null, name text not null check(length(name) between 1 and 100),
 endpoint text not null default '' check(endpoint='' or endpoint ~ '^https://[a-z0-9-]+[.]trycloudflare[.]com$'),
 local_host text not null default '' check(length(local_host)<=200),
 local_port integer not null default 18765 check(local_port between 1 and 65535),
 fingerprint text not null check(fingerprint ~ '^[a-f0-9]{64}$'), last_seen timestamptz not null default now()
);
create index on punk_computers(owner_id);
create table punk_connect_grants (
 digest text primary key, computer_id uuid not null references punk_computers(id) on delete cascade,
 owner_id text not null, installation_id uuid not null, expires_at timestamptz not null,
 unique(owner_id, installation_id)
);
create table punk_account_limits (owner_id text primary key, window_start timestamptz not null, hits integer not null);
revoke all on punk_computers,punk_connect_grants,punk_account_limits from public;
commit;
