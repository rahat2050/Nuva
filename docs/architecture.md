# NUVA Architecture

> Status: **PHASE 1 (Vercel Backend Foundation) implemented.** PHASE 2 Android app is implemented, including user-selectable default-assistant registration plus the opt-in visible foreground wake-word fallback; real-device build/release verification is still pending.

## 1. The engineering model (§29)

| Component            | Role              |
| -------------------- | ----------------- |
| Groq                 | Brain (reasoning) |
| Android app          | Interface         |
| AccessibilityService | Hands             |
| Vercel               | Secure gateway    |
| Supabase             | Memory            |
| Room                 | Local memory      |
| Cloudinary           | Media storage     |
| GitHub               | Source control    |

## 2. The golden pipeline (§30)

Every command follows exactly this path. Steps marked **[BE]** live in `backend/`; steps marked
**[APP]** live in the Kotlin Android app under `android/`.

```
ASSIST / WAKE             [APP]  Android VoiceInteractionService or visible WakeWordService
   ↓
FLOATING UI               [APP]  overlay shows listening/processing/confirmation/result
   ↓
USER COMMAND              [APP]  microphone / text input
   ↓
VOICE INPUT               [APP]  Android SpeechRecognizer
   ↓
TEXT NORMALIZATION        [BE]   lib/normalize.ts     — NFC, wake-word strip, language detect
   ↓
AI UNDERSTANDING          [BE]   lib/groq.ts + lib/prompt.ts
   ↓
STRUCTURED ACTION         [BE]   JSON envelope from the model
   ↓
VALIDATION                [BE]   lib/validate.ts + lib/actions.ts  — strict whitelist
   ↓
RISK CHECK                [BE]   lib/risk.ts          — server-authoritative
   ↓
CONFIRMATION IF REQUIRED  [APP]  driven by result.requires_confirmation
   ↓
EXECUTION                 [APP]  CommandExecutor → AccessibilityService
   ↓
RESULT VERIFICATION       [APP]  re-read the screen
   ↓
MEMORY/HISTORY UPDATE     [BE]   lib/repository.ts → Supabase
   ↓
VOICE RESPONSE            [APP]  TTS speaks result.speech
```

The backend's job is to be the **safe interpreter**: text in, validated + risk-classified action out.
It never executes anything, so a compromised model cannot reach the device.

## 3. Request flow

```
Android app
  │  POST /api/ai/command   { text: "Nuva YouTube open koro." }
  │  Authorization: Bearer <supabase user jwt>
  ▼
Vercel serverless function (api/ai/command.ts)
  │  1. defineHandler:  request id, CORS, security headers, body limit, rate limit
  │  2. resolveIdentity: verify the JWT with Supabase (anon client)
  │  3. interpretCommand (lib/pipeline.ts)
  │       ├─ normalizeCommand      strip "Nuva", detect banglish
  │       ├─ groqChatJson          GROQ_API_KEY stays server-side
  │       ├─ validateModelOutput   registry whitelist, strict schemas
  │       ├─ assessRisk            max(baseline, keywords, model claim)
  │       └─ recordCommand         Supabase audit trail (service role)
  ▼
{ ok: true, result: { intent, action, risk, requires_confirmation, speech }, meta: {...} }
```

## 4. Repository layout

```
NUVA/
├── android/        PHASE 2 — Kotlin + Compose assistant app
│   └── app/src/main/java/com/nuva/assistant/
│       ├── voice/          SpeechRecognizer, TTS, wake phrase detector
│       ├── systemassistant/ default-assistant/session/recognizer bridge
│       ├── service/        foreground listening + visible wake-word service
│       ├── ui/floating/    small overlay assistant popup
│       └── accessibility/  Android AccessibilityService automation
├── backend/        PHASE 1 — Vercel serverless API  (implemented)
│   ├── api/        HTTP endpoints, one file per route
│   ├── lib/        all logic; endpoints stay thin
│   ├── types/      dependency-free contracts mirrored into Kotlin
│   ├── tests/      vitest suites
│   └── dev/        local harness, excluded from deploys
├── supabase/       migrations + seed
└── docs/           this documentation set
```

### Why the logic lives in `lib/`, not in `api/`

Each `api/**` file only parses its request and delegates. That keeps the whole pipeline testable
without HTTP, and means a second transport (e.g. a WebSocket for streaming voice in a later phase)
does not require reimplementing any rules.

## 5. Backend modules

