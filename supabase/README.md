# NUVA Supabase

Schema, Row Level Security and triggers for NUVA's memory layer (§13).

## Apply the migrations

**In order.** Each file is idempotent, so re-running is safe.

```bash
# Option A — Supabase CLI
supabase link --project-ref <your-project-ref>
supabase db push

# Option B — SQL editor in the Supabase dashboard
#   paste migrations/0001_init.sql, then 0002_rls.sql, then 0003_functions.sql
```

| File                        | Contents                                                        |
| --------------------------- | --------------------------------------------------------------- |
| `migrations/0001_init.sql`  | Six tables, constraints, indexes                                 |
| `migrations/0002_rls.sql`   | RLS enabled + owner-only policies + role grants                  |
| `migrations/0003_functions.sql` | `updated_at` triggers, `handle_new_user()` provisioning       |
| `seed.sql`                  | Sanity checks + documented memory keys (no fake users)           |

## Tables

| Table           | Purpose                                        | Key                     |
| --------------- | ---------------------------------------------- | ----------------------- |
| `profiles`      | One row per user                                | `id` = `auth.users.id`  |
| `conversations` | User/assistant transcript                       | `user_id`               |
| `commands`      | Audit trail: command → action → risk → status   | `user_id`               |
| `memories`      | Preferences NUVA remembers                      | `user_id` + `key` unique |
| `devices`       | Linked Android devices                          | `user_id`               |
| `settings`      | Language, voice, confirmation mode, theme       | `user_id` unique        |

## Security notes

- RLS is **on** for all six tables; policies are strictly owner-only (`auth.uid()`).
- `anon` has no table grants. `authenticated` operates only under RLS.
- The backend's service-role key bypasses RLS, so `backend/lib/repository.ts` always filters by a
  `user_id` taken from a **verified JWT** — never from the request body.
- `settings.confirmation_mode` allows only `always` or `risky_only`. There is deliberately no
  `never`: §11 requires that high-risk actions can never skip confirmation, so the schema makes that
  state unrepresentable.
- `memories` never stores credentials — `/api/memory` rejects credential-like keys with `400`.
- Everything cascades on `auth.users` deletion, so removing an account removes all its data.

## Verifying

```bash
curl -s "https://<project>.vercel.app/api/health?deep=1" | jq '.checks.supabase'
```

- `"REST reachable, schema present"` → migrations applied correctly.
- `"…\`profiles\` query failed — run the migrations…"` → migrations not applied yet.

## New users

`handle_new_user()` fires on insert into `auth.users` and provisions a `profiles` row plus default
`settings`, so the Android app never has to special-case a first launch. Create users through
Supabase Auth rather than seeding rows by hand.
