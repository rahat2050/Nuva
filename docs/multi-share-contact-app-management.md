# NUVA v2.8 — Multi-share, contact picker and app uninstall handoff

Seventh safe non-financial implementation phase.

## Multiple item sharing

```text
multiple file share koro
onek photo share koro
multiple video share koro
email compose koro multiple attachment
```

Android's multiple-document picker is mandatory. NUVA deduplicates and caps the selection at 10
URIs, grants read access through ClipData, then opens `ACTION_SEND_MULTIPLE`. The user chooses the
app/recipient and final action. Multiple email attachments keep recipient/subject/body in the visible
composer; NUVA never taps Send.

## Contact picker view/edit

```text
contact dekhao
contact edit koro
```

The Android contact picker chooses the exact contact. View opens its details. Edit opens the Contacts
app's visible editor with temporary URI grants; final Save remains user-controlled. NUVA does not
request broad contact-write permission and does not delete contacts.

## Non-financial app uninstall handoff

```text
facebook uninstall koro
```

NUVA resolves the installed package by launchable app label, asks for blocking confirmation, then
opens Android's `ACTION_DELETE package:` system confirmation. Android performs the final decision.
Financial apps and NUVA itself are refused.

## Still unsupported

- Silent or bulk automatic posts.
- More than 10 items in one handoff.
- Contact deletion or background edits.
- Financial-app uninstall initiation.
- Package removal without Android's system confirmation.
