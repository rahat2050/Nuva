# NUVA Roadmap

Two major phases (§19). The PHASE 2 Android implementation is present in this branch, but a production release still requires PHASE 1 to be deployed to Vercel first.

## PHASE 1 — Vercel Backend Foundation

| Exit criterion (§19)              | Status | Evidence                                                      |
| --------------------------------- | ------ | ------------------------------------------------------------- |
| GitHub repository exists          | ✅     | `rahat2050/Nuva`                                              |
| Backend exists                    | ✅     | `backend/` — 4 endpoints, 16 lib modules                       |
| TypeScript builds                 | ✅     | `npm run build` (tsc, strict) passes                           |
| Health endpoint works             | ✅     | `GET /api/health` + `?deep=1`, verified locally                |
| Groq integration works            | ✅     | `lib/groq.ts`, 16 tests vs a mock Groq server¹                 |
| Structured JSON works             | ✅     | Registry + strict validation, 200 OK envelope verified          |
| Supabase connection works         | ✅     | Clients, RLS migrations, `?deep=1` schema probe²                |
| Secrets configured securely       | ✅     | Env-only, redacted logs, `.env.example` placeholders            |
| **Vercel production deployment**  | ⏳     | **Requires the human developer's Vercel account**              |

¹ Verified end to end against a mock that speaks Groq's real API shape. A live-key smoke test is in
`docs/testing.md` §6 and should be run once deployed.
² Schema/credential verification needs a real Supabase project; `?deep=1` reports it precisely.

### Remaining PHASE 1 work (human developer)

1. Create the Supabase project; run `supabase/migrations/0001…0003` in order.
2. Create the Vercel project with **Root Directory = `backend`**.
3. Add env vars: `GROQ_API_KEY`, `SUPABASE_URL`, `SUPABASE_ANON_KEY`,
   `SUPABASE_SERVICE_ROLE_KEY` (+ optional `NUVA_*` overrides).
4. Deploy, then run the smoke test in `docs/testing.md` §6 and confirm
   `checks.groq.status == "ok"`, `checks.supabase.status == "ok"`, `meta.source == "groq"`.
5. Consider `NUVA_REQUIRE_AUTH=true` once the Android app can sign in.

## PHASE 2 — Full NUVA Android project

Internal build order (§20). Status after the 2026-08-24 implementation pass (`android/`):

| #  | Step                     | Status | Where                                                            |
| -- | ------------------------ | ------ | ---------------------------------------------------------------- |
| 1  | Android project foundation | ✅ | Gradle 8.9 + AGP 8.7.3, Kotlin 2.0.20, `com.nuva.assistant`, minSdk 26 |
| 2  | Compose UI               | ✅ | `ui/home` (idle/listening/processing/done/failed/confirm), History, Memory, Settings |
| 3  | Voice input/output       | ✅ | `voice/SpeechRecognizerController` (bn-BD + en-US, partials), `voice/TTSManager` |
| 4  | Vercel API integration   | ✅ | `ai/NuvaApi` + `ai/AIRepository` (incl. SSE `/api/ai/command/stream`) |
| 5  | Groq action parsing      | ✅ | `command/Intent.kt`, `command/Action.kt` (registry mirror), `ai/ActionParser` |
| 6  | Command engine           | ✅ | `command/CommandParser` (offline), `command/CommandExecutor` |
| 7  | Action validation        | ✅ | `command/CommandValidator` — full local re-validation + risk floor |
| 8  | AccessibilityService     | ✅ | `accessibility/NuvaAccessibilityService`, `NodeFinder` (§9 priority) |
| 9  | Basic Android actions    | ✅ | `automation/AppLauncher` (home/back/app/alarm/timer/dial/URL) |
| 10 | Generic UI automation    | ✅ | Tap/Text/Swipe controllers + ScreenReader, retries + timeouts |
| 11 | WhatsApp automation      | ✅ | `automation/WhatsAppAutomation` (wa.me deep link + node flow) |
| 12 | YouTube automation       | ✅ | `automation/YouTubeAutomation` (search + play) |
| 13 | Browser automation       | ✅ | `automation/BrowserAutomation` (search, navigate) |
| 14 | Room database            | ✅ | `database/` (CommandHistory, PendingAction, LocalMemory) |
| 15 | Supabase integration     | ✅ | `supabase/SupabaseRepository` (GoTrue REST) + `SyncManager` |
| 16 | Memory                   | ✅ | `memory/MemoryManager` — local first, push/pull sync |
| 17 | Authentication           | ✅ | Settings sign-in; flip `NUVA_REQUIRE_AUTH=true` server-side when ready |
| 18 | Security                 | ✅ | `core/security/SecurityPolicy`; no secrets in APK |
| 19 | Confirmation system      | ✅ | Blocking AlertDialog for medium/high; no off switch |
| 20 | Wake word                | ✅ | `service/WakeWordService` opt-in foreground fallback + `ui/floating` overlay |
| 21 | Testing                  | ⏳ | JVM unit tests in `app/src/test`; instrumented + §23 matrix pending |
| 22 | Performance optimization | ⏳ | SSE streaming already wired; profiling pending |
| 23 | Release APK              | ⏳ | Release build configured (minify+shrink); signing pending |

⏳ items need a real Android SDK/machine (Gradle build, instrumented tests, profiling, signed APK)
and the production backend URL — they are listed for the human developer's machine pass.

## Backend follow-ups (completed 2026-08-24)

| Follow-up                                            | Status | Where                                                                 |
| ---------------------------------------------------- | ------ | --------------------------------------------------------------------- |
| Distributed rate limiting (Upstash)                  | ✅     | `lib/ratelimit.ts` → `checkRateLimitDistributed` (REST pipeline, automatic in-memory fallback); `UPSTASH_REDIS_REST_*` env |
| Cloudinary upload endpoint for screenshots (§18)     | ✅     | `POST /api/screenshots` + `lib/cloudinary.ts` — signed direct upload, secret never leaves the server |
| `POST /api/devices` registration                     | ✅     | `api/devices/index.ts` + `registerDevice`/`listDevices` in `lib/repository.ts` |
| Streaming to cut perceived latency                   | ✅     | `POST /api/ai/command/stream` — SSE `stage` events + the identical `result` payload |
| Model evaluation harness (`gpt-oss-20b` vs `gpt-oss-120b`) | ✅ | `npm run eval` (`scripts/eval-models.ts` + 32-case Bangla/Banglish/English dataset in `scripts/eval-dataset.json`, kept honest by `tests/eval.test.ts`) |
| Pin `GROQ_MODEL` + deprecation watch                 | ✅     | Pinned in `lib/env.ts` defaults; automatic fallback model in `lib/groq.ts`; `?deep=1` warns when the model vanishes from Groq's live list |

## Definition of done (§28)

NUVA v1.0 ships when the user can speak in Bangla/Banglish/English, have the command understood,
validated, confirmed when risky, executed via AccessibilityService, verified, remembered, and spoken
back — on a stable release build. The repo now contains both backend and Android app halves; remaining
release work is production deployment, real-device validation, profiling and signed APK generation.
