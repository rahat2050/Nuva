# NUVA v4.3 — Accessible 3D UI/UX system

## Visual direction

v4.3 replaces the flat Material presentation with a consistent, lightweight 3D/glass language:

- violet-to-cyan intelligence/voice palette;
- layered aurora background shared by every route;
- raised translucent panels with highlight borders and depth shadows;
- tactile static voice orb with clear listening/thinking/error colors;
- floating rounded bottom navigation with an elevated selected destination;
- bottom-positioned assistant voice plate over other apps;
- rounded fields, switches, chips, dialogs and action hierarchy;
- matching dark and light color schemes with brand-controlled contrast.

The depth is rendered with native Compose gradients, borders and shadows. No bitmap background,
remote image, WebView, analytics SDK or large 3D engine was added.

## UX improvements

### Home

- One bounded `LazyColumn` replaces the previous column-with-nested-list layout.
- A 164dp command orb is the primary voice affordance.
- Status appears in a raised panel with `READY`, `LISTENING`, `THINKING`, `COMPLETE` or error state.
- Typed input remains permanently available in the same command pipeline.
- Recent commands use compact state chips and show Retry only for failed/blocked/unsupported rows.
- Accessibility setup and long on-device results stay visible as separate depth layers.

### History

- One scroll surface owns the header, horizontal filter row, empty state and history cards.
- Status, intent, timestamp and failure reason have separate visual hierarchy.
- Filters scroll horizontally on narrow phones instead of clipping.
- Clear history now shows a blocking destructive-action confirmation.

### Memory

- Notes, to-dos and remembered preferences now share one bounded lazy list.
- Input is grouped in one raised panel; each data type has its own accent.
- Section counts are visible without opening another screen.
- Note/preference deletion now requires an explicit target-aware confirmation.

### Settings and onboarding

- Setup begins with a concise security/depth hero.
- Every permission card has a clear granted/optional/required chip.
- Settings use a consistent control-deck header, glass toggle rows and a raised result panel.
- Existing Android-owned permission/default-assistant screens remain unchanged and user-controlled.

### Floating assistant

- The overlay moved from the top-right to a reachable bottom-center voice plate.
- Width is clamped to the current display.
- Listening, processing, confirmation, success and error now use three-tone depth gradients.
- Confirmation buttons retain explicit labels and cannot bypass the existing action gates.

## Accessibility and performance boundaries

- Primary custom actions keep a minimum 50dp target; the voice orb exposes `Role.Button` semantics.
- Navigation labels remain visible; color is not the only status indicator.
- Decorative infinite animation was intentionally avoided to reduce motion, battery and GPU cost.
- System status/navigation icon appearance follows light/dark mode.
- Text continues to use Material typography and user font scaling.
- All confirmation dialogs, permission prompts, typed fallback and screen-reader setup remain available.
- UI changes do not alter command parsing, LOCAL_ONLY boundaries, financial/credential policy or final
  user confirmation.

## Verification

```bash
cd android
python3 tools/parser_mirror_check.py
python3 tools/android_contract_check.py
```

The 3D theme, Privacy, History, Memory and floating-overlay surfaces were additionally compiled with
the Kotlin/Compose compiler against public Android 35 and Compose 1.7/Material3 1.3 API artifacts in
the constrained build environment. A full Gradle resource build, screenshot review and physical-device
contrast/TalkBack test remain required.
