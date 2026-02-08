# Project Architecture

This document describes the **current** architecture of the CCTV system. It is training documentation and must be kept aligned with the codebase.

---

## 1. High-Level System Architecture

The system follows a **Client–Server** model over **TCP/IP**.

- **Server**: `CCTVPrimary` (the camera device)
- **Client**: `CCTVViewer` (the display device)

```mermaid
graph LR
    subgraph Primary Device [CCTVPrimary]
        Camera[CameraX] --> Encoder[VideoEncoder H.264]
        Mic[Microphone] --> AudioEngine[AudioSourceEngine]
        Encoder --> StreamServer[StreamServer + FrameBus]
        AudioEngine --> StreamAudioSender[StreamAudioSender AAC]
        StreamAudioSender --> StreamServer
        AudioEngine --> Recorder[CustomRecorder]
        StreamServer --> Playback[AudioTrack Talkback]
    end

    subgraph Viewer Device [CCTVViewer]
        StreamClient[StreamClient TCP] --> Decoder[MediaCodec H.264]
        StreamClient --> AudioPlay[AudioTrack]
        ViewerMic[Microphone] --> StreamClient
        Decoder --> Surface[SurfaceView/TextureView]
    end

    StreamServer <==>|TCP Socket (Video/Audio/Control)| StreamClient
```

---

## 2. CCTVPrimary (Server) Internals

The app is built around a **Foreground Service** so capture and streaming continue when the app is minimized or the screen is off. Pipeline behavior is driven by **device capabilities and Camera2 hardware level** (see §2.1), not by brand or model checks.

### 2.1. Startup & Resilience Layer

Before streaming starts, the service ensures the device profile exists and, on first launch, optionally validates the camera→encoder pipeline.

| Component | Role | Behavior |
|-----------|------|----------|
| **`RuntimeProbe`** | Ensure profile at runtime | Calls `DeviceProfileManager.getOrCreate()`. No heavy probing here; just build or load a capability-driven profile. |
| **`DeviceProfileManager`** | Build / load device profile | Reads **Camera2** capabilities (`Camera2Capabilities`) and **codec** capabilities (`CodecCapabilities`). Applies **`CameraHardwareLevelPolicy`** to set `preferBufferMode`, `allowFpsGovernor`, `allowDynamicBitrate`. Builds a quality ladder. If `DeviceProfileStore` has a matching profile (fingerprint + camera), returns it; otherwise builds and persists. |
| **`CameraHardwareLevelPolicy`** | Policy from hardware level | **LEGACY** / **LIMITED** → prefer buffer input, fixed FPS cap (e.g. 24), no dynamic bitrate recovery. **FULL** / **LEVEL_3** → allow FPS governor and dynamic bitrate. No brand-specific hacks. |
| **`DeviceProfileStore`** | Persist profile | Saves `DeviceProfile` (JSON) keyed by firmware fingerprint + cameraId + lensFacing. |
| **`ActivePipelineProber`** | Optional active probe (first launch) | Runs short test binds (Surface then Buffer mode, descending resolutions). Success = at least one encoded frame within timeout. Result (e.g. “use Buffer, max 720p”) is written into the profile. Used when the service needs an empirically validated starting config. |
| **`EncoderProbeStore`** | Persist Surface input failure | If Surface input fails at runtime (or in probe), marks “Surface input bad” so future launches prefer Buffer mode without re-probing. |
| **`EncoderCapabilityDetector`** | Codec capability check | Queries `MediaCodecList` for AVC Surface input support. |
| **`DeviceQuirks`** | Mostly UI hints; one pipeline exception | Most quirks are display hints only. **Exception:** `forceBufferInputMode()` affects pipeline mode (buffer vs surface) for known-bad devices (e.g. Samsung M30s). Pipeline policy is otherwise in `CameraHardwareLevelPolicy` and the profile. |
| **`SizeSelector`** | Resolution selection | Intersects preview/YUV/recorder sizes; picks 4:3 portrait candidates; used when building the profile ladder. |

### 2.2. Threading & Executors

All streaming and capture use **shared executors** to avoid thread explosion and improve thermal behavior. See **`StreamingExecutors.kt`** and **`Docs/THREADING.md`**.

