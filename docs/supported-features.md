# NUVA — Supported features (v4.4)

Statuses: **SUPPORTED** (works) · **PARTIAL** (built, piece missing/awaiting device QA) ·
**ANDROID-LIMITED** (only as far as Android permits) · **UNSUPPORTED** (deliberate, stated in-app) ·
**BLOCKED** (policy refusal)

## Voice & conversation — SUPPORTED / ANDROID-LIMITED
v4.2 registers NUVA as a user-selectable Android default digital assistant: the assistant gesture or
configured power shortcut opens NUVA and starts listening. The opt-in foreground “Hey Nuva” fallback
uses a visible ongoing microphone notification, works while the screen is interactive, opens the full
app when NUVA is selected as default, and retains one wake = command + ≤2 follow-ups. Android 11+
recognizer discovery, first-enable race recovery, 12-second wake-cycle recovery, ASR name variants and
self-TTS rejection are included. Google's screen-off low-power DSP hotword path requires OEM/system
keyphrase support unavailable to an ordinary APK. Bangla/Banglish/English/mixed parsing, typed fallback,
Bangla-first replies, 15-second command timeout recovery and 5-minute conversational context remain.
Details: [`hey-nuva-system-assistant.md`](hey-nuva-system-assistant.md).

## 3D interface & accessibility — SUPPORTED / DEVICE QA PENDING
v4.3 adds a native Compose aurora backdrop, raised glass panels, static 3D command orb, floating
four-route navigation, state chips and a bottom-center assistant voice plate. Home, History and Memory
each use one bounded lazy list, avoiding nested scrolling/clipping on small displays. Custom primary
actions retain 50dp+ targets, the voice orb exposes button semantics, navigation keeps text labels and
no decorative infinite animation is used. Light/dark system-bar icon contrast is synchronized. Full
Gradle, screenshot, TalkBack and physical-device contrast QA remain pending. Details:
[`3d-ui-ux.md`](3d-ui-ux.md).

## Quick access & selected-text handoff — USER-PRESENT SUPPORTED
v4.4 adds a user-installed **Talk to NUVA** Quick Settings tile, launcher long-press shortcut and
Android `text/plain` Share/Process Text target. Tile/shortcut only open one visible listening session.
Selected/shared text becomes a transient editable draft capped at 1,000 characters; it is never
auto-submitted. Credential-like and financial-transaction text is refused before import, and the
Activity Intent payload is cleared after consumption. Details:
[`quick-access-text-handoff.md`](quick-access-text-handoff.md).

## Natural command grammar — SUPPORTED
A data-audited grammar accepts **12,250 concrete static command forms** (50 families × 5 aliases ×
7 prefixes × 7 suffixes), plus dynamic apps/contacts/messages/queries/times and 600 sourced skills.
Unknown commands get one conservative ASR/politeness canonicalization retry through the same typed
parser; security is re-run on canonical text. Multi-step plans support more connectors and up to six
segments. Details: [`10000-command-grammar.md`](10000-command-grammar.md).

## Home Assistant / IoT — CONFIGURABLE SUPPORTED
v4.0 supports allowlisted Home Assistant `light`, `switch`, `fan` and `climate` entities. Commands
resolve dynamic friendly names/entity IDs, stop on ambiguity, require blocking confirmation and call
only `turn_on`, `turn_off`, `toggle`, or climate `set_temperature` (10–32°C). Configuration requires
an HTTPS origin; the long-lived token is AES-GCM encrypted with Android Keystore, stays on-device and
is never logged/sent to NUVA backend. Locks, covers/garage, cameras, alarms/security, gas/water,
medical and arbitrary domains/services remain refused.

## Apps & navigation — SUPPORTED
Open any installed app by name (+ Play Store suggestion when missing), close/home/back/recents,
scroll/swipe anywhere, notification shade open, per-app capability truth via AppCapabilityRegistry.
v2.8 can open Android's system uninstall confirmation for one dynamically resolved non-financial
app; financial apps and NUVA itself are refused, and Android owns the final decision.

## Communication — SUPPORTED (confirmation mandatory)
Contacts: dynamic resolution, kinship fallback, multi-match asks, phone numbers incl.
hyphenated. Calls (dialer or opt-in direct). SMS send-after-confirmation. WhatsApp
send-after-confirmation with package + recipient verification. Chat open (wa.me).
Telegram/Messenger/Signal/Viber/IMO — **ANDROID-LIMITED**: message pre-filled, user taps Send.
Email recipient/subject/body compose is **SUPPORTED user-reviewed**: email app opens and user taps Send.
v2.7 adds confirmed generic text-share chooser and Contacts-app insert drafts (name/phone/email); the
user chooses the destination/final share or taps final Save. v2.8 adds contact picker view/edit
handoff without contact-write permission; exact contact and final Save remain user-controlled.
v3.1 adds text-only social compose handoffs for Facebook/Instagram/X/LinkedIn/Reddit/Threads/TikTok,
MMS/message compose with one picker-selected attachment, and voicemail dialer handoff. Social/MMS
content is rechecked; final Post/Send/call remains user-controlled and bulk/background use is absent.

