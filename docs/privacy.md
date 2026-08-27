# NUVA Privacy

Last updated: v2.6 (2026-08-27). The app contains **no analytics, no trackers,
no ad SDKs**.

## What stays on your phone

* **Voice audio** — never uploaded. Android SpeechRecognizer transcribes on
  device/service; only the resulting TEXT is used.
* Command history, notes, to-dos, memories, settings — local Room/DataStore only.
* Scheduled email/SMS **draft reminders** (recipient, subject/body, trigger and recurrence) — local Room only;
  AlarmManager carries only the local row id. They are never uploaded or automatically sent.
* Screen snapshots (`ScreenStateModel`) — transient, in RAM, never persisted or
  uploaded. Password fields are never captured; OTP-like codes are redacted even
  in memory before display.
* Contacts are read only to resolve a name you spoke; nothing is copied out.

## What is sent, and where

| Data | Destination | When |
|------|-------------|------|
| Command text | Vercel backend → Groq (AI) | ONLY when the on-device parser cannot understand it |
| Command record (text, intent, status) | Supabase | ONLY if you signed in (optional memory sync) |
| Device id header | Vercel | with AI requests (rate limiting) |

The Groq key lives only on the server; the APK holds just the backend URL and
the public anon key.

## What never happens

* OTP / PIN / password / CVV / card numbers are never read, stored, typed or
  sent (policy LEVEL 2). Banking screens are never read.
* Financial transactions are never automated (LEVEL 3).
* No hidden recording: the microphone runs only in a foreground session with a
  visible notification, and only while the listening indicator is active.
* No raw microphone stream ever goes to Groq/Vercel.

## In-app privacy screen

Settings → প্রাইভেসি shows the same facts in Bangla.
