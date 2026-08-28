# NUVA v4.0 — Home Assistant integration

Provider-specific phase selected by the user. This is a deliberately small physical-control
allowlist, not arbitrary Home Assistant service execution.

## Secure setup

Settings → Home Assistant:

1. Enter an **HTTPS** Home Assistant origin (optional port/subpath allowed).
2. Paste a Home Assistant long-lived access token in the app—never in chat/source code.
3. Tap **Save encrypted**, then **Test**.

The token is AES-GCM encrypted with a non-exportable Android Keystore key and stored in private app
preferences. It is decrypted only for a direct Home Assistant HTTPS request, never logged, synced,
or sent to NUVA's backend/Groq/Supabase. HTTP, URL credentials, query tokens and fragments are
rejected.

## Commands

```text
living room light on koro
bedroom fan off koro
home assistant kitchen smart switch toggle
bedroom ac temperature 24 degree
```

## Allowed domains/services

| Domain | Services |
|---|---|
| `light` | turn_on, turn_off, toggle |
| `switch` | turn_on, turn_off, toggle |
| `fan` | turn_on, turn_off, toggle |
| `climate` | turn_on, turn_off, set_temperature 10–32°C |

Every physical action requires blocking confirmation. Entity matching fetches current states, prefers
exact friendly-name/object-ID match, then starts-with/contains. Zero matches fail; multiple matches
list candidates and stop instead of guessing.

## Explicitly excluded

Locks, garage/cover, cameras, alarm/security systems, gas valves, water pumps, ovens, medical devices,
location/trackers, scripts/scenes/automations and arbitrary domain/service names. Adding one requires
a separate safety design, not a prompt or server response.
