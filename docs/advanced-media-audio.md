# NUVA v3.5 — Advanced media and audio control

Fourteenth safe non-financial implementation phase.

## Active MediaSession controls

```text
music stop koro
music 30 second forward
video 15 second rewind
next track
previous track
music pause koro
music resume koro
```

NUVA uses the active notification MediaSession's official TransportControls. Forward/rewind is a
bounded seek of 1–300 seconds, clamped at zero and at known media duration. Player apps that do not
expose a session/seek action fail honestly.

## Exact media volume

```text
volume 55 percent
volume set 20 percent
sound mute koro
sound unmute koro
volume barao
volume kom koro
```

Exact volume is validated to 0–100 and mapped to the device's media-stream index. Android's visible
volume UI, safe-volume warning and OEM limits remain authoritative.

## Safety

- MediaSession and notification access only; no screen-coordinate playback tapping.
- Seek offset is capped at five minutes per command.
- Only media-stream volume is changed; call/alarm volume is not silently modified.
- Out-of-range percentages are rejected.
