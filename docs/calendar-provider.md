# NUVA v4.1 — Explicit Calendar provider integration

Provider/permission-specific phase selected after Home Assistant. `READ_CALENDAR` is optional and is
requested independently in onboarding.

## Commands

```text
calendar agenda poro
next 7 day calendar agenda poro
kal calendar events poro
calendar event kholo title dentist
calendar event edit title project meeting
```

## Agenda privacy

- Range is bounded to 1–31 days and results to 100 queried/20 displayed events.
- User must explicitly request the read and confirm it.
- Title, begin time and optional location are shown; description/attendees are not queried.
- OTP/password-like event titles are excluded; credential-like locations are omitted and code-like
  title/location content is redacted before display.
- Data stays on-device and is not synced to NUVA backend/Groq/Supabase.

## Exact event view/edit

NUVA matches within the requested range by normalized exact title first, then starts-with/contains.
Zero matches fail; multiple matches list candidates and stop. A single result opens the official
Calendar event URI in view or edit mode. Edit is visible and final Save belongs to the user.

## Exclusions

- No `WRITE_CALENDAR` permission.
- No silent event mutation or direct delete.
- No background/full-history calendar export.
- No guessed event selection when titles are ambiguous.