| Executor | Use |
|----------|-----|
| **`ioExecutor`** | StreamServer accept loop, other I/O. |
| **`senderExecutor`** | Single thread that drains the **FrameBus** and fans out frames to all `ViewerSession`s. |
| **`controlExecutor`** | **Command Bus** consumer (infinite loop); camera bind/unbind. Only this thread holds `encoderLock` for commands. Recording start/stop do **not** run here—they use `recordingExecutor`. |
| **`recordingExecutor`** | Start/stop recording and CustomRecorder file/MediaMuxer work. Dedicated so recording is never starved by the Command Bus loop. |
| **`encodingExecutor`** | ImageAnalysis and encoding-related work (kept separate from control to avoid stalls). |
| **`sessionPool`** | Fixed pool for per-session work (listener, video sender, audio sender, heartbeat). Caps total session threads. |

**Golden rule:** Session and accept threads **never** call into the encoder or camera directly. They post to the **Command Bus**; the control thread runs `handleStreamCommand()`.

### 2.3. Command Bus & Control Flow

Remote commands (keyframe request, start/stop recording, backpressure, zoom, reconfigure, encoder recovery) are **posted** to a **Command Bus**. A single consumer runs on **`controlExecutor`** and executes them.

| Component | Role |
|-----------|------|
| **`CommandBus`** | Bounded queue of `StreamCommand`; consumer loop runs on `StreamingExecutors.controlExecutor`. |
| **`StreamCommand`** | Sealed class: `RequestKeyframe`, `StartRecording`, `StopRecording`, `ReconfigureStream`, `Backpressure`, `PressureClear`, `AdjustBitrate`, `SwitchCamera`, `Zoom`, `RecoverEncoder`. |
| **`CameraForegroundService.handleStreamCommand()`** | Runs **only** on the control thread. Holds `encoderLock` when touching the encoder; posts recording start/stop to **`recordingExecutor`** (actual MediaStore/File/MediaMuxer work runs there). |

Thus: **ViewerSession** (or StreamServer) receives a protocol command → converts to `StreamCommand` → `commandBus.post(cmd)` → control thread → `handleStreamCommand(cmd)` → encoder/camera/recording. No synchronous cross-layer calls from session threads into the pipeline.

### 2.4. Video Path: Encoder → FrameBus → Sessions

| Component | Role |
|-----------|------|
| **`VideoEncoder`** | Wraps `MediaCodec`. Supports **Surface input** (zero-copy, preferred) and **ByteBuffer input** (fallback). Mode chosen by profile (`preferBufferMode`), user override (“Force Buffer Mode”), or runtime/EncoderProbeStore failure. Produces H.264; notifies `EncodedFrameListener` per frame. |
| **`FrameBus`** | Bounded queue (capacity 60). **Single producer**: encoder output is pushed via `StreamServer.enqueueFrame(frame)` (which calls `frameBus.publish(frame)`). **Single consumer**: sender loop on `senderExecutor` drains with `pollWithTimeout(ms)` (no blocking `take()`), then fans out to each `ViewerSession.enqueueFrame()`. **Drop policy:** DROP_NON_KEYFRAME_ON_FULL (keyframes prioritized; non-keyframes dropped when full). See `FrameBus.kt` KDoc and **THREADING.md**. |
| **`StreamServer`** | Holds the `FrameBus` and `CommandBus`. Accept loop on `ioExecutor`; for each client, creates a **`ViewerSession`**. Encoder callback → `streamServer.enqueueFrame(frame)` → `frameBus.publish()`. Sender loop (on `senderExecutor`) pulls from FrameBus and calls `session.enqueueFrame(toSend)` for each session. Tracks active session count; triggers **low-power idle mode** when no viewers and not recording and UI not visible. |
| **`ViewerSession`** | Per-client session. **Input:** listener thread (from session pool) reads protocol (HELLO, AUTH, CAPS, SET_STREAM, PING, etc.). **Output:** dedicated sender task (session pool) drains per-session frame queue and control queue; writes STREAM_STATE, CSD, FRAME, etc. **Stream epoch:** incremented on encoder restart/reconfigure; Viewer must drop frames for wrong epoch. **STREAM_STATE** is server-authoritative (numeric codes + epoch). Session lifecycle: CONNECTING → AUTHENTICATED → STREAMING / RECONFIGURING → DISCONNECTED. See **SESSION_LIFECYCLE.md**. |

