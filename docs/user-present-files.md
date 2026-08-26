# NUVA v2.2 — User-present file and gallery workflows

This is the first implementation phase for the safe, non-financial unsupported list. Android's
system picker remains the authority: NUVA cannot guess a path or scan broad storage, and only the URI
the user explicitly selects is handled.

## Added operations

| Voice example | Operation | Confirmation |
|---|---|---|
| `file open koro` | Pick one file, open with an installed viewer | picker selection |
| `file share koro` | Pick one file, then open Android share sheet | blocking confirmation + picker + recipient |
| `text file pore shonao` | Pick one text file and read at most 100,000 bytes | picker selection |
| `folder select koro` | Pick one folder and persist its user grant | blocking confirmation + folder picker |
| `gallery theke photo select koro` | Pick and view one photo | media picker |
| `photo share koro` | Pick one photo, then Android share sheet | blocking confirmation + picker + recipient |
| `gallery theke video select koro` | Pick and view one video | media picker |
| `video share koro` | Pick one video, then Android share sheet | blocking confirmation + picker + recipient |

## Privacy and security

- No broad storage permission.
- No hard-coded or model-invented file path.
- No file/media is selected without the Android picker.
- Share recipients are chosen in Android's share sheet.
- Sharing and folder grants use the existing blocking confirmation gate.
- Read text is bounded; the full file is not copied to NUVA's database or backend.
- The operations are LOCAL_ONLY and cannot be emitted by the server/AI.

## Still pending in later phases

- URI-specific rename with confirmation.
- Copy/move to a user-selected destination folder.
- URI-specific delete with a second target-aware confirmation.
- Gallery edit/crop workflows.
- Notification RemoteInput reply and email compose are implemented in v2.3.
- Email document-attachment handoff remains pending.
