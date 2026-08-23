-- ===========================================================================
-- NUVA v1.0 — initial schema (master prompt §13)
--
-- Tables: profiles, conversations, commands, memories, devices, settings
-- Every user-owned table references auth.users(id) ON DELETE CASCADE so that
-- deleting an account removes all of that user's data.
--
-- Apply with:  supabase db push      (or paste into the Supabase SQL editor)
-- RLS lives in 0002_rls.sql, triggers in 0003_functions.sql.
-- ===========================================================================

create extension if not exists pgcrypto;

-- --- profiles ---------------------------------------------------------------
create table if not exists public.profiles (
  id         uuid primary key references auth.users (id) on delete cascade,
  name       text check (char_length(name) <= 120),
  email      text check (char_length(email) <= 320),
  created_at timestamptz not null default now()
);

comment on table public.profiles is 'One row per NUVA user, keyed by the Supabase auth user id.';

-- --- conversations ----------------------------------------------------------
-- Chat transcript used for context and history. Not a place for secrets.
create table if not exists public.conversations (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null references auth.users (id) on delete cascade,
  role       text not null check (role in ('user', 'assistant', 'system')),
  message    text not null check (char_length(message) <= 8000),
  created_at timestamptz not null default now()
);

create index if not exists conversations_user_created_idx
  on public.conversations (user_id, created_at desc);

comment on table public.conversations is 'Turn-by-turn transcript between the user and NUVA.';

-- --- commands ---------------------------------------------------------------
-- Audit trail: what was asked, what action it became, and what happened.
-- `risk`, `error` and `updated_at` are additions beyond §13's field list; they
-- are required to audit the §11 risk system and report execution outcomes.
create table if not exists public.commands (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null references auth.users (id) on delete cascade,
  command    text not null check (char_length(command) <= 2000),
  intent     text not null check (char_length(intent) <= 64),
  action     jsonb,
  risk       text not null default 'low' check (risk in ('low', 'medium', 'high')),
  status     text not null default 'ready' check (
               status in ('ready', 'pending_confirmation', 'confirmed', 'rejected',
                          'executing', 'completed', 'failed', 'unsupported')
             ),
  error      text check (char_length(error) <= 1000),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists commands_user_created_idx
  on public.commands (user_id, created_at desc);
create index if not exists commands_user_status_idx
  on public.commands (user_id, status);
-- Supports "show me everything risky I ever ran".
create index if not exists commands_user_risk_idx
  on public.commands (user_id, risk) where risk <> 'low';

comment on table public.commands is 'Audit trail of interpreted commands and their execution status.';

-- --- memories ---------------------------------------------------------------
-- Non-sensitive preferences only (§17). Credential-like keys are rejected by
-- the API layer before they ever reach this table.
create table if not exists public.memories (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null references auth.users (id) on delete cascade,
  key        text not null check (char_length(key) between 1 and 120),
  value      text not null check (char_length(value) <= 4000),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint memories_user_key_unique unique (user_id, key)
);

create index if not exists memories_user_updated_idx
  on public.memories (user_id, updated_at desc);

comment on table public.memories is 'Key/value user preferences NUVA remembers. Never credentials.';

-- --- devices ----------------------------------------------------------------
create table if not exists public.devices (
  id              uuid primary key default gen_random_uuid(),
  user_id         uuid not null references auth.users (id) on delete cascade,
  device_name     text not null check (char_length(device_name) <= 120),
  android_version text check (char_length(android_version) <= 40),
  created_at      timestamptz not null default now()
);

create index if not exists devices_user_idx on public.devices (user_id);

comment on table public.devices is 'Android devices linked to a NUVA account.';

-- --- settings ---------------------------------------------------------------
-- confirmation_mode intentionally has NO 'never' option: §11 requires that
-- high-risk actions can never bypass confirmation, so the schema makes that
-- unrepresentable rather than relying on application code.
create table if not exists public.settings (
  id                uuid primary key default gen_random_uuid(),
  user_id           uuid not null unique references auth.users (id) on delete cascade,
  language          text not null default 'banglish' check (language in ('bn', 'en', 'banglish')),
  voice_enabled     boolean not null default true,
  confirmation_mode text not null default 'risky_only' check (confirmation_mode in ('always', 'risky_only')),
  theme             text not null default 'system' check (theme in ('system', 'light', 'dark')),
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now()
);

comment on table public.settings is 'Per-user NUVA preferences.';
comment on column public.settings.confirmation_mode is
  'always = confirm every action; risky_only = confirm medium/high risk. There is deliberately no "never".';