**Encoder self-healing:** `VideoEncoder` detects (1) **stall** (input advancing, no output for >5s) and (2) **keyframe drought** (no keyframe for 2× then 3× GOP). It invokes `onRecoveryNeeded`; the service posts `RecoverEncoder` to the Command Bus. The control thread performs a soft encoder restart (stop/start) with a **cooldown** (e.g. 90s, or 180s on low-tier hardware) to avoid repeated restarts on unstable devices.

### 2.5. Audio Path

| Component | Role |
|-----------|------|
| **`AudioSourceEngine`** | Singleton; single `AudioRecord`. Multiple consumers register as listeners and receive copies of the same PCM. Reference counting: streaming and recording increment refs; mic runs when any ref > 0. |
| **`StreamAudioSender`** | Listener that receives PCM from `AudioSourceEngine`; applies volume boost and soft limiter; encodes to AAC (e.g. 64 kbps); sends downstream via `StreamServer` (e.g. `AUDIO_FRAME|dir=down|format=aac|...`). |
| **Talkback** | Viewer sends PCM upstream. `StreamServer` is configured with `onAudioFrameUp`; the service plays chunks via a dedicated **AudioTrack** (noise gate applied to avoid hiss). |

### 2.6. Recording

| Component | Role |
|-----------|------|
| **`CustomRecorder`** | Uses `MediaCodec` + `MediaMuxer` for MP4. **Separate** from the live stream encoder. Video source: CameraX `ImageAnalysis` (or shared with Buffer-mode streaming). Audio: registers as listener to `AudioSourceEngine`. Start/stop and file I/O run on **`recordingExecutor`** so they are never starved by the Command Bus. When recording is started **remotely** (`START_RECORDING` from Viewer), the service calls `startStreamingAudio()` before `startRecording(true)` so the saved file includes audio. **Camera switch during recording** is supported: after each camera rebind, the recording sink is re-applied so the same file captures the full duration. |

### 2.7. Low-Power Idle Mode

When **no viewers** are connected, **not recording**, and **Primary UI is not visible**, the service enters **low-power idle mode**: resolution and FPS are reduced (e.g. 480×640 @ 15 fps, bitrate capped), and the last “active” config is stored. When a viewer connects, recording starts, or the UI becomes visible again, the previous high-quality config is restored. This reduces battery and thermal load when the camera is unattended.

**Extensions:**
- **Battery/charging-aware profiles**: When entering idle, if unplugged and battery < 20%, bitrate cap is 600 kbps (else 900 kbps). Battery state is monitored via `BatteryManager` / `ACTION_BATTERY_CHANGED`.
- **Idle entry hysteresis**: 10 s delay before entering low-power to avoid flapping on brief viewer disconnects.
- **Thermal tier scaling**: Progressive encoder throttling by thermal status (MODERATE 10%, SEVERE 30%, CRITICAL 50%, EMERGENCY 70% bitrate reduction). MODERATE tier available on API 31+.
- **Thermal debounce**: 15 s debounce before applying thermal throttling to ignore brief thermal spikes.

### 2.8. CameraForegroundService (Orchestrator) Summary

- **Lifecycle:** Binds CameraX (Preview, optional ImageAnalysis, optional VideoCapture for combo probe); applies `DeviceProfile` (resolution, FPS cap, buffer vs surface).
- **Profile:** On start, calls `RuntimeProbe.ensureProfile()`; on first capture, may run `ActivePipelineProber.probe()` and persist the result into the profile.
- **Streaming:** Creates `CommandBus(controlExecutor) { handleStreamCommand(it) }`, `FrameBus(capacity)`, `StreamServer(port, passwordProvider, commandBus, frameBus)`. Registers encoder `EncodedFrameListener` → `streamServer.enqueueFrame(frame)`. Starts `StreamServer`; subscribes `StreamAudioSender` to `AudioSourceEngine` when streaming is active.
- **Encoder recovery:** On `onRecoveryNeeded` or encoder watchdog (no keyframe for too long), posts `RecoverEncoder`; `handleStreamCommand(RecoverEncoder)` performs stop/start with cooldown.
- **Encoder resolution:** StreamServer uses `encoderWidthProvider` / `encoderHeightProvider` so STREAM_ACCEPTED and stream dimensions match actual encoder output (e.g. Buffer mode 720×960).

---

