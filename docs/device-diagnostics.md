# NUVA v3.3 — Privacy-safe device diagnostics

Twelfth safe non-financial implementation phase. Values are read locally at execution time and are
not supplied by AI or sent to the backend.

## Commands

```text
phone model ki
android version koto
ram koto
phone uptime koto
screen resolution koto
volume koto
ringer mode ki
timezone ki
phone language ki
koyta app installed
phone e ki sensor ache
```

## Answers

- Manufacturer/model, Android release and API level
- Total/available RAM
- Time since last boot
- Current display pixel dimensions and density
- Media volume percentage and ringer mode
- Timezone ID and UTC offset
- Current system locale/language tag
- Count of launchable apps (not private/package data)
- Sensor count and a bounded list of user-visible sensor names

## Privacy boundary

NUVA deliberately does not expose or store IMEI, serial, Android ID, advertising ID, SIM identity,
MAC address, exact IP/location, account list or other persistent identifiers. Diagnostic answers stay
local and require no additional permission.
