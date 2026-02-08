# CCTVPrimary Onboarding

Project documentation for developers working on the Primary (camera) application and related Viewer changes.

## Project Documentation

### Foreground Service Policy (Mic / Talkback)

**[FGS_POLICY.md](FGS_POLICY.md)** — Documents the foreground service type strategy for Android 14+ compliance. The service starts with `CAMERA` only and dynamically escalates to `CAMERA | MICROPHONE` when mic capture begins (streaming audio or recording). Explains state transitions, rationale, and instrumentation for debugging FGS type changes.

### Primary: Low-Power Policy (Idle, Battery, Thermal)

**Low-power idle mode** (see [ARCHITECTURE.md](ARCHITECTURE.md#27-low-power-idle-mode)) is extended with:
- **Battery/charging-aware profiles**: When entering idle, if unplugged and battery &lt; 20%, bitrate cap is 600 kbps instead of 900 kbps.
- **Thermal tier scaling**: Progressive throttling by thermal status (MODERATE 10%, SEVERE 30%, CRITICAL 50%, EMERGENCY 70% bitrate reduction); MODERATE tier on API 31+.
- **Idle entry hysteresis**: 10 s delay before entering low-power to avoid flapping on brief viewer disconnects.
- **Thermal debounce**: 15 s debounce before applying thermal throttling to ignore brief spikes.

### Primary: Video Recording

**[VIDEO_RECORDING.md](VIDEO_RECORDING.md)** — Recording uses `generateUniqueFilename` with max 100 attempts; falls back to timestamp+UUID and logs a **warning** (Log.w) when exhausted (handled, non-critical).

### Viewer: StreamClient Architecture

**[VIEWER_ARCHITECTURE.md](VIEWER_ARCHITECTURE.md)** — Key session updates:
- **StreamClientExecutors**: Centralized manager for 7 single-thread executors (connect, decode, heartbeat, sender, audio record, audio playback, reconnect); simplifies lifecycle and reduces race complexity.
- **Handshake constants**: Timeouts and thresholds in `StreamClientConstants.kt` (e.g. `HANDSHAKE_AUTH_TIMEOUT_MS`, `STREAM_STALL_TIMEOUT_MS`).
- **Device-tier queue sizing**: Decode queue (15/25/30) and audio playback queue (40/60/80) sized by device tier (RAM, cores, `MEDIA_PERFORMANCE_CLASS`) for latency and memory balance on weak devices.