## Forms & productivity — USER-REVIEWED SUPPORTED
v2.4 can store an explicitly dictated local draft and open a sourced official-portal search for
passport, NID, birth registration, driving licence, visa, admission, job, doctor, hotel, flight and
courier forms. Personal details are never placed in the web query; final form entry/upload/Submit is
user-controlled. Email can include one picker-selected attachment. Email/SMS compose reminders
persist in Room, support once/daily/weekly schedules, restore after reboot/app update, and can be
listed/cancelled by voice. Tapping a notification opens a prefilled draft and never auto-sends.
Notification permission is required. Details: [`persistent-scheduled-drafts.md`](persistent-scheduled-drafts.md).

## Clipboard & rich calendar — EXPLICIT/USER-REVIEWED SUPPORTED
v3.0 supports explicit foreground clipboard copy/read/clear with blocking confirmation, bounded text,
OTP-like redaction and runtime credential/financial checks. There is no clipboard monitoring/history.
Rich Calendar insert drafts support title, begin/end duration, location, description and one attendee
email; the visible Calendar app owns final Save. Date views need no permission. v4.1 adds optional,
explicit `READ_CALENDAR` access for bounded 1–31 day agenda summaries and exact title-matched event
view/edit handoff. Credential-titled events are excluded, ambiguity stops, no calendar data is synced
or uploaded, and edit opens the visible Calendar app for final Save. Direct delete/write remains absent.

## Media & camera — SUPPORTED / ANDROID-LIMITED
YouTube search/play, Spotify fallback, play/pause/next/previous/stop and bounded 1–300 second
forward/rewind through the active MediaSession (ANDROID-LIMITED via notification access). Media
volume supports up/down/mute/unmute and exact 0–100%; Android safe-volume/OEM rules still win. Camera open photo/video +
explicit-capture flow (shutter always user-controlled). User-present Android pickers support
photo/video selection, viewing and share-sheet handoff. v2.5 adds selected-photo `ACTION_EDIT`
handoff; the installed editor remains visible and final Save is user-controlled. Gallery-wide search,
automatic editing and background deletion remain **UNSUPPORTED**.

## Files — TARGET-AWARE SUPPORTED / PROVIDER-LIMITED
Storage Access Framework supports user-selected file open/view/share, bounded text reading, folder
grants and—since v2.5—rename, copy, move and delete. v2.8 adds picker-based sharing of up to 10
files/photos/videos and up to 10 email attachments through `ACTION_SEND_MULTIPLE`. Mutations require command confirmation, exact
source/destination picker selection, then a second target-aware confirmation. Move copies first and
only deletes the source when permission succeeds; otherwise both copies remain and NUVA says so.
Provider support/write grants can still limit an operation. v3.7 adds one user-selected PDF streamed
to Android's system print preview; printer, pages and final Print remain user-controlled. Broad
storage scan and arbitrary/model-invented paths remain **UNSUPPORTED**.

## Daily-life utility engine — SUPPORTED (offline)
Data-driven local answers cover well over 1,000 command forms rather than 1,000 hard-coded phrases:
arithmetic with precedence/parentheses/powers, roots/factorials, percentage/discount/VAT/tip,
bill splitting, BMI/BMR/water estimates (with medical disclaimers), EMI/simple+compound interest,
profit/loss, unit price, savings goals, mileage/trip fuel cost/travel ETA, average/median/ratio/grade,
rectangle/circle/triangle/cuboid geometry, download-time estimates, age/date-difference/days-until/
weekday, coin/dice/random choice, and conversions across length, weight, cooking volume, area plus
Bangladeshi decimal/katha/bigha, speed, time, data size, temperature, energy and pressure. Inputs and
results stay on-device. Shopping/grocery lists reuse the local to-do store; expense logs reuse local
notes. `shopping list dekhao`, `todo list poro`, `note gulo dekhao` and `khoroch gulo poro` read the
matching local items back without a network call.

## 600 sourced daily skills — SUPPORTED
The original exact 100-entry registry covers health/emergency, travel/nearby, household/shopping,
education/work, financial information, civic/faith, digital safety and lifestyle lookups. v2.0 adds
**exactly 500** precise matrix-generated skills: 25 local services × 8 tasks (200), 20 public
services × 5 tasks (100), 20 learning subjects × 5 tasks (100), and 20 household products × 5
tasks (100). An entity and task must both match, while location/model/error details are preserved.
All skills are read-only live searches; transaction and credential policies run first.
Lists: [`100-daily-skills.md`](100-daily-skills.md) · [`500-extended-skills.md`](500-extended-skills.md).

