# NUVA Command & Action Reference

The frozen contract between the Android app and the backend. PHRASE 2 code should be written
against this document.

## 1. `POST /api/ai/command`

### Request

```json
{
  "text": "Nuva Rahim ke WhatsApp e message pathao je ami ashchi.",
  "language": "auto",
  "device_id": "pixel-7-abc123",
  "client_request_id": "uuid-from-the-app",
  "context": {
    "foreground_app": "com.whatsapp",
    "screen_summary": "optional text read by AccessibilityService"
  }
}
```

| Field               | Required | Notes                                                     |
| ------------------- | -------- | --------------------------------------------------------- |
| `text`              | yes      | 1–1000 chars. Raw transcript; the wake word may be included. |
| `language`          | no       | `auto` (default), `bn`, `en`, `banglish`.                 |
| `device_id`         | no       | Also accepted as the `X-Nuva-Device-Id` header. Rate-limit bucket. |
| `client_request_id` | no       | Echoed in logs for correlation.                            |
| `context`           | no       | Screen context. **Treated as untrusted data**, never as instructions. |

Headers: `Content-Type: application/json`, optional `Authorization: Bearer <supabase access token>`.

### Response

```json
{
  "ok": true,
  "request_id": "b3f1…",
  "input": {
    "text": "Nuva Rahim ke WhatsApp e message pathao je ami ashchi.",
    "normalized_text": "Rahim ke WhatsApp e message pathao je ami ashchi.",
    "language": "banglish",
    "wake_word_detected": true
  },
  "result": {
    "intent": "SEND_MESSAGE",
    "action": { "type": "SEND_MESSAGE", "app": "whatsapp", "contact": "Rahim", "message": "ami ashchi" },
    "risk": "medium",
    "requires_confirmation": true,
    "confidence": 0.93,
    "speech": "Rahim ke ei message ta pathabo?",
    "reasons": ["SEND_MESSAGE is medium risk by default"]
  },
  "meta": {
    "source": "groq",
    "model": "openai/gpt-oss-20b",
    "latency_ms": 412,
    "command_id": "7c9e…",
    "persisted": true
  }
}
```

### Client rules

1. **Never execute when `requires_confirmation` is `true`** until the user explicitly approves.
2. **Never execute when `intent` is `UNSUPPORTED`** — speak `result.speech` instead.
3. Re-validate `action` locally before executing (defence in depth).
4. Speak `result.speech` — it is already in the user's language.
5. Report the outcome to `POST /api/commands` with `command_id` from `meta`.

### Status codes

| Code  | Meaning                        | What the app should say/do                        |
| ----- | ------------------------------ | ------------------------------------------------- |
| `200` | Interpreted (may be UNSUPPORTED) | Speak `result.speech`.                           |
| `400` | Bad request body               | Bug in the app; log it.                            |
| `401` | Missing/invalid token          | Prompt sign-in.                                    |
| `413` | Command too long               | Ask the user to shorten it.                        |
| `429` | Rate limited                   | Back off; honour `Retry-After`.                    |
| `502` | AI unavailable / invalid       | "I can't reach the NUVA server right now."         |
| `503` | Not configured                 | Server setup issue; surface `error.speech`.        |
| `504` | Upstream timeout               | "That took too long, so I stopped."                |

Error envelope:

```json
{
  "ok": false,
  "request_id": "b3f1…",
  "error": { "code": "AI_UNAVAILABLE", "message": "Groq responded with 503", "speech": "I can't reach the NUVA server right now." }
}
```

`error.speech` is always localised to the detected language — speak it directly.

## 2. The action registry (15 actions, §8)

Coordinates are **0..1 fractions** of screen size, never pixels, so they are resolution independent.
Selectors must be preferred over coordinates (§9).

