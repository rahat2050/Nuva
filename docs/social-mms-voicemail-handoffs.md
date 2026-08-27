# NUVA v3.1 — Social, MMS and voicemail handoffs

Tenth safe non-financial implementation phase. Every external communication remains visible and
user-finalized.

## Social post drafts

```text
facebook post draft je aj meeting ache
instagram post compose text new photo coming soon
linkedin post draft je project update
```

Supported text-compose targets: Facebook, Instagram, X/Twitter, LinkedIn, Reddit, Threads and
TikTok. NUVA opens the installed app's `ACTION_SEND` compose route. If the package-specific route is
missing, Android's generic share chooser opens. The user reviews and taps final Post. NUVA never
publishes automatically, bulk-posts or changes account/profile settings.

## MMS/message attachment draft

```text
mms compose 01712345678 photo attachment je ami ashchi
mms compose 01712345678 message hello
```

Without attachment, the visible `smsto:` composer opens. With attachment, Android's picker selects
one URI, then a visible message chooser receives text, optional recipient and temporary read grant.
The user chooses the app/recipient and taps final Send. Provider/app support varies.

## Voicemail

```text
voicemail khulo
```

NUVA opens `ACTION_DIAL voicemail:`. The dialer remains visible and the user starts the call.
Voicemail recording, deletion and automatic response remain unsupported.

## Safety

- Social/MMS text is checked again for credentials and financial transaction content.
- Social post and MMS actions require blocking confirmation.
- Attachment is selected by Android picker; no broad storage permission.
- No auto-send, auto-post, bulk campaign, account mutation or hidden communication.