## Emergency & SOS — USER-FINALIZED SUPPORTED
v3.4 maps national/police/fire/ambulance emergency requests to Bangladesh `999` and opens only the
visible dialer; NUVA never places the call. SOS text opens the normal confirmed share sheet without
collecting location. Emergency/medical-info settings open through the official Android settings
action with security-settings fallback. Other helpline numbers remain sourced lookups rather than
hard-coded potentially stale numbers.

## Maps, routes & navigation — SUPPORTED USER-HANDOFF
v3.2 opens user-visible maps for directions, turn-by-turn navigation, nearby searches and street-view
coordinates. Dynamic origin/destination are preserved; driving/walking/bicycling/transit modes map to
official Google Maps URLs or native geo/navigation intents with browser fallback. NUVA itself reads
no device location; Maps applies its own user-granted location permission.

## Clock management — SYSTEM-INTENT SUPPORTED / APP-LIMITED
v3.6 can show alarm/timer lists and request snooze/dismiss for the active alarm/timer through official
AlarmClock intents. Snooze/dismiss are blocking-confirmation actions. If the device Clock app does
not implement an action, NUVA opens the Clock app and explicitly asks the user to finish manually.
Creating alarms/timers remains supported; deleting arbitrary saved alarms is not guessed.

## Device utilities & current information — SUPPORTED
Torch, battery/time/date/network/storage answers read from the phone at execution time (offline;
Bangla/Banglish/English, including ASR variants such as `aj koto tarik` and `akn koyta baje`). v3.3
adds privacy-safe diagnostics: manufacturer/model + Android/API, RAM total/available, uptime, display
resolution/density, media volume/ringer, timezone/UTC offset, locale, launchable app count and sensor
count/names. Serial, Android ID, MAC, IMEI and other persistent identifiers are deliberately absent.
Alarms/timers, reminders, notes/to-dos and app-agnostic press/clear/describe remain supported. Current weather/news/live scores/traffic/rates,
prayer times, sunrise/sunset, air quality and transport schedules are routed to a live web search
rather than answered from stale model memory. Other factual/how-to/recipe/translation questions
also open a sourced web result instead of returning `UNSUPPORTED`.

## Settings & app-management screens — SUPPORTED (ANDROID-LIMITED by nature)
Exact user-controlled screens/panels cover brightness, DND, Wi-Fi, Bluetooth, mobile data, airplane
mode, location, hotspot, NFC, VPN, battery saver, default apps, date/time, language, storage, privacy,
security, cast, print, captions, sound, notification, app and accessibility. Android forbids most
direct toggles, so NUVA opens the exact screen; torch/volume and DND-with-existing-policy-access are
direct where permitted. v2.9 also resolves any installed app label to App Info, that app's notification
settings, or its Play Store page. No secure setting is silently changed.

## Notifications — SUPPORTED / APP-LIMITED
Read/summarize (OTP redacted, banking skipped), open source app and shade open. v2.3 uses an app's
official free-form RemoteInput action for a reply only after blocking confirmation. Banking apps,
credential-bearing replies, missing/expired actions and apps with no RemoteInput are refused; NUVA
never guesses a reply button through accessibility. v2.7 can dismiss one explicitly selected safe
notification or invoke an exact allowlisted app-provided `Mark as read` action, both after blocking
confirmation. Financial notifications and bulk clear remain unavailable.

## Screen understanding & automation — SUPPORTED (semantic only)
Read visible/focused text, UI summary (buttons/inputs/lists), tap/long-press/type/clear/
focus/scroll/swipe on SEMANTIC targets. Ambiguous or missing target ⇒ STOP and ask.
Blind automation never happens.

## Financial — BLOCKED by policy (unchanged)
LEVEL 1: open + navigate financial apps. LEVEL 2: OTP/PIN/password/CVV/card/biometric never
read/stored/typed; banking screens never read. LEVEL 3: money transfer/payment/cash-out/
recharge/purchase/financial authorization automation ALWAYS refused with:
"এই financial transaction NUVA নিজে করতে পারবে না। আপনি চাইলে নিজে manually করতে পারবেন।"

Financial, credential, file/gallery, social-account mutation, restricted Android settings,
communications, IoT, booking/submission and autonomous-surveillance limitations-এর exact
1,000-item list: [`1000-unsupported-skills.md`](1000-unsupported-skills.md).

## PARTIAL / pending verification
Real-device QA (checklist in `v1.6-real-device-qa.md`) and the APK artifact build are pending —
see `v1.6-production-audit.md` §5. Nothing here claims device-verified status.
