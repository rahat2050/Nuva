# NUVA v1.1 Audit & Roadmap — practical voice-first assistant

Audit date: 2026-08-25 · Scope: `android/` (PHASE 2 app), backend contract untouched.

## 1. Audit summary (what exists, what is missing)

The PHASE 2 skeleton is solid: 15-action frozen AI registry (client mirror + local
re-validation), CommandExecutor pipeline with a blocking confirmation gate, offline
fallback parser, AccessibilityService with semantic node finding, WhatsApp flow,
voice I/O, History/Memory/Settings screens and a `workflow_dispatch` APK workflow.

Gaps found against the "practical voice-first assistant" brief:

| # | Area | Status before this pass | Gap |
| - | ---- | ----------------------- | --- |
| 1 | Security denylist (banking/payment) | partial (server keyword risk only) | **No client-side sensitive-app/package denylist, no runtime foreground-screen guard, no OTP/PIN redaction** |
| 2 | Voice commands bn/banglish/en | basic | Offline parser only knew home/back/timer/4 app aliases; no call/SMS/YouTube/search/status parsing |
| 3 | Typed fallback | ✅ pipeline exists (`submit`) | **No text input on Home screen**, no auto-offer when recognition fails |
| 4 | Command status UX | basic | No failure reason in history, no retry, confirmation dialog showed raw intent names |
| 5 | App open | map of ~19 packages only | **No installed-app lookup by label**, no Play Store suggestion when missing |
| 6 | Navigation | home/back only | No recents |
| 7 | Contacts/calls/messages | dial-with-number only | **No contact search/multi-match/number resolution**; SMS compose only; Telegram/Messenger etc. silently unsupported |
| 8 | Phone utilities | alarm/timer only | No reminder/calendar, note, to-do, battery/date/network/storage answers, torch/brightness/volume/DND/Wi-Fi/BT, notification read |
| 9 | Date/time parsing | none locally | No Bangla numerals, `shokal 7tay`, `kal`, `parso`, `আধা ঘণ্টা` support |
| 10 | Permissions UX | runtime mic only | No onboarding screen, no Bangla explanation of *why*, no graceful-denied help |
| 11 | Feature transparency | none | No supported/unsupported feature list in the app |
| 12 | CI | manual dispatch only | No unit-test step, no automatic APK build on push/PR |

## 2. Non-negotiable constraints preserved

* **AI never controls Android** — the 15-action AI registry stays frozen; every action is
  re-validated locally before execution (docs/security.md unchanged).
* **New capabilities are LOCAL-ONLY actions** (§3 below): they can be produced by the
  offline parser and typed input, but `NuvaIntent.fromWire()` deliberately does **not**
  resolve them, so no server/AI response can ever trigger one.
* **Confirmation gate untouched** — medium/high risk still blocks; call/message/reminder
  always confirm; there is still no way to disable risky confirmations.
* **Strict exclusions** — banking/payment automation, OTP/PIN/password handling and
  screen automation on sensitive apps are refused client-side by
  `core/security/SensitiveAppPolicy` even if everything else fails open.

## 3. v1.1 implementation plan (this pass)

| Priority | Deliverable | Where |
| -------- | ----------- | ----- |
| P0 | Sensitive-app denylist + runtime screen guard + OTP/PIN redaction + money-transfer refusal | `core/security/SensitiveAppPolicy.kt`, `accessibility/*`, `CommandExecutor` |
| P0 | Local-only action set: SHOW_RECENTS, SEARCH_WEB, DEVICE_STATUS, OPEN_SETTING, READ_NOTIFICATIONS, SET_REMINDER, CREATE_NOTE, CREATE_TODO | `command/Intent.kt`, `command/Action.kt`, `command/CommandValidator.kt`, `command/ActionJson.kt` |
| P0 | Offline parser v2 — Bangla/Banglish/English, Bangla numerals, call/SMS/WhatsApp/search/YouTube/status/settings/note/todo/reminder patterns | `command/CommandParser.kt` |
| P0 | Date/time parser (bn+banglish+en) for alarms/timers/reminders | `command/NuvaDateTimeParser.kt` |
| P1 | Installed-app lookup by label + Play Store suggestion | `automation/AppLauncher.kt` |
| P1 | Contact search, multi-match selection, number resolution | `contacts/ContactResolver.kt`, executor, Home UI |
| P1 | SMS send-after-confirmation (SmsManager) with compose fallback | `automation/SmsAutomation.kt` |
| P1 | Messaging plugin registry — WhatsApp + SMS supported, others refused with a clear Bangla reason | `automation/MessagingRegistry.kt` |
| P1 | Device status answers (battery/time/date/network/storage), settings screens + torch, reminder via calendar prefill, notes & to-dos (Room v2) | `automation/DeviceStatusProvider.kt`, `automation/SettingsOpener.kt`, `automation/TorchController.kt`, `automation/ReminderOpener.kt`, `database/*` |
| P1 | Notification reader service + OTP-safe summary (reply = explicitly unsupported) | `service/NuvaNotificationListener.kt` |
| P1 | Permission onboarding (Bangla explanations, request-in-context, denied→help) | `ui/onboarding/OnboardingScreen.kt` |
| P1 | Home UX: typed fallback, processing/success/failure states, accessibility setup guide, contact-choice + rich confirmation dialogs | `ui/home/HomeScreen.kt`, `ui/ConfirmationSummary.kt` |
| P2 | History: failure reason + retry; Settings: health check, TTS test, accessibility status; supported/unsupported feature screen | `ui/history`, `ui/settings`, `ui/support` |
| P2 | CI: unit tests + assembleDebug on push/PR + dispatch, artifact upload | `.github/workflows/build-apk.yml` |
| P2 | Unit tests for parser, date/time parser, validator, denylist, confirmation summary, ActionJson round-trip | `app/src/test/*` |

