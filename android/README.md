# NUVA Android — PHASE 2

The NUVA voice assistant app: **Kotlin + Jetpack Compose + AccessibilityService**. It listens
(Bangla / Banglish / English), sends the transcript to the Vercel backend, re-validates the
returned action **locally**, asks before anything risky, executes on the device, and speaks the
result back.

> Phase gate: the app is written against the frozen contract in [`../docs/commands.md`](../docs/commands.md).
> Point it at a deployed backend (Settings → Backend base URL) before using it for real.

## Stack

| Layer            | Choice                                            |
| ---------------- | ------------------------------------------------- |
| Language         | Kotlin 2.0.20, JDK 17 target                      |
| UI               | Jetpack Compose (BOM 2024.09), Material 3         |
| Navigation       | androidx.navigation-compose                       |
| Local DB         | Room 2.6 (history, memory, pending actions)       |
| Preferences      | DataStore                                         |
| Network          | Retrofit + OkHttp + kotlinx-serialization         |
| Voice in         | `android.speech.SpeechRecognizer` (bn-BD / en-US) |
| Voice out        | `android.speech.tts.TextToSpeech`                 |
| Automation       | `AccessibilityService` + platform intents         |
| Sync             | Supabase REST (GoTrue JWT) → Vercel API           |

## Module map

```
com.nuva.assistant/
├── MainActivity / NuvaApplication
├── core/          NuvaContainer (DI), DeviceId, constants, SecurityPolicy
├── ai/            NuvaApi (Retrofit), AIRepository (+SSE), ActionParser, PromptManager
├── command/       Intent/Action registry mirror (15 AI + 8 LOCAL-ONLY intents),
│                  CommandValidator (LOCAL re-validation), CommandParser v2
│                  (on-device bn/banglish/en), NuvaDateTimeParser, CommandExecutor, ActionJson
├── voice/         SpeechRecognizerController, TTSManager, VoiceController (typed fallback hook)
├── accessibility/ NuvaAccessibilityService (+ sensitive-screen guard), NodeFinder,
│                  Tap/Text/Swipe controllers, ScreenReader (password/OTP redaction)
├── automation/    AppLauncher v2 (installed-app lookup + Play Store suggestion),
│                  GenericAutomation, WhatsApp/YouTube/Browser flows, SMS, SettingsOpener
│                  (+ torch), DeviceStatusProvider, ReminderOpener, MessagingRegistry
├── contacts/      ContactResolver (name → number, multi-match)
├── database/      Room: CommandHistory (+failure reason), PendingAction, LocalMemory, Notes
├── memory/        MemoryManager (local-first), UserPreferences (DataStore)
├── supabase/      SupabaseRepository (REST + auth), SyncManager
├── service/       NuvaForegroundService (visible mic session), WakeWordService (opt-in),
│                  NuvaNotificationListener (read-only, OTP-redacting)
├── ui/floating/   Small overlay popup with listen/process/confirm/success/error states
├── ui/            Home (voice + typed fallback + confirm/choice dialogs), History (+retry),
│                  Memory (notes & to-dos), Settings (health check, TTS test, status)
├── ui/onboarding/ Bangla permission onboarding (request-in-context, denied→help)
└── ui/support/    Supported / unsupported feature list (honest, with reasons)
```

## Build & run

1. Open the **`android/`** folder in Android Studio (Koala or newer, AGP 8.7.3 / Gradle 8.9).
   The wrapper JAR is not committed — Android Studio regenerates it on first sync, or run
   `gradle wrapper --gradle-version 8.9` once if you prefer the CLI.
2. `./gradlew :app:assembleDebug` (or the Run ▶ button).
3. Unit tests (pure JVM, no emulator): `./gradlew :app:testDebugUnitTest`

```bash
./gradlew lint           # Android lint
./gradlew test           # unit tests
./gradlew assembleDebug  # debug APK

# Dependency-free preflight (also works when Gradle is unavailable)
python3 tools/parser_mirror_check.py
python3 tools/android_contract_check.py
```

For constrained environments with a standalone Kotlin compiler + Android 35 API jar, run
`tools/focused_android_api_compile_check.py`; see
[`../docs/v4.2-apk-build-qa.md`](../docs/v4.2-apk-build-qa.md). This focused check supplements—it does
not replace—the Gradle build, lint or physical-device matrix.

## First-run setup on a device

1. **Backend URL** — defaults to `https://nuvaa.vercel.app/` (verified with `/api/health?deep=1`).
   For local backend testing on an emulator use `http://10.0.2.2:3000/`. Tap Save — it checks `/api/health`.
2. **Microphone + notification** — grant when prompted. A visible foreground notification is shown
   whenever NUVA is listening or waiting for the wake phrase.