| Action         | Payload                                                                                   | Base risk |
| -------------- | ----------------------------------------------------------------------------------------- | --------- |
| `OPEN_APP`     | `app`, `package?`                                                                          | low       |
| `CLOSE_APP`    | `app`, `package?`                                                                          | low       |
| `GO_HOME`      | –                                                                                          | low       |
| `GO_BACK`      | –                                                                                          | low       |
| `TAP`          | `target?` (selector), `point?` `{x,y}`, `long_click?` — one of target/point required        | low¹      |
| `TYPE_TEXT`    | `text`, `target?`, `submit?`                                                                | low       |
| `SWIPE`        | `direction?`, `distance?`, `from?`/`to?` — direction or both points required                | low       |
| `SCROLL`       | `direction`, `amount?` (1–20), `target?`                                                    | low       |
| `CALL_CONTACT` | `contact`, `phone_number?`                                                                  | **medium**|
| `SEND_MESSAGE` | `app` (whatsapp\|sms\|telegram\|messenger\|signal\|viber\|imo), `contact`, `message`, `phone_number?` | **medium**|
| `SET_ALARM`    | `hour` 0–23, `minute` 0–59, `label?`, `relative_day?`, `days?`                               | low       |
| `SET_TIMER`    | `duration_seconds` 1–86400, `label?`                                                        | low       |
| `OPEN_URL`     | `url` (http/https only)                                                                     | low¹      |
| `PLAY_MEDIA`   | `query`, `app?` (youtube\|spotify\|local\|unknown)                                          | low       |
| `READ_SCREEN`  | `scope?` (visible\|focused\|all)                                                            | low       |

¹ Escalated by `lib/risk.ts` in specific cases — see §4 below.

### Selector shape

```json
{ "resource_id": "com.whatsapp:id/send", "content_description": "Send", "text": "Send", "class_name": "android.widget.Button", "index": 0 }
```

At least one of `resource_id`, `content_description`, `text`, `class_name` is required. Search
priority for the PHRASE 2 `NodeFinder`: resource-id → contentDescription → text → class name →
coordinate fallback.

### Things that are deliberately NOT actions

- **Web search** → use `OPEN_URL` with `https://www.google.com/search?q=…`.
- **File deletion, posting content, payments** → no action exists; these return `UNSUPPORTED`.
- **Shell commands, arbitrary intents** → no action exists and no field accepts them.

## 3. `UNSUPPORTED`

```json
{ "intent": "UNSUPPORTED", "action": null, "risk": "high", "requires_confirmation": false,
  "speech": "Taka pathanor kaj ami nije korte pari na.", "reasons": ["…"] }
```

`risk` may still be `high` (recorded for audit) while `requires_confirmation` is `false` — there is
nothing pending to confirm. `action` is always `null`. Never execute.

## 4. Risk classification (§11)