| Module                 | Responsibility                                                          |
| ---------------------- | ----------------------------------------------------------------------- |
| `lib/env.ts`           | Validated env access. Returns booleans about secrets, never the values.  |
| `lib/logger.ts`        | Structured JSON logs with automatic secret redaction.                    |
| `lib/errors.ts`        | `NuvaError` + user-facing speech per error code, in bn/en/banglish.       |
| `lib/http.ts`          | `defineHandler` — the one place headers, CORS, limits and errors happen. |
| `lib/normalize.ts`     | Text normalization and language detection.                               |
| `lib/actions.ts`       | **The action registry.** Zod schemas + metadata for the 15 actions.      |
| `lib/apps.ts`          | App alias → slug/package/label resolution (en, bn, banglish).            |
| `lib/prompt.ts`        | System prompt, generated from the registry so it cannot drift.           |
| `lib/groq.ts`          | Groq HTTP client: timeouts, retries, model fallback.                     |
| `lib/validate.ts`      | Model output → validated action, or a safe refusal.                      |
| `lib/risk.ts`          | Risk classification and confirmation decision.                           |
| `lib/speech.ts`        | Deterministic TTS phrasing in three languages.                           |
| `lib/fallbackParser.ts`| Offline deterministic parser for low-risk commands only.                 |
| `lib/auth.ts`          | Supabase JWT verification, identity resolution.                          |
| `lib/repository.ts`    | Supabase reads/writes (service role, always scoped by user_id).          |
| `lib/ratelimit.ts`     | Best-effort per-instance abuse brake.                                    |
| `lib/pipeline.ts`      | Orchestrates the golden pipeline.                                        |

## 6. Endpoints

| Method | Path                | Auth        | Purpose                                    |
| ------ | ------------------- | ----------- | ------------------------------------------ |
| GET    | `/api/health`       | none        | Config status; `?deep=1` pings Groq + DB.  |
| POST   | `/api/ai/command`   | optional¹   | Interpret a command into a safe action.    |
| GET    | `/api/commands`     | **required**| Command history.                            |
| POST   | `/api/commands`     | **required**| Report an execution result.                 |
| GET    | `/api/memory`       | **required**| Read remembered preferences.                |
| POST   | `/api/memory`       | **required**| Save a preference.                          |
| DELETE | `/api/memory?key=`  | **required**| Forget a preference.                        |

¹ Anonymous interpretation works (nothing is persisted). Set `NUVA_REQUIRE_AUTH=true` to require a
user. Full request/response schemas: [`commands.md`](commands.md).

## 7. Data model

See [`../supabase/migrations/`](../supabase/migrations/). Six tables, all owner-only under RLS:
`profiles`, `conversations`, `commands`, `memories`, `devices`, `settings`.

Deviations from §13's field list, all deliberate and additive:

- `commands.risk` — needed to audit the §11 risk system.
- `commands.error` / `commands.updated_at` — needed to report execution outcomes from the device.
- `settings.confirmation_mode` is `always | risky_only`. There is deliberately **no `never`**, so
  "skip all confirmations" is not representable in the schema (§11).

## 8. The AI boundary

The model is untrusted input, not a trusted component.

- It can only choose from 15 registered action types; anything else becomes `UNSUPPORTED`.
- Action schemas are `.strict()`, so unknown keys are rejected rather than forwarded.
- It cannot lower risk or waive a confirmation — the server takes the maximum (`lib/risk.ts`).
- Screen text from `READ_SCREEN` is fenced and labelled untrusted in the prompt, so page content
  cannot issue instructions.
- It cannot emit shell commands, file paths or arbitrary URLs: there is no action that accepts them,
  and `OPEN_URL` allows only `http`/`https`.

## 9. Degradation behaviour

| Condition                            | Behaviour                                                        |
| ------------------------------------ | ---------------------------------------------------------------- |
| Groq reachable, output is garbage    | `200` + `UNSUPPORTED` — "I couldn't understand that command."     |
| Groq unreachable / timing out        | Offline parser tries; else `502`/`504` — "I can't reach the NUVA server." |
| `GROQ_API_KEY` unset                 | Offline parser tries; else `503 NOT_CONFIGURED`.                  |
| Supabase unset                       | Interpretation works; history/memory return `503`.                |
| No user token                        | Interpretation works, nothing persisted; per-user endpoints `401`. |

An outage is never disguised as a comprehension failure — that distinction is what makes §24's error
messages honest.

## 10. Android integration contract (PHASE 2)

`types/action.ts` and `types/api.ts` are intentionally dependency-free so they can be mirrored 1:1
into Kotlin data classes. The Android side must **re-validate** every action before executing it —
the server check is the first gate, not the only one (§7).
