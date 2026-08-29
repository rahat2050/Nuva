# NUVA v2.1 — 10,000+ natural command grammar

NUVA now has a data-audited paraphrase grammar instead of 10,000 copied `if` statements.

## Audited count

- 50 safe command families
- 5 unique aliases per family
- 7 raw prefixes
- 7 suffix/politeness variants
- `50 × 5 × 7 × 7 = 12,250` concrete static command forms

Dynamic app names, contacts, message bodies, search queries, alarm times and skill slots are not
included in this conservative count.

## Supported variation types

### Prefixes
No prefix, `Nuva`, `Hey Nuva`, `please`, `Nuva please`, `doya kore`, and `ektu`.

### Suffixes
No suffix, `please`, `ekhon`, `ekhoni`, `kore dao`, `bolen`, and `taratari`.

### Families

- Navigation: home, back, recent apps
- Screen: read, describe/buttons, clear input
- Notifications: read, shade/panel, source app
- Media: pause, resume, next, previous
- Sound: volume up/down/mute and sound settings
- Camera: photo, video, explicit capture screen
- Device/settings: torch, Wi-Fi, Bluetooth, brightness, DND, general/app/notification/accessibility settings
- Status: battery, time, date, network, storage
- Local lists: notes, to-dos, shopping and expenses
- Assistant: help, greeting, thanks, identity
- Random: coin and dice
- Live sourced info: weather, news, scores, traffic, prayer time and air quality

Each family has five Bangla/Banglish/English or ASR-style aliases. Examples:

```text
Nuva please go to home taratari
please open notification app please
Nuva please battery percentage bolo ekhon
Hey Nuva ektu current AQI bolo bolen
doya kore picture tolar screen dao ekhoni
```

## Dynamic-command canonicalization

On an initial parser miss, conservative command words are normalized and retried while dynamic slots
are preserved:

- `open kore dao` / `খুলে দিন` → canonical open
- `bondho kore dao` → canonical close
- `search kore dao` / `খুঁজে বের করো` → canonical search
- `phone lagiye dao` → canonical call
- `message kore dao` → canonical message
- reminder variants → canonical reminder

A message that already parsed successfully keeps its exact body.

## Multi-step plans

Connectors now include `and then`, `erpor`, `also`, `এরপর`, and `তারপরে`, with up to six recursive
segments. Every segment still passes validation, risk checks and its own confirmation gate. A message
body containing `ar/and` is not split because `SEND_MESSAGE` can never be accepted as a split's left
side.

## Security

The canonical text is security-checked before broad fallback routing. Common dangerous typos such as
`paymnt`, `send mony`, `pasword`, and `passwrd` are normalized only to ensure payment/credential
requests remain blocked. The grammar never creates a new executable action; it only routes back into
the existing typed parser and validator.
