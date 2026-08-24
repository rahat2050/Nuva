# NUVA Backend — PHRASE 1

The secure gateway between the NUVA Android app and Groq/Supabase. Text in, a **validated,
risk-classified action** out. It never executes anything on a device.

## Commands

```bash
npm install
npm run build     # tsc --noEmit (strict)
npm test          # vitest run — 188 tests
npm run verify    # build + test
npm run dev       # local server on :3000, manual test console at /
npm run eval      # model evaluation harness (needs GROQ_API_KEY)
```

## Endpoints

| Method | Path                       | Auth      | Purpose                                    |
| ------ | -------------------------- | --------- | ------------------------------------------ |
| GET    | `/api/health`              | –         | Config status; `?deep=1` pings Groq + DB.  |
| POST   | `/api/ai/command`          | optional  | Interpret a command into a safe action.    |
| POST   | `/api/ai/command/stream`   | optional  | Same, streamed as SSE progress + result.   |
| GET    | `/api/commands`            | required  | Command history.                            |
| POST   | `/api/commands`            | required  | Report an execution result.                 |
| GET    | `/api/memory`              | required  | Read preferences.                           |
| POST   | `/api/memory`              | required  | Save a preference.                          |
| DELETE | `/api/memory?key=`         | required  | Forget a preference.                        |
| GET    | `/api/devices`             | required  | List registered devices.                    |
| POST   | `/api/devices`             | required  | Register/refresh this device.               |
| POST   | `/api/screenshots`         | required  | Signed Cloudinary direct-upload grant.      |

Rate limiting is per-identity (`X-Nuva-Device-Id` or IP): in-memory by default, or global across
instances when `UPSTASH_REDIS_REST_URL`/`UPSTASH_REDIS_REST_TOKEN` are set — every response carries
`X-RateLimit-Mode` telling the client which one answered.

Auth is a Supabase user JWT: `Authorization: Bearer <access_token>`. There is deliberately no shared
app secret — see [`../docs/security.md`](../docs/security.md) §1.

Full request/response schemas: [`../docs/commands.md`](../docs/commands.md).

## Layout

```
backend/
├── api/            one file per route; thin — parse, delegate, return
│   ├── health/index.ts
│   ├── ai/command.ts
│   ├── ai/command/stream.ts
│   ├── commands/index.ts
│   ├── memory/index.ts
│   ├── devices/index.ts
│   └── screenshots/index.ts
├── lib/            all logic (see ../docs/architecture.md §5)
├── scripts/        model evaluation harness + dataset
├── types/          dependency-free contracts, mirrored into Kotlin in PHRASE 2
├── tests/          14 vitest suites
├── dev/            local server + Vercel shim (excluded from deploys)
├── vercel.json     function config + security headers
└── .env.example    every variable, with placeholders only
```

## Runs without credentials

| Missing               | Behaviour                                                                    |
| --------------------- | ---------------------------------------------------------------------------- |
| `GROQ_API_KEY`        | `/api/health` fine; `/api/ai/command` uses the deterministic offline parser for simple low-risk commands, else `503`. |
| `SUPABASE_*`          | Interpretation fine; nothing persisted; `/api/commands`, `/api/memory` and `/api/devices` `503`/`401`. |
| `CLOUDINARY_*`        | `POST /api/screenshots` answers `503 NOT_CONFIGURED`; everything else fine.  |
| `UPSTASH_REDIS_REST_*`| Rate limiting silently uses the per-instance memory limiter (`X-RateLimit-Mode: memory`). |
| Both                  | Full pipeline still testable — this is how the test suite runs.               |

`meta.source` in the response tells you which brain answered: `"groq"` or `"fallback"`.

## Dependencies (kept minimal, §26)

| Package                 | Why                                                            |
| ----------------------- | -------------------------------------------------------------- |
| `zod`                   | Runtime validation of untrusted model output — core to safety.  |
| `@supabase/supabase-js` | Auth + database.                                               |
| `@vercel/node` (dev)    | Request/response types.                                        |
| `typescript`, `vitest`, `tsx` (dev) | Build, test, local run.                            |

Groq is called with **native `fetch`** — no SDK — so timeouts, retries and model fallback are fully
under our control with no extra supply-chain surface.

## Deployment

Vercel project with **Root Directory = `backend`** (or repo root — the root `vercel.json`
handles that too). Node 22.x is pinned via `engines` in `package.json` (the `functions.runtime` field must stay unset — Vercel resolves @vercel/node automatically and rejects version strings like `nodejs22.x`). `vercel.json` pins
**Build Command = `npm run build`**, **Install Command = `npm install --include=dev`** and
**Output Directory = `public`**; the committed `public/index.html` is a small landing page so
API-only deployments do not fail Vercel's output folder check. Functions are detected from
`api/**/*.ts`; `dev/` and `tests/` are excluded by `.vercelignore`.

> If the build ever fails with `sh: tsc: not found`, a `NODE_ENV=production` env var made npm
> skip devDependencies — remove that variable (the pinned `--include=dev` install command
> already guards against it).

Required env vars: `GROQ_API_KEY`, `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `SUPABASE_SERVICE_ROLE_KEY`.
Optional `NUVA_*`, `UPSTASH_*`, `CLOUDINARY_*` overrides are documented in `.env.example`.

## Model choice

Verified against Groq's docs on 2026-08-23:

| Model                  | Speed     | Use                                    |
| ---------------------- | --------- | -------------------------------------- |
| `openai/gpt-oss-20b`   | ~1000 t/s | **default** — lowest latency for voice |
| `openai/gpt-oss-120b`  | ~500 t/s  | automatic fallback / stronger reasoning |

`llama-3.3-70b-versatile` and `llama-3.1-8b-instant` were **shut down 2026-08-16** and must not be
used. If the configured model is ever decommissioned, `lib/groq.ts` detects the error and retries on
`GROQ_FALLBACK_MODEL` without needing a redeploy.
