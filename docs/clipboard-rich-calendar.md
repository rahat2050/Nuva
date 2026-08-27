# NUVA v3.0 — Explicit clipboard and rich calendar drafts

Ninth safe non-financial implementation phase.

## Clipboard

```text
clipboard e copy koro je meeting kal 9 tay
clipboard poro
clipboard clear koro
```

Every clipboard operation is an explicit foreground command behind blocking confirmation. Copy is
bounded to 5,000 characters and credential/financial text is refused. Read returns only the current
primary clip, redacts OTP-like content, and keeps no history. Clear uses the official ClipboardManager
API. NUVA never monitors clipboard changes in background.

## Rich calendar event

```text
kal 9 tay 2 hour calendar event create title project meeting location khulna description roadmap attendee user@example.com
```

The parser requires an explicit time and title. It supports:

- Relative day/weekday and Bangla/Banglish/English clock forms
- Duration (default one hour)
- Location
- Description
- One validated attendee email

NUVA opens the official Calendar `ACTION_INSERT` screen with begin/end/title/location/description and
attendee extras. The user reviews and taps final Save. Credential-like or financial event text is
rejected again at execution time.

## Still unsupported

- Background clipboard monitoring/history/sync.
- Copying OTP/PIN/password/financial-transaction text.
- Silent calendar writes or automatic invitation sending.
- Calendar event deletion without a target-aware provider flow.
