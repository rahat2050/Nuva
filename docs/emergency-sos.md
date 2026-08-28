# NUVA v3.4 — Emergency and SOS handoffs

Thirteenth safe non-financial implementation phase. NUVA reduces navigation friction but leaves the
actual emergency call/message under the user's control.

## Bangladesh 999 dialer

```text
emergency call
999 dial koro
police call koro
ambulance call koro
fire service call
```

National emergency, police, fire and ambulance requests all map to Bangladesh `999`. NUVA opens
`ACTION_DIAL tel:999`; it never uses direct calling and never presses Call.

## SOS share draft

```text
sos message draft je amar help dorkar
emergency message draft
```

A custom message—or a safe default when omitted—goes through the existing confirmation-gated Android
share sheet. NUVA does not read or append current location, choose a recipient, or send automatically.

## Emergency information settings

```text
emergency info setting khulo
medical info setting khulo
```

NUVA opens Android's official emergency-assistance settings action. OEMs without that screen fall
back to Security settings; final profile/contact edits remain user-controlled.

## Safety

- No automatic emergency call or message.
- No background location collection.
- No fabricated service-specific numbers: police/fire/ambulance use the single national 999 route.
- Other specialist helplines use current sourced search skills instead of hard-coded numbers.
