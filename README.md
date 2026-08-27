# NUVA v1.0

A personal AI assistant for Android that understands **Bangla, Banglish and English**, converts what
you say into a validated action, asks before doing anything risky, and carries it out on your phone.

```
"Nuva YouTube open koro."            → opens YouTube
"Nuva kal shokal 7 tay alarm dao."   → sets a 07:00 alarm for tomorrow
"Nuva Rahim ke WhatsApp e message pathao." → asks you to confirm, then sends
"নুভা এই স্ক্রিনটা পড়ো।"              → reads the screen aloud
"500 er 20 percent discount koto?"   → answers 400 offline
"5 kilometer mile e koto?"           → converts locally
"Shopping list e dim dudh add koro." → saves a local shopping item
"Parcel tracking ZX123."             → opens a sourced tracking search
"120 km 60 kmph travel time koto?"  → calculates ETA offline
"Passport ki kagoj lagbe?"           → opens sourced document requirements
"LG F4 washing machine repair UE."  → preserves model/error in product help search
"Nuva please go to home taratari."  → polite/ASR grammar routes to Home
"Gallery theke photo share koro."   → user picker, then Android share sheet
"Text file pore shonao."            → reads only the file the user selects
"user@example.com ke email koro je ami ashchi" → opens reviewed email draft
"notification reply dao je 10 minute pore ashbo" → confirmed official RemoteInput reply
"kal 9 tay schedule email user@example.com je ami ashchi" → local draft reminder, never auto-send
"passport form prepare details name address draft" → local note + official portal handoff
"file rename koro new name report.pdf" → picker + exact-target second confirmation
"photo crop koro"                     → picker + visible editor; user saves
"protidin 8 tay schedule sms message update" → persistent recurring draft reminder
"scheduled draft list dekhao"         → lists pending local drafts
"text share koro je ami ashchi"       → confirmed Android share chooser
"new contact add koro name Rahim number 01712345678" → user-reviewed contact draft
"2 number notification dismiss koro" → confirmed exact notification dismissal
"onek photo share koro"              → up to 10 user-selected photos
"contact edit koro"                  → exact picker + Contacts editor
"facebook uninstall koro"            → Android system confirmation
"vpn setting khulo"                  → exact official Settings screen
"whatsapp notification settings khulo" → resolved app-specific panel
```

## Project status

| Phase                                    | Status                                             |
| ---------------------------------------- | -------------------------------------------------- |
| **PHASE 1** — Vercel Backend Foundation | ✅ Implemented · ⏳ awaiting production deployment |
| **PHASE 2** — Android application      | ✅ Implemented (Kotlin + Compose) · build/verify on a real machine + production backend URL still needed |
| **v1.1–v2.9 passes** — practical assistant → safe Android management | ✅ Implemented in code (real-device QA still pending): reviewed handoffs plus expanded exact system panels and dynamically resolved per-app Info/notification/Store screens; see [`docs/supported-features.md`](docs/supported-features.md) |

`backend/` builds clean under strict TypeScript with **190 passing tests**. `android/` contains the
full PHASE 2 app (Kotlin + Compose, see [`android/README.md`](android/README.md)). See
[`docs/roadmap.md`](docs/roadmap.md) for the exit criteria.

## Architecture

```
USER ─▶ HEY NUVA ─▶ FLOATING POPUP ─▶ VOICE ENGINE ─▶ COMMAND ENGINE ─▶ VERCEL API ─▶ GROQ AI
                                                                          │
                                                              STRUCTURED ACTION JSON
                                                                          │
                                              ACTION VALIDATOR ─▶ RISK CHECK ─▶ CONFIRMATION
                                                                          │
                                        COMMAND EXECUTOR ─▶ ACCESSIBILITYSERVICE ─▶ PHONE
                                                                          │
                                                              RESULT ─▶ TTS RESPONSE
```

| Component            | Role              | Component  | Role           |
| -------------------- | ----------------- | ---------- | -------------- |
| Groq                 | Brain             | Supabase   | Memory         |
| Android app          | Interface         | Room       | Local memory   |
| AccessibilityService | Hands             | Cloudinary | Media storage  |
| Vercel               | Secure gateway    | GitHub     | Source control |

**The AI never controls Android.** It only proposes one of 15 registered actions; the server validates
and classifies risk; the user confirms anything sensitive; only then does Android act.

## Repository layout

```
NUVA/
├── android/      PHASE 2 — Kotlin + Compose app
├── backend/      PHASE 1 — Vercel serverless API (TypeScript)
├── supabase/     migrations (schema + RLS) and seed
├── docs/         architecture, commands, security, testing, roadmap
├── README.md
├── .gitignore
└── LICENSE
```

## Quick start (backend)

```bash
cd backend
npm install
cp .env.example .env.local     # add GROQ_API_KEY / SUPABASE_* as you get them
npm run verify                 # tsc --noEmit + 188 tests
npm run dev                    # http://localhost:3000  (test console at /)
```

