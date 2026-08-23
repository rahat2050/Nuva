-- ===========================================================================
-- NUVA v1.0 — triggers and helper functions
--
--   1. touch_updated_at()  — keeps updated_at honest on every UPDATE
--   2. handle_new_user()   — provisions profiles + settings on signup so the
--                            Android app never has to special-case "first run"
-- ===========================================================================

-- --- 1. updated_at maintenance --------------------------------------------
create or replace function public.touch_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

drop trigger if exists commands_touch_updated_at on public.commands;
create trigger commands_touch_updated_at
  before update on public.commands
  for each row execute function public.touch_updated_at();

drop trigger if exists memories_touch_updated_at on public.memories;
create trigger memories_touch_updated_at
  before update on public.memories
  for each row execute function public.touch_updated_at();

drop trigger if exists settings_touch_updated_at on public.settings;
create trigger settings_touch_updated_at
  before update on public.settings
  for each row execute function public.touch_updated_at();

-- --- 2. provision a new user ----------------------------------------------
-- SECURITY DEFINER is required to write into public tables from the auth
-- schema trigger. search_path is pinned to '' and every reference is fully
-- qualified, which is the standard defence against search_path hijacking.
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  insert into public.profiles (id, name, email)
  values (
    new.id,
    coalesce(new.raw_user_meta_data ->> 'name', new.raw_user_meta_data ->> 'full_name'),
    new.email
  )
  on conflict (id) do nothing;

  insert into public.settings (user_id)
  values (new.id)
  on conflict (user_id) do nothing;

  return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();
