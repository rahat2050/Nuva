-- ===========================================================================
-- NUVA v1.0 — seed data
--
-- Deliberately almost empty. NUVA's tables are all user-owned and keyed to
-- auth.users, so seeding real rows would require inventing fake auth users —
-- which then linger in a production project. Create a user through Supabase
-- Auth instead; 0003_functions.sql provisions their profile and settings
-- automatically.
--
-- Run with:  supabase db reset   (local development only)
-- ===========================================================================

-- Sanity check that the migrations were applied in order.
do $$
begin
  if not exists (select 1 from information_schema.tables
                 where table_schema = 'public' and table_name = 'commands') then
    raise exception 'NUVA schema missing — apply supabase/migrations/0001_init.sql first';
  end if;

  if not exists (select 1 from pg_trigger where tgname = 'on_auth_user_created') then
    raise warning 'handle_new_user trigger missing — apply supabase/migrations/0003_functions.sql';
  end if;

  raise notice 'NUVA schema looks good.';
end
$$;

-- ---------------------------------------------------------------------------
-- Reference: memory keys NUVA uses (§17). Rows are created per user at runtime
-- through POST /api/memory, never seeded here.
--
--   preferred_language   'bn' | 'en' | 'banglish'
--   assistant_name       what the user calls NUVA
--   favourite_apps       comma-separated app slugs, e.g. 'youtube,whatsapp'
--   default_messaging_app 'whatsapp' | 'sms' | ...
--   wake_word_enabled    'true' | 'false'
--
-- Credential-like keys (password, otp, pin, token, ...) are rejected by
-- /api/memory before they reach the database.
-- ---------------------------------------------------------------------------
