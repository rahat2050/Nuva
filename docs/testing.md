# NUVA Testing

## 1. Backend commands

```bash
cd backend
npm install
npm run build     # tsc --noEmit  (the PHRASE 1 "TypeScript builds" gate)
npm test          # vitest run    (156 tests)
npm run verify    # build + test
npm run dev       # local server on :3000 with a manual test console at /
```

## 2. Current status

```
Test Files  9 passed (9)
     Tests  156 passed (156)
```

| Suite                     | Tests | Covers                                                        |
| ------------------------- | ----- | ------------------------------------------------------------- |
| `normalize.test.ts`       | 21    | Wake-word stripping, language detection, size/empty guards     |
| `actions.test.ts`         | 22    | Registry integrity, strict schemas, URL scheme blocking        |
| `validate.test.ts`        | 16    | JSON extraction, unsupported/invalid handling, coercion        |
| `risk.test.ts`            | 25    | Baselines, escalation, "model cannot lower risk"               |
| `fallbackParser.test.ts`  | 13    | Offline parsing + its safety contract                          |
| `speech.test.ts`          | 10    | Three-language phrasing, confirmations always ask              |
| `pipeline.test.ts`        | 17    | End-to-end with Groq mocked, degradation, prompt injection     |
| `groq.test.ts`            | 16    | Real HTTP client vs a mock Groq: retries, model fallback, timeouts |
| `api.test.ts`             | 16    | Handlers via the Vercel shim: methods, CORS, auth, rate limits  |

No live `GROQ_API_KEY` or Supabase project is needed: Groq is verified against a mock server that
speaks the real API shape, so the suite runs in CI and in a fresh clone.

## 3. Manual endpoint checks

```bash
# Health
curl -s localhost:3000/api/health | jq
curl -s "localhost:3000/api/health?deep=1" | jq   # pings Groq + Supabase

# Command interpretation
curl -s -X POST localhost:3000/api/ai/command \
  -H 'Content-Type: application/json' \
  -d '{"text":"Nuva YouTube open koro."}' | jq

# Per-user endpoints (401 without a token — that is correct)
curl -s localhost:3000/api/commands | jq
curl -s -X POST localhost:3000/api/memory \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $SUPABASE_USER_JWT" \
  -d '{"key":"preferred_language","value":"banglish"}' | jq
```

Or open `http://localhost:3000/` for the console, which has one-click buttons for each scenario
below.

## 4. Verified command matrix

Run against the local server with no Groq key (deterministic parser):

| Utterance                                 | Language | Result                                     |
| ----------------------------------------- | -------- | ------------------------------------------ |
| `Nuva YouTube open koro.`                 | banglish | `OPEN_APP` youtube + package hint          |
| `নুভা হোম স্ক্রিনে যাও`                     | bn       | `GO_HOME`, Bengali-script speech           |
| `Nuva back jao.`                          | banglish | `GO_BACK`                                  |
| `Nuva ei screen ta poro.`                 | banglish | `READ_SCREEN` scope=visible                |
| `Nuva kal shokal 7 tay alarm dao.`        | banglish | `SET_ALARM` 07:00 relative_day=tomorrow    |
| `Nuva rat 9 tay alarm dao`                | banglish | `SET_ALARM` 21:00 (night → PM)             |
| `Set a 10 minute timer`                   | en       | `SET_TIMER` 600s                           |
| `Nuva google e dhaka weather search koro` | banglish | `OPEN_URL` google search, URL-encoded      |
| `Nuva play koro rabindra sangeet`         | banglish | `PLAY_MEDIA`                               |
| `Nuva bondho koro whatsapp`               | banglish | `CLOSE_APP` whatsapp                       |
| `…message pathao…` / `…taka pathao…`      | banglish | Refused (`503`) — parser will not act for others |

With a real `GROQ_API_KEY` the last row becomes `SEND_MESSAGE` (medium, confirmation) and
`UNSUPPORTED` (high) respectively.

## 5. Security regression checks

Each has a test:

- Unregistered action (`DELETE_FILE`, `RUN_SHELL`) → `UNSUPPORTED`, never executed.
- Extra key on a valid action → rejected.
- Model claiming `risk:low` on `SEND_MESSAGE` → still medium + confirmation.
- Model setting `requires_confirmation:false` on a message → still requires confirmation.
- `javascript:`, `data:`, `file:`, `intent:` URLs → rejected.
- Loopback/private-network URL → high risk.
- Screen text containing "IGNORE ALL RULES" → fenced as untrusted.
- Health payload contains no secret-shaped material.
- Credential-like memory keys → `400`.

## 6. Deployment smoke test

After deploying to Vercel:

```bash
BASE=https://<your-project>.vercel.app
curl -s $BASE/api/health | jq '.ok, .config'
curl -s "$BASE/api/health?deep=1" | jq '.checks'      # both should be status "ok"
curl -s -X POST $BASE/api/ai/command -H 'Content-Type: application/json' \
  -d '{"text":"Nuva YouTube open koro."}' | jq '.result, .meta.source'
```

`meta.source` must be `"groq"` in production. If it says `"fallback"`, `GROQ_API_KEY` is missing.

## 7. PHRASE 2 manual test plan (not yet executable)

Reserved from §23; nothing here can be run until the Android app exists:

microphone permission · speech recognition · AI response · invalid commands · network failure ·
Accessibility disabled · UI element not found · action timeout · confirmation · app launch · typing ·
swipe · scrolling · back · home.