## 4. Explicitly NOT implemented (policy)

* Any automation inside banking / mobile-wallet / payment apps (bKash, Nagad, Rocket,
  Upay, bank apps) — refused before execution, including opening them by voice.
* Reading, storing, typing or sending OTP / PIN / password / CVV / card numbers /
  biometric data — password fields are skipped by the screen reader and OTP-like codes
  are redacted from every spoken/shown summary.
* Notification **reply** — needs per-app RemoteInput integration that cannot be made
  reliable today; NUVA says so clearly instead of pretending.
* Blind "all apps" automation — automation only targets semantic selectors found on
  screen; if the UI changed and the target is gone, NUVA reports a safe failure instead
  of tapping around.
* Data exfiltration / hidden recording — microphone only runs in a foreground session
  with a visible notification; screen text is only read on an explicit command.

## 5. Later (needs design)

* Supabase memory sync of notes/to-dos (local-first for now).
* Wake-word always-on on-device model.
* Reply flows for notifications behind per-app plugins.
* Multi-user / work-profile support.


---

# v1.2 — Three-level financial policy + maximum safe phone control

Updated product spec (2026-08-26): financial apps are NOT blanket-blocked any more.
The denylist concept was replaced by explicit levels, enforced locally:

| Level | Scope | Behaviour |
| ----- | ----- | --------- |
| **1 — Normal access** | Launch wallet/bank apps by voice ("bKash kholo"), home/back/recents, scrolling, normal navigation | **Allowed.** The user is never blocked from their own apps. |
| **2 — Sensitive information** | OTP, PIN, password, CVV, card number, auth codes, banking credentials, biometrics | **Never read/stored/typed.** Password fields skipped, OTP-like codes redacted everywhere, screen reading disabled while a financial app is foreground (fail-safe: a "public" screen cannot be reliably distinguished from a PIN/OTP screen). |
| **3 — Financial transactions** | Send/receive money, cash out, bank transfer, payment, purchase, card transaction, recharge, payment confirmation, financial authorization | **Automation always refused** — before parsing, with the exact message "এই financial transaction NUVA নিজে করতে পারবে না। আপনি চাইলে নিজে manually করতে পারবেন।" No confirmation is ever offered. Tap/long-press/type automation is blocked inside financial apps because a tap is how a transaction gets confirmed. |

Transaction detection matches ACTIONS ("taka pathao", "cash out", "card diye payment",
"bank transfer", "recharge koro", …), never bare app names — so "bkash kholo" is LEVEL 1
while "bkash diye taka pathao" is LEVEL 3.

## v1.2 additions

* **Media transport control** — pause/resume/next/previous through the active
  MediaSession, discovered from the media notification (official route).
* **Direct volume control** — up/down/mute via AudioManager ("volume barao").
* **Camera** — open photo/video modes; "chobi tolo" opens the capture flow on an
  explicit command; the shutter always stays in the user's hands.
* **Messaging tiers** — FULL (WhatsApp sends after confirmation, SMS after
  confirmation) and COMPOSE (Telegram, Messenger, Signal, Viber, IMO open with
  the message pre-filled via share intent; the user taps Send).
* Financial apps get package hints + Bangla aliases so voice launch resolves
  directly when installed.

## Still NOT implemented (honest limitations)

* Notification replies (per-app RemoteInput integration needed).
* File-content search / gallery media search (storage permission model varies).
* Blind all-apps automation — semantic targets only, safe failure when the UI
  changed.
