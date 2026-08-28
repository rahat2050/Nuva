# NUVA v4.4 — Quick access and user-present text handoff

## New entry points

### Quick Settings tile

A user-added **Talk to NUVA** tile opens the visible NUVA Activity and starts one microphone session.
It never executes a background command. On Android 13+, NUVA Settings can display Android's official
Add Tile prompt. On older releases the user can add the tile manually from Quick Settings edit mode.

If the device is locked, the tile uses Android's normal unlock challenge before opening NUVA. Android
14+ uses the required immutable Activity `PendingIntent`; older supported releases use the legacy
TileService activity handoff.

### Launcher shortcut

Long-pressing the NUVA app icon exposes **Talk to NUVA**. The static launcher shortcut opens the same
visible listening route as the tile, with no hidden microphone or action.

### Share / Process Text

NUVA now appears in Android's `text/plain` share sheet and selected-text **Process Text** menu. Text
chosen by the user is imported into the Home command field as an editable local draft.

Important boundary:

1. The text is **not submitted automatically**.
2. The user reviews/edits it and presses Send.
3. Input is transient and removed from the Activity Intent after consumption.
4. Maximum imported draft length is 1,000 characters.
5. Credential-like content and financial-transaction requests are refused before becoming a draft.
6. A blocked/empty handoff shows a local Toast and performs no backend request.

Examples:

- Select a paragraph → **Process Text → NUVA** → add `summarize` and press Send.
- Share an address or task from another app → **NUVA** → review the draft.
- Long-press NUVA → **Talk to NUVA**.
- Pull down Quick Settings → **Talk to NUVA**.

## Android/user control

- Tile installation is an Android-owned prompt; NUVA cannot add itself silently.
- Launcher shortcut visibility is controlled by the launcher.
- Share/Process Text starts only after the user selects NUVA.
- Microphone permission and all normal command confirmation/security rules remain unchanged.
- No clipboard monitor, Accessibility scraping or background share receiver was added.

## Verification

The policy has deterministic tests for accepted, truncated, empty, credential and transaction input.
Manifest/XML contracts verify tile permission/filter, share/process filters, shortcut metadata,
bounded-draft policy and absence of auto-submit. Android 35 semantic compilation covers the tile and
request-add APIs. Full Gradle/device testing remains pending.
