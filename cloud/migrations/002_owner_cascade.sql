-- Managed Better Auth uses UUID identities on this project.
begin;
alter table punk_computers alter column owner_id type uuid using owner_id::uuid;
alter table punk_connect_grants alter column owner_id type uuid using owner_id::uuid;
alter table punk_account_limits alter column owner_id type uuid using owner_id::uuid;
alter table punk_computers add constraint punk_computers_owner_fk foreign key(owner_id) references neon_auth."user"(id) on delete cascade;
alter table punk_connect_grants add constraint punk_grants_owner_fk foreign key(owner_id) references neon_auth."user"(id) on delete cascade;
alter table punk_account_limits add constraint punk_limits_owner_fk foreign key(owner_id) references neon_auth."user"(id) on delete cascade;
commit;
