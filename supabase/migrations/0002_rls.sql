-- ===========================================================================
-- NUVA v1.0 — Row Level Security (master prompt §12)
--
-- Every table is owner-only: a user can read and write exclusively their own
-- rows. The backend's service-role key bypasses RLS by design, which is why the
-- backend always filters by a user_id taken from a VERIFIED JWT (see
-- backend/lib/auth.ts and backend/lib/repository.ts).
--
-- Policies are dropped and recreated so this migration is idempotent.
-- ===========================================================================

alter table public.profiles      enable row level security;
alter table public.conversations enable row level security;
alter table public.commands      enable row level security;
alter table public.memories      enable row level security;
alter table public.devices       enable row level security;
alter table public.settings      enable row level security;

-- --- profiles (keyed by id, not user_id) -----------------------------------
drop policy if exists "profiles are self-readable"   on public.profiles;
drop policy if exists "profiles are self-insertable" on public.profiles;
drop policy if exists "profiles are self-updatable"  on public.profiles;
drop policy if exists "profiles are self-deletable"  on public.profiles;

create policy "profiles are self-readable"   on public.profiles for select using (auth.uid() = id);
create policy "profiles are self-insertable" on public.profiles for insert with check (auth.uid() = id);
create policy "profiles are self-updatable"  on public.profiles for update using (auth.uid() = id) with check (auth.uid() = id);
create policy "profiles are self-deletable"  on public.profiles for delete using (auth.uid() = id);

-- --- conversations ---------------------------------------------------------
drop policy if exists "conversations are self-readable"   on public.conversations;
drop policy if exists "conversations are self-insertable" on public.conversations;
drop policy if exists "conversations are self-deletable"  on public.conversations;

create policy "conversations are self-readable"   on public.conversations for select using (auth.uid() = user_id);
create policy "conversations are self-insertable" on public.conversations for insert with check (auth.uid() = user_id);
create policy "conversations are self-deletable"  on public.conversations for delete using (auth.uid() = user_id);

-- --- commands --------------------------------------------------------------
drop policy if exists "commands are self-readable"   on public.commands;
drop policy if exists "commands are self-insertable" on public.commands;
drop policy if exists "commands are self-updatable"  on public.commands;
drop policy if exists "commands are self-deletable"  on public.commands;

create policy "commands are self-readable"   on public.commands for select using (auth.uid() = user_id);
create policy "commands are self-insertable" on public.commands for insert with check (auth.uid() = user_id);
create policy "commands are self-updatable"  on public.commands for update using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "commands are self-deletable"  on public.commands for delete using (auth.uid() = user_id);

-- --- memories --------------------------------------------------------------
drop policy if exists "memories are self-readable"   on public.memories;
drop policy if exists "memories are self-insertable" on public.memories;
drop policy if exists "memories are self-updatable"  on public.memories;
drop policy if exists "memories are self-deletable"  on public.memories;

create policy "memories are self-readable"   on public.memories for select using (auth.uid() = user_id);
create policy "memories are self-insertable" on public.memories for insert with check (auth.uid() = user_id);
create policy "memories are self-updatable"  on public.memories for update using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "memories are self-deletable"  on public.memories for delete using (auth.uid() = user_id);

-- --- devices ---------------------------------------------------------------
drop policy if exists "devices are self-readable"   on public.devices;
drop policy if exists "devices are self-insertable" on public.devices;
drop policy if exists "devices are self-updatable"  on public.devices;
drop policy if exists "devices are self-deletable"  on public.devices;

create policy "devices are self-readable"   on public.devices for select using (auth.uid() = user_id);
create policy "devices are self-insertable" on public.devices for insert with check (auth.uid() = user_id);
create policy "devices are self-updatable"  on public.devices for update using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "devices are self-deletable"  on public.devices for delete using (auth.uid() = user_id);

-- --- settings --------------------------------------------------------------
drop policy if exists "settings are self-readable"   on public.settings;
drop policy if exists "settings are self-insertable" on public.settings;
drop policy if exists "settings are self-updatable"  on public.settings;
drop policy if exists "settings are self-deletable"  on public.settings;

create policy "settings are self-readable"   on public.settings for select using (auth.uid() = user_id);
create policy "settings are self-insertable" on public.settings for insert with check (auth.uid() = user_id);
create policy "settings are self-updatable"  on public.settings for update using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "settings are self-deletable"  on public.settings for delete using (auth.uid() = user_id);

-- --- lock down the anon/authenticated grant surface ------------------------
-- PostgREST reaches tables through these roles; RLS above still applies.
revoke all on public.profiles, public.conversations, public.commands,
              public.memories, public.devices, public.settings
  from anon;

grant select, insert, update, delete
  on public.profiles, public.conversations, public.commands,
     public.memories, public.devices, public.settings
  to authenticated;