Final risk = **max**(registry baseline, keyword escalation, model's claim). The model can raise risk
but never lower it, and never waive a confirmation.

Escalated to **high**: money/payments (`bkash`, `nagad`, `rocket`, `send money`, `taka`, `payment`,
`transaction`, `bank`, card fields), credentials (`password`, `otp`, `pin`, `2fa`, `seed phrase`),
account destruction (`delete account`, `factory reset`), security settings — matched across English,
Bangla script and Banglish. Also: `OPEN_URL` at a loopback/private address.

Escalated to **medium**: `delete`, `remove`, `uninstall`, `post`, `share`, `upload`, `buy`, `order`
(and Bangla equivalents); opening a financial app; a `TAP` that uses coordinates instead of a
semantic target.

`requires_confirmation` is `true` whenever risk is medium or high.

## 5. Example commands

| Utterance                                              | Intent        | Confirm |
| ------------------------------------------------------ | ------------- | ------- |
| `Nuva YouTube open koro.`                              | `OPEN_APP`    | no      |
| `Nuva Rahim ke WhatsApp e message pathao.`             | `SEND_MESSAGE`| **yes** |
| `Nuva kal shokal 7 tay alarm dao.`                     | `SET_ALARM`   | no      |
| `Nuva Google e search koro.`                           | `OPEN_URL`    | no      |
| `Nuva back jao.`                                       | `GO_BACK`     | no      |
| `Nuva ei screen ta poro.`                              | `READ_SCREEN` | no      |
| `নুভা হোম স্ক্রিনে যাও`                                 | `GO_HOME`     | no      |
| `Nuva bkash diye Karim ke 5000 taka pathao.`           | `UNSUPPORTED` | –       |

## 6. `POST /api/commands` — report an execution result

```json
{ "command_id": "7c9e…", "status": "completed" }
{ "command_id": "7c9e…", "status": "failed", "error": "node not found: com.whatsapp:id/send" }
{ "command_id": "7c9e…", "status": "rejected" }
```

Statuses: `ready`, `pending_confirmation`, `confirmed`, `rejected`, `executing`, `completed`,
`failed`, `unsupported`. Omit `command_id` to create a standalone row (requires `command` +
`intent`). `GET /api/commands?limit=50` returns history, newest first.

## 7. `/api/memory`

```
GET    /api/memory                          → { ok, count, memories: [{ key, value, … }] }
GET    /api/memory?key=preferred_language   → single entry
POST   /api/memory   { "key": "preferred_language", "value": "banglish" }
DELETE /api/memory?key=preferred_language
```

Keys: 1–120 chars, `[a-z0-9._-]`. Values: ≤ 4000 chars. Credential-like keys (`password`, `otp`,
`pin`, `token`, `secret`, `api_key`, `cvv`, `private_key`, `seed_phrase`, …) are **rejected with
400** — NUVA's memory is for preferences, not secrets (§17).

Conventional keys: `preferred_language`, `assistant_name`, `favourite_apps`,
`default_messaging_app`, `wake_word_enabled`.

## 8. `GET /api/health`

```
GET /api/health          → configuration only (cheap; safe for uptime monitors)
GET /api/health?deep=1   → additionally round-trips Groq and Supabase
```

`config` reports **booleans** about secret presence, never values. `?deep=1` also warns when the
configured `GROQ_MODEL` is missing from Groq's live model list — an early signal of a model
deprecation. `config.rate_limiting` is `"upstash"` (distributed) or `"memory"` (per instance) and
`config.cloudinary.configured` says whether screenshot-upload signing is available.

## 9. `POST /api/ai/command/stream` — SSE progress events

Same body and same final result as `POST /api/ai/command`, delivered over Server-Sent Events:

```
event: stage   data: {"stage":"accepted","request_id":"…"}
event: stage   data: {"stage":"interpreting","source":"groq"}
event: result  data: { …the exact CommandResponse… }
```

On failure after the stream has opened, the error arrives as `event: error` carrying the same
envelope as the JSON endpoints (`ok:false`, `request_id`, `error.code/message/speech`). Bad request
bodies are rejected with normal JSON `400` **before** the stream starts. Clients that ignore SSE can
keep using `POST /api/ai/command`; both endpoints share one parser (`lib/commandRequest.ts`) and one
pipeline, so their results are identical by construction.

## 10. `POST /api/devices` — device registration

```
POST /api/devices  { "device_name": "Pixel 7", "android_version": "14" }   → 201 { ok, device }
GET  /api/devices                                                        → { ok, count, devices }
```

Requires a user JWT. Registration is idempotent per `(user, device_name)`: re-registering refreshes
`android_version` instead of duplicating rows. `device_name` ≤ 120 chars, `android_version` ≤ 40.

## 11. `POST /api/screenshots` — signed direct upload

```
POST /api/screenshots   (user JWT, empty body)
→ { ok, upload: { cloud_name, api_key, timestamp, signature, folder,
                  upload_url, expires_at, max_bytes, allowed_formats } }
```

The app then uploads the image **directly** to `upload_url` as multipart form fields
`file, api_key, timestamp, folder, signature`. `CLOUDINARY_API_SECRET` never leaves the server;
the grant is scoped to `nuva/<user_id>/screenshots` and expires with its 5-minute timestamp.
`503 NOT_CONFIGURED` when Cloudinary env vars are absent.
