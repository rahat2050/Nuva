# NUVA v3.7 — Selected PDF print and Calendar view

Sixteenth safe non-financial implementation phase.

## PDF print

```text
pdf print koro
file print koro
```

After blocking confirmation, Android's picker accepts one PDF. NUVA streams the selected URI through
a `PrintDocumentAdapter` into the visible system print preview. The user chooses printer, pages,
copies and final Print. Cancellation is respected while writing, and NUVA never scans storage or
chooses a document automatically.

## Calendar view

```text
calendar dekhao
tomorrow calendar dekhao
calendar agenda
```

NUVA opens the Calendar app focused on today, tomorrow, the day after tomorrow, or a requested
weekday. It uses a Calendar time-view URI and needs no `READ_CALENDAR` permission. Event names/details
are not read by NUVA. Rich event draft creation remains a separate user-saved flow.

## Still unsupported

- Printing non-PDF formats without a renderer.
- Selecting a printer or pressing final Print automatically.
- Reading, summarizing, editing or deleting arbitrary calendar events in background.
