# NUVA v2.9 — Expanded settings and per-app management

Eighth safe non-financial implementation phase. Android owns every final secure setting change.

## Exact system screens/panels

```text
mobile data setting khulo
airplane mode setting khulo
location setting khulo
hotspot setting khulo
nfc setting khulo
vpn setting khulo
battery saver setting khulo
default apps setting khulo
date time setting khulo
language setting khulo
storage setting khulo
privacy setting khulo
security setting khulo
cast setting khulo
print setting khulo
caption setting khulo
```

These map to official Settings actions/panels. NUVA does not use hidden APIs, shell commands or
Accessibility to bypass Android's user interaction. Existing torch/volume controls remain direct;
DND is direct only after the user manually granted notification-policy access.

## Per-app management

```text
facebook app info khulo
whatsapp notification settings khulo
youtube play store page khulo
```

The installed app is dynamically resolved by launchable label. App Info and app-specific
notification settings receive the resolved package. Play Store opens the installed package page,
falling back to a store search if the app is absent.

## Safety

- Opening a settings screen is not treated as changing the setting.
- No system confirmation is bypassed.
- Financial app management/info screens may be opened, but financial transactions and sensitive
  screen automation remain blocked.
- Uninstall remains a separate confirmed system handoff and is refused for financial apps/NUVA.
