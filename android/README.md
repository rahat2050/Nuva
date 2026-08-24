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
├── command/       Intent/Action registry mirror, CommandValidator (LOCAL re-validation),
│                  CommandParser (offline fallback), CommandExecutor, ActionJson
├── voice/         SpeechRecognizerController, TTSManager, VoiceController
├── accessibility/ NuvaAccessibilityService, NodeFinder, Tap/Text/Swipe controllers, ScreenReader
├── automation/    AppLauncher, GenericAutomation, WhatsApp/YouTube/Browser flows
├── database/      Room: CommandHistory, PendingAction, LocalMemory
├── memory/        MemoryManager (local-first), UserPreferences (DataStore)
├── supabase/      SupabaseRepository (REST + auth), SyncManager
├── service/       NuvaForegroundService (visible mic session), WakeWordService (opt-in fallback)
├── ui/floating/   Small overlay popup with listen/process/confirm/success/error states
└── ui/            Home (voice + confirm dialog), History, Memory, Settings
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
```

## First-run setup on a device

1. **Backend URL** — Settings → Backend base URL. For the local backend on an emulator use
   `http://10.0.2.2:3000/`; for production use your Vercel URL. Tap Save — it checks `/api/health`.
2. **Microphone + notification** — grant when prompted. A visible foreground notification is shown
   whenever NUVA is listening or waiting for the wake phrase.
3. **Floating popup** — Settings → System assistant mode → enable **Display over other apps** when
   Android asks. The overlay is shown only after activation and auto-dismisses after results.
4. **Accessibility** — Settings → Accessibility → NUVA Automation → On. This is required for
   tap/type/swipe/scroll/read-screen; app open, alarms, timers, dialer and browser work without it.
5. Toggle **Wake word “Hey Nuva”** in NUVA Settings. Leave the app, use another app, say “Hey Nuva”,
   then speak the command in the floating popup.
6. Optional: Supabase URL + anon key + sign-in to enable cloud memory/history sync.

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

## Wake word status

`WakeWordService` now provides the Android-compliant fallback for “Hey Nuva”: an explicit Settings
toggle starts a visible foreground microphone service, checks wake-loop transcripts locally, then
shows a small overlay popup for the actual command. NUVA does **not** send idle/wake-loop audio or
transcripts to the backend/Groq. The fallback uses Android's `SpeechRecognizer`, so the device's
recognizer policy still applies; for strict offline hotwording, a future DSP/on-device keyword engine
can replace it without changing the command engine.

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
