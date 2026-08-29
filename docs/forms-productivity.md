# NUVA v2.4 — Forms and productivity handoff

Third safe non-financial implementation phase. NUVA prepares local/user-reviewed handoffs; it does
not submit a form, upload identity documents automatically, pay a fee, book, or send in background.

## Email attachment handoff

Example:

```text
user@example.com ke email compose koro subject report body report attached attachment
```

After blocking confirmation, Android's file picker opens. The selected URI is passed to an
`ACTION_SEND` chooser with recipient/subject/body. The user chooses the email app, reviews everything
and taps final Send. No broad storage permission is used.

## Local form draft + official portal handoff

Supported kinds: passport, NID, birth registration, driving licence, visa, admission, job, doctor,
hotel, flight and courier pickup.

Examples:

```text
passport application form kholo details name address draft
job application prepare details CV update korte hobe
hotel booking prepare
```

Only explicitly dictated `details` are saved as a local note. The browser search contains only the
form kind's sourced portal query—not personal details. The user performs form entry, document upload,
review and final Submit. Credential-like or financial details are rejected.

## Scheduled compose reminder

Examples:

```text
kal shokal 9 tay schedule email user@example.com subject meeting je ami ashchi
kal 8 tay schedule sms 01712345678 message ami ashchi
```

NUVA schedules an `AlarmManager.setAndAllowWhileIdle` reminder. At the chosen time a local
notification appears. Tapping it opens an email or SMS draft; no message is automatically sent.
Notification permission is required. v2.6 stores reminders in Room, restores them after reboot/app
update, supports daily/weekly recurrence, and provides list/cancel voice commands.

## Still unsupported

- Automatic Send, Submit, booking, payment or document upload.
- Bulk or recurring messages.
- Scheduling credential/financial content.
- Silent browser form filling through accessibility.