3. **Default assistant** — Settings → Hey NUVA & default assistant → **Choose NUVA as default**,
   then select NUVA in Android's visible Digital assistant app picker.
4. **Floating popup** — enable **Display over other apps** when Android asks. It is used as the
   fallback when an OEM does not route the official assistant surface.
5. **Accessibility** — Settings → Accessibility → NUVA Automation → On. This is required for
   tap/type/swipe/scroll/read-screen; app open, alarms, timers, dialer and browser work without it.
6. Toggle **Wake word “Hey Nuva”**. Confirm `waiting for wake`, leave the app, keep the screen on and
   say “Hey Nuva”. `Restart listener` and `Test voice` diagnose stale OEM recognizers.
7. Optional: Supabase URL + anon key + sign-in to enable cloud memory/history sync.

## The safety model (client half)

- Only the **15 registered actions** exist as executable types; `UNSUPPORTED` is spoken, never run.
- Every server response is **re-validated locally** (`CommandValidator`) — the server is not the
  only gate.
- Risk is recomputed on-device; the model can raise it, never lower it; medium/high ⇒ a **blocking
  confirmation dialog** (there is no setting that disables it).
- Offline, a small deterministic parser handles `GO_HOME`, `GO_BACK`, simple `OPEN_APP`, and
  minute-based `SET_TIMER` — everything else honestly says it needs the server.
- No secret ever ships in the APK: the app holds only the backend URL and the public Supabase
  anon key.
- Pending actions are persisted and re-validated on decode, so nothing stale can execute.

## Wake word and default-assistant status

v4.2 adds Android's official `VoiceInteractionService`/session/recognizer contract, so the user can
select NUVA as the default digital assistant and invoke it with the device's configured assistant
gesture or power shortcut. `WakeWordService` remains an opt-in foreground fallback for the custom
“Hey Nuva” phrase: its microphone notification is always visible, it runs only while the screen is
interactive and it promotes verified wakes to the official NUVA surface when available.

Idle/wake transcripts are checked locally and never sent to NUVA backend/Groq. Raw audio is not
stored or sent to NUVA backend; the selected Android speech provider may process it under that
provider's terms. A normal APK cannot use Google's/OEM's screen-off DSP model or hidden
`CAPTURE_AUDIO_HOTWORD` path. See [`../docs/hey-nuva-system-assistant.md`](../docs/hey-nuva-system-assistant.md).

## Test matrix (§2.19 essentials)

| Area         | Check                                                                |
| ------------ | -------------------------------------------------------------------- |
| Voice        | bn / banglish / en utterances; empty; noisy; permission denied       |
| Network      | airplane mode → offline parser or clear error; timeout               |
| AI           | UNSUPPORTED reply is spoken, not executed                            |
| Confirmation | SEND_MESSAGE blocks until Yes/No; rejected ⇒ nothing sent            |
| Accessibility| disabled ⇒ actionable error message with Settings shortcut           |
| Automation   | open YouTube; WhatsApp send with number; scroll; read screen         |
| Persistence  | history rows for executed/failed/rejected; pending expiry           |
| Security     | credential-like memory keys refused; javascript: URL refused        |


## v1.1 — practical assistant pass

Highlights (see `../docs/roadmap-v1.1.md` for the full audit + plan):

* **On-device parser v2** — Bangla/Banglish/English with Bangla numerals; runs BEFORE the
  network AI (privacy, speed, offline) and as a rescue path when the server says unsupported.
* **LOCAL-ONLY intents** (`NuvaIntent.localOnly`): recents, web search, device status,
  settings/torch, notification summary, reminder, note/to-do/list reads, media/device control and
  deterministic calculated answers. `NuvaIntent.fromWire()` refuses them, so **no server response
  can ever trigger one** — the executable AI registry stays frozen at 15.
* **Three-level financial policy** (`core/security/SensitiveAppPolicy`, v1.2):
  LEVEL 1 — wallet/bank apps can be launched by voice and navigated (scroll);
  LEVEL 2 — OTP/PIN/password/card data is never read, stored or typed (password
  fields skipped, OTP-like codes redacted, screen reading disabled inside
  financial apps); LEVEL 3 — transaction automation (send money, payment, cash
  out, transfer, recharge, payment confirmation) is always refused with a fixed
  Bangla message and never offered a confirmation.
* **Contacts + calls/messages**: contact-name resolution with an explicit multi-match choice;
  SMS sends only after confirmation (compose-screen fallback); WhatsApp unchanged; other
  messaging apps clearly refused with reasons.
