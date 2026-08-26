# NUVA v2.3 — User-reviewed communication

Second safe non-financial implementation phase.

## Email compose

Examples:

- `email compose koro`
- `user@example.com ke email koro je kal 9 tay asben`
- `user@example.com ke email koro subject meeting body kal 9 tay asben`

NUVA validates the optional recipient, bounds subject/body, asks for blocking confirmation, and opens
an `ACTION_SENDTO mailto:` composer. The email app remains visible and the user taps final Send.
NUVA does not send email silently, send bulk mail, schedule background mail, or attach a file yet.

## Notification RemoteInput reply

Examples:

- `notification reply dao je ami ashchi`
- `2 number notification e reply dao je 10 minute pore ashbo`

Rules:

1. Notification access must have been granted manually.
2. Banking/payment notifications remain absent from the safe snapshot.
3. The selected notification must expose an official free-form `RemoteInput` action.
4. NUVA shows target ordinal and exact reply in a blocking confirmation.
5. OTP/PIN/password-like reply text is rejected.
6. The stored `PendingIntent` and RemoteInput result keys are used; accessibility never guesses a Reply button.
7. Missing, expired or unsupported reply actions fail honestly.

## v2.4 follow-up

- One picker-selected file attachment and local email/SMS compose reminders are now implemented; see [`forms-productivity.md`](forms-productivity.md).
- Bulk messages, auto-replies and background sends remain unsupported.
