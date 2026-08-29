# NUVA Privacy

Last updated: v4.4.2 (2026-08-29). The app contains **no analytics, no trackers,
no ad SDKs**.

## What stays on your phone

* **Voice audio** — NUVA never stores raw audio or sends it to NUVA backend/Groq/Vercel/Supabase.
  Android's user-selected `SpeechRecognizer` provider converts audio to text under that provider's
  own privacy terms; only the resulting transcript enters NUVA's command pipeline. Idle wake-loop
  transcripts are matched locally and are not sent to NUVA's backend.
* Command history, notes, to-dos, memories and settings use local Room/DataStore as their source of
  truth. Optional signed-in sync can copy allowed command/memory records to Supabase; credential-bearing
  utterances/values stop before local history, Groq and sync, and legacy sensitive rows are hidden.
* Scheduled email/SMS **draft reminders** (recipient, subject/body, trigger and recurrence) — local Room only;
  AlarmManager carries only the local row id. They are never uploaded or automatically sent.
* Clipboard access happens only on an explicit foreground copy/read/clear command; NUVA stores no
  clipboard history and never registers a clipboard-change monitor.
* Text explicitly shared/selected into NUVA becomes a transient editable draft capped at 1,000
  characters. It is never auto-submitted, is removed from the Activity Intent after consumption,
  and credential/transaction text is refused before import.
* Rich calendar event fields are passed to the visible Calendar insert screen and not saved by NUVA.
  Optional agenda access queries only an explicitly requested 1–31 day range; results stay transient,
  are never uploaded/synced, credential-titled events are excluded, and credential/code-like event
  locations are omitted or redacted before display.
* Social/MMS text and a picker-selected attachment go only to the visible Android compose/share intent
  chosen by the user; NUVA does not upload them to its backend or press Post/Send.
* Maps routes contain only the places the user dictated. NUVA requests no location permission and
  does not read current coordinates; the selected Maps app manages its own location access.
* Device diagnostics stay local and omit IMEI, serial, Android ID, ad ID, SIM identity, MAC, account
  list, exact IP/location and other persistent identifiers.
* SOS drafts never auto-append current location; the user chooses text, recipient and final Send.
* Home Assistant URL/token stay on-device. The token is AES-GCM encrypted by Android Keystore and is
  sent only as a Bearer header directly to the user-configured HTTPS Home Assistant origin—not to
  NUVA backend, Groq or Supabase.
* Optional Supabase access/refresh JWTs are AES-GCM encrypted with a separate Android Keystore key.
  Legacy plaintext session values are rejected and cleared; the user signs in again. Backend and
  Supabase custom endpoints are HTTPS-only before any password, JWT or command can be sent.
* Screen snapshots (`ScreenStateModel`) — transient, in RAM, never persisted or
  uploaded. Password fields are never captured; OTP-like codes are redacted even
  in memory before display.
* Contacts are read only to resolve a name you spoke; nothing is copied out.

## What is sent, and where

| Data | Destination | When |
|------|-------------|------|
| Command text | Vercel backend → Groq (AI) | ONLY when the on-device parser cannot understand it and no credential/financial-transaction marker is present |
| Command record (text, intent, status) | Supabase | ONLY if signed in; credential-bearing commands are never persisted |
| Device id header | Vercel | with AI requests (rate limiting) |

The Groq key lives only on the server; the APK holds just the backend URL and
the public anon key.

## What never happens

* OTP / PIN / password / CVV / card numbers are never read, stored, typed or
  sent (policy LEVEL 2). Banking screens are never read.
* Financial transactions are never automated (LEVEL 3).
* No hidden recording: active command listening has visible UI; optional “Hey Nuva” waiting always
  has a visible ongoing microphone foreground-service notification and pauses while the screen is off.
* No raw microphone stream ever goes to NUVA backend, Groq, Vercel or Supabase.

## In-app privacy screen

Settings → প্রাইভেসি shows the same facts in Bangla.
