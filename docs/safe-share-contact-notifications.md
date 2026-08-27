# NUVA v2.7 — Safe share, contact and notification management

Sixth safe non-financial implementation phase.

## Text share handoff

```text
text share koro je ami ashchi
lekha share koro "meeting kal 9 tay"
```

After blocking confirmation, Android's share sheet opens. The user chooses the app/recipient and final
action. Credential-like and financial text is rejected again at execution time. NUVA does not bulk
share or automatically post.

## New contact draft

```text
new contact add koro name Rahim number 01712345678 email rahim@example.com
```

NUVA validates bounded name/phone/email fields and opens the Contacts app's official insert screen.
No Contacts write permission is requested; the user reviews and taps final Save.

## Notification management

```text
2 number notification dismiss koro
notification mark as read koro
```

Rules:

- One visible safe-snapshot ordinal only.
- Blocking confirmation for dismiss and mark-read.
- Financial/payment notifications are never present in the safe snapshot.
- Dismiss uses the notification listener's exact system notification key.
- Mark-read runs only an app-provided action whose normalized title is exactly allowlisted (`Read`,
  `Mark read`, `Mark as read`, or the listed Bangla equivalents).
- `Mark unread`, Archive and other ambiguous actions are rejected.
- Bulk notification clear remains unsupported.

## Still unsupported

Automatic posts, bulk sharing, contact auto-save, financial/credential sharing, guessed notification
actions and conversation-history deletion.
