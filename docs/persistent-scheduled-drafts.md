# NUVA v2.6 — Persistent scheduled drafts

Fifth safe non-financial implementation phase. Scheduled email/SMS reminders remain drafts—never
automatic sends—but now survive process death, reboot and app replacement.

## Commands

```text
kal 9 tay schedule email user@example.com je ami ashchi
protidin 8 tay schedule sms message standup update
shukrobar 9 tay schedule email je weekly report
scheduled draft list dekhao
2 number scheduled draft cancel koro
```

## Persistence

Room database v3 adds `scheduled_drafts` with channel, optional recipient/subject, bounded body,
trigger time, recurrence and status. Migration 2→3 preserves existing history, notes and settings.

An alarm carries only the local draft ID. Message content is loaded from Room by the explicit
receiver, rather than copied into the alarm PendingIntent.

## Reboot/app-update restore

- `RECEIVE_BOOT_COMPLETED` is declared.
- `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` reschedule pending rows.
- App process startup also performs an idempotent restore.
- Past one-shot reminders produce their draft notification and become `fired`.
- Past recurring reminders advance to the first future daily/weekly occurrence.

## User controls

- List returns pending drafts ordered by trigger time.
- Cancel uses that same visible ordinal, requires blocking confirmation, cancels AlarmManager and
  marks the Room row `cancelled`.
- Daily and weekly reminders reschedule only after the current notification is posted.

## Safety

- Notification tap opens the draft; it never sends.
- Financial/credential content is rejected again at scheduling time.
- Lock-screen notification visibility is private.
- Missing notification permission prevents scheduling/restoration.