The backend runs **without any credentials**: `/api/health` works, and `/api/ai/command` falls back to
a deterministic offline parser for simple low-risk commands, so you can exercise the whole pipeline
before wiring up Groq.

```bash
curl -s localhost:3000/api/health | jq
curl -s -X POST localhost:3000/api/ai/command \
  -H 'Content-Type: application/json' \
  -d '{"text":"Nuva YouTube open koro."}' | jq
```

```json
{
  "result": {
    "intent": "OPEN_APP",
    "action": { "type": "OPEN_APP", "app": "youtube", "package": "com.google.android.youtube" },
    "risk": "low",
    "requires_confirmation": false,
    "speech": "YouTube khulchi."
  }
}
```

## Deploying the backend

1. **Supabase** — create a project, run `supabase/migrations/0001…0003` in order.
2. **Vercel** — new project from this repo. Either setting works:
   - **Root Directory = `backend`** (recommended) — uses `backend/vercel.json`, which pins
     **Build Command = `npm run build`** and **Output Directory = `public`** so API-only
     deployments do not fail with a missing `public` folder.
   - **Root Directory = repo root** — the root `vercel.json` builds `backend/`, serves the
     functions under `backend/api/**` and rewrites `/api/*` to them.
3. Add environment variables (see [`backend/.env.example`](backend/.env.example)):
   `GROQ_API_KEY`, `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `SUPABASE_SERVICE_ROLE_KEY`.
   ⚠️ **Do NOT set `NODE_ENV=production`** in the project env vars — it makes npm skip
   devDependencies, so `tsc` disappears and the build fails with `tsc: not found`. Vercel
   already sets production for deployments; the repo's `installCommand` now passes
   `--include=dev` as a belt-and-braces guard.
4. Deploy, then verify:

```bash
curl -s "https://<project>.vercel.app/api/health?deep=1" | jq '.checks'
```

Both checks must report `"status": "ok"`.

> **Groq models change.** `llama-3.3-70b-versatile` was shut down on 2026-08-16, so NUVA defaults to
> `openai/gpt-oss-20b` with `openai/gpt-oss-120b` as an automatic fallback if the primary model is
> ever decommissioned. `?deep=1` warns when the configured model vanishes from Groq's live list.

## Security highlights

- No secret ever reaches the Android app: `Android → Vercel → Groq`, never `Android → Groq`.
- The action registry is a strict whitelist — an invented action becomes a safe `UNSUPPORTED` reply.
- Risk is recomputed server-side; the model can raise it but **never** lower it or waive a confirmation.
- Row Level Security on every table; the service-role key is only ever used with a user id taken from
  a verified JWT.

Full detail: [`docs/security.md`](docs/security.md).

## Documentation

| Document                                   | Contents                                          |
| ------------------------------------------ | ------------------------------------------------- |
| [architecture.md](docs/architecture.md)    | Pipeline, modules, data model, AI boundary        |
| [commands.md](docs/commands.md)            | API contract + all 15 server actions              |
| [daily-utility-commands.md](docs/daily-utility-commands.md) | Offline daily calculations, conversions and examples |
| [100-daily-skills.md](docs/100-daily-skills.md) | Numbered registry of 100 broad sourced lookups |
| [500-extended-skills.md](docs/500-extended-skills.md) | Entity×task matrices for exactly 500 precise skills |
| [10000-command-grammar.md](docs/10000-command-grammar.md) | v2.1 audit of 12,250 natural command forms |
| [1000-unsupported-skills.md](docs/1000-unsupported-skills.md) | Exact list of 1,000 blocked/unsupported direct automations |
| [user-present-files.md](docs/user-present-files.md) | v2.2 picker-based file/gallery workflows and remaining phases |
| [user-reviewed-communication.md](docs/user-reviewed-communication.md) | v2.3 email compose and official notification reply rules |
| [forms-productivity.md](docs/forms-productivity.md) | v2.4 attachments, local form drafts and scheduled compose reminders |
| [target-aware-file-mutations.md](docs/target-aware-file-mutations.md) | v2.5 rename/copy/move/delete and photo-editor safety flow |
| [persistent-scheduled-drafts.md](docs/persistent-scheduled-drafts.md) | v2.6 Room persistence, recurrence, reboot restore and cancellation |
| [safe-share-contact-notifications.md](docs/safe-share-contact-notifications.md) | v2.7 reviewed share/contact and exact notification actions |
| [multi-share-contact-app-management.md](docs/multi-share-contact-app-management.md) | v2.8 multi-item, contact edit and uninstall handoffs |
| [expanded-settings-app-management.md](docs/expanded-settings-app-management.md) | v2.9 exact system panels and per-app management |
| [security.md](docs/security.md)            | Threat model, mitigations, known limitations      |
| [testing.md](docs/testing.md)              | How to run and extend the suites                  |
| [roadmap.md](docs/roadmap.md)              | Phase gates and the 23-step PHASE 2 build order  |

## License

MIT — see [LICENSE](LICENSE).