* **Phone utilities**: battery/time/date/network/storage answers, torch toggle, direct
  volume up/down/mute, media pause/resume/next/previous (active MediaSession), camera
  photo/video/capture-open, settings screens, calendar-prefilled reminders, local
  notes & to-dos.
* **Messaging tiers**: WhatsApp + SMS send after confirmation; Telegram/Messenger/
  Signal/Viber/IMO open with the message pre-filled (user taps Send).
* **v1.8–v2.0 daily utility packs**: 1,000+ offline calculation/conversion command forms,
  advanced study/travel/health/budget formulas, shopping/expense list read-back, 100 broad sourced
  skills, and exactly 500 precise entity×task skills for local services, public services, learning
  and product help.
* **v2.1 natural command grammar**: 50 families × 5 aliases × 7 prefixes × 7 suffixes =
  **12,250 audited forms**, conservative dynamic-command rewrites, typo-aware security checks and
  up to six validated multi-step segments.
* **v2.2 user-present files/media**: Storage Access Framework picker for selected file open/share,
  bounded text read and folder grant; selected photo/video view/share via Android picker and share
  sheet. No broad storage permission or guessed path.
* **v2.3 user-reviewed communication**: validated email draft compose (`ACTION_SENDTO`, user taps
  Send) and confirmed notification replies only through an app-provided free-form RemoteInput action.
* **v2.4 forms/productivity**: one picker-selected email attachment, local-only form/booking drafts
  with sourced portal handoff, and one-shot email/SMS compose reminders that open drafts on tap.
* **v2.5 target-aware files**: selected-file rename/delete and source+destination copy/move with a
  second target-aware confirmation; selected-photo `ACTION_EDIT` handoff with user-controlled Save.
* **v2.6 persistent drafts**: Room-backed once/daily/weekly email/SMS compose reminders, pending-list
  and confirmed cancellation commands, plus BOOT_COMPLETED/app-update restoration.
* **v2.7 safe handoffs**: confirmed generic text share, Contacts-app insert drafts, and single safe
  notification dismiss/exact allowlisted Mark-as-read actions; no bulk or guessed action.
* **v2.8 multi/system handoffs**: max-10 file/photo/video sharing and email attachments, exact contact
  picker view/edit, and Android-confirmed uninstall for dynamically resolved non-financial apps.
* **v2.9 settings/app management**: 16 additional official settings screens/panels plus dynamically
  resolved App Info, app-notification and Play Store pages; no secure setting bypass.
* **v3.0 explicit productivity**: confirmation-gated bounded clipboard copy/read/clear with no
  monitoring, and rich Calendar insert drafts with user-controlled final Save.
* **v3.1 visible communication**: text-only social compose handoffs, MMS/message compose with one
  picker attachment, and voicemail dialer; final Post/Send/call always user-controlled.
* **v3.2 maps/navigation**: dynamic origin/destination, route modes, nearby search and coordinate
  Street View through visible native/web map handoffs; NUVA reads no device location.
* **v3.3 diagnostics**: local device/Android/RAM/uptime/display/audio/timezone/locale/app-count/sensor
  answers with no IMEI/serial/Android-ID/MAC/location or additional permission.
* **v3.4 emergency handoffs**: Bangladesh 999 dialer, confirmed SOS share draft and emergency-info
  settings; no automatic call/message or location collection.
* **v3.5 media/audio**: official MediaSession stop and bounded seek, plus exact 0–100 media volume
  and mute/unmute through AudioManager; OEM safe-volume rules remain authoritative.
* **v3.6 clock management**: official AlarmClock list/snooze/dismiss requests with blocking
  confirmation for active changes and honest Clock-app fallback on unsupported OEMs.
* **v3.7 print/calendar**: one picker-selected PDF streamed to Android print preview and Calendar
  date/agenda views without READ_CALENDAR; final print/save remains user-controlled.
* **v4.0 Home Assistant**: HTTPS-only config, Android-Keystore-encrypted token, exact entity matching
  and confirmed light/switch/fan/climate allowlist; locks/security/high-consequence domains excluded.
* **v4.1 Calendar provider**: optional bounded agenda reads and exact title-matched event view/edit
  handoff; credential events excluded, ambiguity stops, no WRITE_CALENDAR/direct delete.
* **v4.2 system assistant**: complete default-assistant registration, gesture/power invocation,
  visible custom wake fallback, recognizer-discovery/race/timeout fixes and live diagnostics.
* **UX**: typed command fallback (offered automatically when recognition fails), rich Bangla
  confirmation dialogs (target/content/app/risk), history failure reasons + retry, permission
  onboarding in Bangla, supported/unsupported feature screen.

Unit tests cover the parser, date/time parser, validator, denylist, ActionJson round-trip and
confirmation summaries: `./gradlew :app:testDebugUnitTest`.