## 3. CCTVViewer (Client) Internals

The Viewer focuses on **low-latency** decoding and **robust state management** with server-authoritative stream state.

### 3.1. Core Components

| Component | Role |
|-----------|------|
| **`StreamClient`** | Network I/O, protocol parsing, **ConnectionState** machine, decode queue, jitter buffering, watchdogs (handshake, stream health, reconnect with backoff). Obeys **STREAM_STATE** from the server (ACTIVE, RECONFIGURING, PAUSED, STOPPED). |
| **`MainActivity`** | Compose UI; hosts `SurfaceView` or `TextureView` for decoded video; touch (zoom/pan); connects to `StreamClient`. |
| **Video pipeline** | Frames from socket → decode queue → **MediaCodec** decoder → Surface. Epoch and sequence checks; frames for wrong epoch are dropped. |
| **Audio** | Downstream audio (AAC/PCM) → decode/play via **AudioTrack**. Talkback: **AudioRecord** → upstream PCM to Primary. |

### 3.2. Viewer State

`ConnectionState` (UI and client logic): **DISCONNECTED**, **CONNECTING**, **CONNECTED** (“No Video”), **AUTHENTICATED**, **STREAMING**, **RECOVERING**, **IDLE** (stream intentionally stopped by server, STREAM_STATE|4). The server is the authority for stream state; the Viewer does not infer STREAMING from CSD/keyframe alone when STREAM_STATE is in use. See **VIEWER_ARCHITECTURE.md** and **PROTOCOL_REFERENCE.md**.

---

## 4. Key Architectural Patterns

### A. Message-Driven, No Cross-Layer Sync

- **Command Bus:** All remote commands go through the Command Bus. Only the control thread runs encoder/camera/recording logic for commands.
- **FrameBus:** Encoder pushes frames into FrameBus; sender thread pulls and fans out. No session thread ever blocks on encoder or camera.

### B. Zero-Copy Video Path (When Possible)

- **Surface input:** Camera → Surface → Encoder. No CPU touch of pixels. Used when the profile and capability checks allow it.
- **Buffer input:** Camera → ImageAnalysis (YUV) → convert → `queueInputBuffer()`. Fallback for LEGACY/LIMITED or when Surface fails (probe or runtime).

### C. Capability- and Hardware-Level–Driven Policy

- Pipeline choices (buffer vs surface, FPS cap, dynamic bitrate) come from **Camera2 hardware level** and **codec capabilities**, not from device name. `CameraHardwareLevelPolicy` and `DeviceProfile` are the source of truth. `DeviceQuirks` is mostly UI-only; `forceBufferInputMode()` is the exception for known-bad devices.

### D. Backpressure & Drop Policy

- **FrameBus:** Bounded; when full, **DROP_NON_KEYFRAME_ON_FULL** (prioritize keyframes). See THREADING.md.
- **ViewerSession:** Per-session frame queue with load shedding (drop non-keyframes when queue is backed up) so one slow viewer does not unboundedly grow memory.

### E. Stream Epoch & Server-Authoritative State

- **Epoch:** Incremented on encoder restart/reconfigure. Every CSD and FRAME carries epoch; Viewer drops data for wrong epoch.
- **STREAM_STATE:** Server sends numeric state (ACTIVE, RECONFIGURING, PAUSED, STOPPED). Viewer must follow it; ACTIVE is sent only after the first keyframe for that epoch.

### F. Seamless Bitrate Change; Rotation via Metadata

- **Bitrate:** `MediaCodec.setParameters(Bundle)` for on-the-fly bitrate change without encoder restart.
- **Rotation:** Stream pixels are not rotated; **ENC_ROT|deg=...** tells the Viewer how to rotate the display.

### G. Audio/Video Sync

- PTS from source (`System.nanoTime()` → microseconds). Viewer can use them for sync; low-latency “push” (render immediately) is often preferred for surveillance.

---

## 5. References

- **THREADING.md** – Execution domains, FrameBus, Command Bus, golden rules.
- **SESSION_LIFECYCLE.md** – Session states and allowed transitions (Primary and Viewer).
- **PROTOCOL_REFERENCE.md** – All protocol commands and fields.
- **STREAMING.md** – Fan-out, epochs, adaptive bitrate, backpressure.
- **CONNECTIONS.md** – Handshake, authentication, session management, heartbeats.
