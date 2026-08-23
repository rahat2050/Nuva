# NUVA Roadmap

Two major phases (§19). PHRASE 2 must not begin until PHRASE 1 is deployed to Vercel production.

## PHRASE 1 — Vercel Backend Foundation

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

### Remaining PHRASE 1 work (human developer)

1. Create the Supabase project; run `supabase/migrations/0001…0003` in order.
2. Create the Vercel project with **Root Directory = `backend`**.
3. Add env vars: `GROQ_API_KEY`, `SUPABASE_URL`, `SUPABASE_ANON_KEY`,
   `SUPABASE_SERVICE_ROLE_KEY` (+ optional `NUVA_*` overrides).
4. Deploy, then run the smoke test in `docs/testing.md` §6 and confirm
   `checks.groq.status == "ok"`, `checks.supabase.status == "ok"`, `meta.source == "groq"`.
5. Consider `NUVA_REQUIRE_AUTH=true` once the Android app can sign in.

## PHRASE 2 — Full NUVA Android project

Internal build order (§20). Do not jump ahead.

| #  | Step                     | Notes                                                            |
| -- | ------------------------ | ---------------------------------------------------------------- |
| 1  | Android project foundation | Kotlin, Compose, `com.nuva.assistant`, min SDK 26              |
| 2  | Compose UI               | Voice-first single screen; states: idle/listening/processing/executing/done/failed/confirm |
| 3  | Voice input/output       | `SpeechRecognizer` (bn-BD + en-US), `TextToSpeech`                |
| 4  | Vercel API integration   | Retrofit/OkHttp against the frozen contract in `docs/commands.md` |
| 5  | Groq action parsing      | Mirror `types/action.ts` into Kotlin data classes                 |
| 6  | Command engine           | `CommandParser`, `CommandExecutor`                                |
| 7  | Action validation        | **Re-validate locally** — server check is not the only gate       |
| 8  | AccessibilityService     | `NuvaAccessibilityService`, `NodeFinder`                          |
| 9  | Basic Android actions    | home, back, open/close app, alarm, timer, URL                     |
| 10 | Generic UI automation    | tap, type, swipe, scroll, read screen; timeouts + retries         |
| 11 | WhatsApp automation      | First confirmed medium-risk flow                                   |
| 12 | YouTube automation       | Search + play                                                      |
| 13 | Browser automation       | Search, navigate                                                   |
| 14 | Room database            | `CommandHistory`, `LocalMemory`, `UserPreferences`, `PendingAction` |
| 15 | Supabase integration     | Auth + `SyncManager`                                               |
| 16 | Memory                   | Local first, then sync                                             |
| 17 | Authentication           | Supabase sign-in; then flip `NUVA_REQUIRE_AUTH=true`               |
| 18 | Security                 | No secrets in APK, permission audit                                |
| 19 | Confirmation system      | Blocking UI for medium/high risk                                   |
| 20 | Wake word                | "Hey Nuva", opt-in, battery-aware, never silent recording           |
| 21 | Testing                  | Unit + instrumented; the §23 manual matrix                         |
| 22 | Performance optimization | Latency budget: speech → action → execution                        |
| 23 | Release APK              | Signed release build                                               |

## Backend follow-ups (deferred, not blocking)

- Distributed rate limiting (Upstash) — see `docs/security.md` §8.
- Cloudinary upload endpoint for screenshots (§18) — env documented, no code yet.
- `POST /api/devices` registration (table exists, endpoint not needed yet).
- Streaming/partial transcripts to cut perceived latency.
- Model evaluation harness comparing `gpt-oss-20b` vs `gpt-oss-120b` on a Bangla/Banglish command set.
- Pin `GROQ_MODEL` deliberately and watch Groq's deprecation page; `?deep=1` warns when the
  configured model disappears from the live list.

## Definition of done (§28)

NUVA v1.0 ships when the user can speak in Bangla/Banglish/English, have the command understood,
validated, confirmed when risky, executed via AccessibilityService, verified, remembered, and spoken
back — on a stable release build. PHRASE 1 delivers the understanding + validation + risk half of
that sentence.
