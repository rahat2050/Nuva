# NUVA v4.2 — “Hey NUVA” and Android default-assistant integration

## What now works

NUVA now registers the complete Android voice-interaction stack:

- `NuvaVoiceInteractionService` — official default digital-assistant entry point;
- `NuvaVoiceInteractionSessionService` / `VoiceInteractionSession` — opens NUVA from the Android
  assistant gesture or configured power-button shortcut and starts listening;
- `NuvaRecognitionService` — a non-recursive bridge to an installed external speech recognizer;
- the existing opt-in `WakeWordService` — visible foreground fallback for the custom phrase
  **“Hey Nuva”**.

When the foreground fallback verifies “Hey Nuva” and NUVA is the user-selected default assistant,
it requests an official assistant session so the full NUVA app opens. If NUVA is not the default,
the existing visible floating popup remains the fallback.

## Setup on the phone

1. Open **NUVA → Settings → Hey NUVA & default assistant**.
2. Tap **Choose NUVA as default**.
3. In Android **Default apps → Digital assistant app / Assist & voice input**, select **NUVA**.
4. Return to NUVA and enable **Wake word “Hey Nuva”**.
5. Grant microphone, notification and “Display over other apps” only when Android asks.
6. Confirm the status says **waiting for wake** and keep the ongoing **NUVA active** notification
   enabled.
7. With the screen on, say **“Hey Nuva”** or **“Hey Nuva, camera kholo”**.

`Restart listener` recovers an OEM-killed/stale recognition session. `Test voice` bypasses only the
wake phrase and verifies the microphone, recognizer and popup path. The Battery button opens Android's
battery-management screen; NUVA never whitelists itself silently.

Selecting NUVA as default is always a visible Android/user decision. The app cannot replace Gemini,
Google Assistant or another default programmatically.

## Reliability fixes

- Added the Android 11+ `android.speech.RecognitionService` package-visibility query. Without this,
  recognizer discovery can incorrectly report unavailable on affected devices.
- Removed a first-enable DataStore race that could make the wake loop exit immediately.
- Added a 12-second ceiling to each wake recognition cycle so a dead OEM recognizer is recreated.
- Added common ASR renderings: `Nova`, `Niva`, `Neva`, `Noova`, `Newva`, `নোভা`, `নিভা`.
- Kept wake matches anchored at the start to reduce false activation.
- Removed spoken “Listening” feedback before command capture so NUVA cannot recognize its own TTS.
- Added live listener state plus explicit restart/test controls.
- The recognizer bridge rejects NUVA itself and deterministically selects only an installed external
  recognizer, preventing recursive binding after NUVA becomes the system assistant. On Android 12+
  it also forwards the caller attribution chain when delegating microphone recognition.

## Honest Android limitation

This is not identical to Google's screen-off “Hey Google” path. Android's low-power DSP detector needs
a device/OEM-enrolled keyphrase sound model. Android's documented software-hotword path requires the
hidden/system-only `CAPTURE_AUDIO_HOTWORD` permission and `AudioSource.HOTWORD`, so an ordinary APK
cannot claim that privileged path.

Therefore NUVA's custom wake fallback:

- is explicitly opt-in;
- works only while the screen is interactive;
- uses a visible ongoing microphone foreground-service notification;
- may be suspended by aggressive OEM battery management;
- never hides the microphone indicator or starts from boot behind the user's back.

The official assistant gesture/power-button route remains available when NUVA is selected as default,
even when custom phrase detection is unsuitable on a particular OEM.

## Privacy

NUVA stores no raw audio and sends no raw audio to NUVA's backend, Groq, Vercel or Supabase. Android's
selected speech-recognition provider may process microphone audio according to that provider's own
privacy terms. Only transcript text accepted as a command enters NUVA's command pipeline. Idle wake
transcripts are checked locally and are not sent to NUVA's backend.

Assistant invocation does not request or retain the foreground app's AssistStructure or screenshot.
All existing action validation, ambiguity stop, confirmation and financial/credential boundaries stay
unchanged.

## Verification status

Pure parser checks, XML parsing and Kotlin syntax checks run in the repository. A full Android Gradle
build and real-device OEM test are still required; behavior is not claimed as device-verified.
