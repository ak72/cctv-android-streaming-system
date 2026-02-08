# CCTVPrimary Onboarding

Project documentation for developers working on the Primary (camera) application.

## Project Documentation

### Foreground Service Policy (Mic / Talkback)

**[FGS_POLICY.md](FGS_POLICY.md)** — Documents the foreground service type strategy for Android 14+ compliance. The service starts with `CAMERA` only and dynamically escalates to `CAMERA | MICROPHONE` when mic capture begins (streaming audio or recording). Explains state transitions, rationale, and instrumentation for debugging FGS type changes.
