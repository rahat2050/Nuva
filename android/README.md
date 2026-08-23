# NUVA Android — reserved for PHRASE 2

This directory is intentionally **empty of implementation code**.

Per the master prompt (§19), the Android application belongs to **PHRASE 2** and must not be
started until **PHRASE 1 (Vercel Backend Foundation)** is complete *and deployed to Vercel
production*.

## Phase gate

PHRASE 2 may begin only when all of the following are true:

- [x] GitHub repository exists
- [x] Backend exists
- [x] TypeScript builds
- [x] Health endpoint works
- [x] Groq integration works
- [x] Structured JSON works
- [x] Supabase connection works
- [x] Secrets are configured securely
- [ ] **Vercel production deployment succeeds** ← requires the human developer's Vercel account

See [`../docs/roadmap.md`](../docs/roadmap.md) for the PHRASE 2 internal build order (23 steps)
and [`../docs/architecture.md`](../docs/architecture.md) for the package layout that will be
created here (`com.nuva.assistant`).

## Planned layout (do not create yet)

```
android/
└── app/src/main/
    ├── AndroidManifest.xml
    ├── java/com/nuva/assistant/
    │   ├── core/  ai/  voice/  command/  accessibility/
    │   ├── automation/  database/  memory/  supabase/
    │   ├── service/  ui/
    └── res/
```

## What the Android app will talk to

The Android client only ever calls the NUVA backend — never Groq directly, and it never holds a
server-side secret:

```
Android  ──HTTPS──▶  Vercel (/api/ai/command)  ──▶  Groq
```

The request/response contract the app must implement is frozen and documented in
[`../docs/commands.md`](../docs/commands.md).
