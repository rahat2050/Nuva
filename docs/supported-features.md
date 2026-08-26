# NUVA — Supported features (v2.0)

Statuses: **SUPPORTED** (works) · **PARTIAL** (built, piece missing/awaiting device QA) ·
**ANDROID-LIMITED** (only as far as Android permits) · **UNSUPPORTED** (deliberate, stated in-app) ·
**BLOCKED** (policy refusal)

## Voice & conversation — SUPPORTED
Wake "Hey Nuva" (opt-in service, visible session, one wake = command + ≤2 follow-ups),
Bangla/Banglish/English/mixed parsing, typed fallback, Bangla-first replies, stuck-session
timeout recovery (15 s), conversational context (5-min TTL, pronoun "ওকে" resolution).

## Apps & navigation — SUPPORTED
Open any installed app by name (+ Play Store suggestion when missing), close/home/back/recents,
scroll/swipe anywhere, notification shade open, per-app capability truth via
AppCapabilityRegistry.

## Communication — SUPPORTED (confirmation mandatory)
Contacts: dynamic resolution, kinship fallback, multi-match asks, phone numbers incl.
hyphenated. Calls (dialer or opt-in direct). SMS send-after-confirmation. WhatsApp
send-after-confirmation with package + recipient verification. Chat open (wa.me).
Telegram/Messenger/Signal/Viber/IMO — **ANDROID-LIMITED**: message pre-filled, user taps Send.

## Media & camera — SUPPORTED / ANDROID-LIMITED
YouTube search/play, Spotify fallback, pause/resume/next/previous (active MediaSession —
ANDROID-LIMITED via notification access), volume up/down/mute. Camera open photo/video +
explicit-capture flow (shutter always user-controlled). Gallery/media search/share —
**UNSUPPORTED** (picker flows need an activity result).

## Files — ANDROID-LIMITED
Files app opens; arbitrary open/read/rename/move/delete — **UNSUPPORTED** from the background
(SAF requires user-present activity results); delete-with-confirmation therefore not offered
rather than faked.

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

## Device utilities & current information — SUPPORTED
Torch, battery/time/date/network/storage answers read from the phone at execution time (offline;
Bangla/Banglish/English, including ASR variants such as `aj koto tarik` and `akn koyta baje`),
alarms/timers, calendar-prefilled reminders, notes/to-dos, maps search (`dhaka er map dekhao`),
and app-agnostic press/clear/describe commands. Current weather/news/live scores/traffic/rates,
prayer times, sunrise/sunset, air quality and transport schedules are routed to a live web search
rather than answered from stale model memory. Other factual/how-to/recipe/translation questions
also open a sourced web result instead of returning `UNSUPPORTED`.

## Settings screens — SUPPORTED (ANDROID-LIMITED by nature)
Brightness/DND/Wi-Fi/BT/sound/display/notification/app/accessibility settings — Android
forbids direct toggles for third-party apps, so the exact screen opens (torch & volume are
direct where permitted).

## Notifications — SUPPORTED (reply UNSUPPORTED)
Read/summarize (OTP redacted, banking skipped), open source app, shade open. Reply —
**UNSUPPORTED** until a reliable per-app RemoteInput route exists.

## Screen understanding & automation — SUPPORTED (semantic only)
Read visible/focused text, UI summary (buttons/inputs/lists), tap/long-press/type/clear/
focus/scroll/swipe on SEMANTIC targets. Ambiguous or missing target ⇒ STOP and ask.
Blind automation never happens.

## Financial — BLOCKED by policy (unchanged)
LEVEL 1: open + navigate financial apps. LEVEL 2: OTP/PIN/password/CVV/card/biometric never
read/stored/typed; banking screens never read. LEVEL 3: money transfer/payment/cash-out/
recharge/purchase/financial authorization automation ALWAYS refused with:
"এই financial transaction NUVA নিজে করতে পারবে না। আপনি চাইলে নিজে manually করতে পারবেন।"

## PARTIAL / pending verification
Real-device QA (checklist in `v1.6-real-device-qa.md`) and the APK artifact build are pending —
see `v1.6-production-audit.md` §5. Nothing here claims device-verified status.
