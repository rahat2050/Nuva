# NUVA v3.6 — Alarm and timer management

Fifteenth safe non-financial implementation phase.

## Commands

```text
show alarms
alarm list dekhao
show timers
timer list dekhao
snooze alarm
alarm bondho koro
timer bondho koro
```

## Behaviour

- Show alarms uses the official AlarmClock show-alarms intent.
- Show timers uses the platform show-timers intent.
- Snooze requests the active alarm snooze action.
- Dismiss alarm/timer requests the corresponding active platform action.
- Snooze/dismiss require blocking confirmation; list views do not.

Clock implementations differ by OEM. If an official action is unavailable, NUVA opens the installed
Clock app and clearly says the final step must be done manually. It never uses screen coordinates to
guess an alarm/timer row.

## Existing create support

Bangla/Banglish/English alarm and timer creation remains available, including relative day and
weekday alarms and bounded timer durations.

## Still unsupported

- Blind deletion of an arbitrary saved alarm.
- Editing an unknown alarm selected by guessed UI position.
- Bypassing the Clock app's own confirmation or OEM restrictions.
