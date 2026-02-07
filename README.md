# Project Concept

## 1. Introduction

**Project Name**: CCTV (CCTVPrimary & CCTVViewer)

This project allows users to repurpose older or spare Android smartphones as effective CCTV usage security cameras. It consists of two distinct applications:

1.  **CCTVPrimary (The Camera)**: Installed on the device acting as the surveillance camera. It effectively turns the phone into a server that captures video/audio and streams it over the local network (or internet given proper routing).
2.  **CCTVViewer (The Monitor)**: Installed on the device used to view the live feed. It acts as a client, connecting to the Primary device to display video, play audio, and send control commands.

## 2. Core Value Proposition

- **Reuse of Hardware**: Gives new life to old devices that are otherwise functional but unmatched for daily driver tasks.
- **Zero Cost Security**: Eliminates the need for expensive proprietary CCTV hardware and subscriptions.
- **Privacy First**: Direct peer-to-peer (P2P) streaming over TCP/IP ensuring data remains local or directly controlled by the user, without intermediate cloud storage servers.
- **High Performance**: Optimized for low latency and smooth framerates using hardware-accelerated encoding/decoding and raw TCP sockets.

## 3. Key Features

- **High-Efficiency Streaming**: Uses H.264 video compression and AAC/PCM audio for efficient bandwidth usage.
- **Two-Way Audio (Talkback)**:
  - **Listen**: Hear what is happening around the camera (Primary -> Viewer).
  - **Speak**: Talk through the viewer to the camera (Viewer -> Primary), useful for intercom functionality or deterring intruders.
- **Video Recording**: Capabilities to record video locally on the Primary device for evidence or later review.
- **Remote Control**:
  - **Flashlight**: Toggle the camera flash remotely for night visibility.
  - **Camera Switch**: Switch between front and back cameras.
  - **Quality Control**: Adjust resolution and bitrate dynamically based on network conditions.
- **Robust Connectivity**: Auto-reconnection logic and session resumption to handle network fluctuations properly.

## 4. Design Philosophy

- **"Mobile-First" Architecture**: Recognizes the constraints of mobile devices (battery, thermal limits, variable network). Features like the "Zero-Copy Pipeline" are implemented specifically to reduce CPU load and heat.
- **Resiliency**: The system is designed as a state machine. It expects network failures and recovers gracefully without crashing or leaving the app in an inconsistent state.
- **Device Agnostic**: While optimized, it includes fallback mechanisms (e.g., Buffer Mode vs. Surface Mode) to support a wide range of Android versions and manufacturer quirks (Samsung, Realme, etc.).

## 5. Intended User Experience

The user should be able to launch `CCTVPrimary` on an old phone, place it on a shelf, and forget about it. On their daily phone (`CCTVViewer`), they can open the app, instantly see the live feed, hear the room, and talk back if necessary, with no lag and high visual clarity.

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

| Component                       | Role                                 | Behavior                                                                                                                                                                                                                                                                                                                                                          |
| ------------------------------- | ------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`RuntimeProbe`**              | Ensure profile at runtime            | Calls `DeviceProfileManager.getOrCreate()`. No heavy probing here; just build or load a capability-driven profile.                                                                                                                                                                                                                                                |
| **`DeviceProfileManager`**      | Build / load device profile          | Reads **Camera2** capabilities (`Camera2Capabilities`) and **codec** capabilities (`CodecCapabilities`). Applies **`CameraHardwareLevelPolicy`** to set `preferBufferMode`, `allowFpsGovernor`, `allowDynamicBitrate`. Builds a quality ladder. If `DeviceProfileStore` has a matching profile (fingerprint + camera), returns it; otherwise builds and persists. |
| **`CameraHardwareLevelPolicy`** | Policy from hardware level           | **LEGACY** / **LIMITED** → prefer buffer input, fixed FPS cap (e.g. 24), no dynamic bitrate recovery. **FULL** / **LEVEL_3** → allow FPS governor and dynamic bitrate. No brand-specific hacks.                                                                                                                                                                   |
| **`DeviceProfileStore`**        | Persist profile                      | Saves `DeviceProfile` (JSON) keyed by firmware fingerprint + cameraId + lensFacing.                                                                                                                                                                                                                                                                               |
| **`ActivePipelineProber`**      | Optional active probe (first launch) | Runs short test binds (Surface then Buffer mode, descending resolutions). Success = at least one encoded frame within timeout. Result (e.g. “use Buffer, max 720p”) is written into the profile. Used when the service needs an empirically validated starting config.                                                                                            |
| **`EncoderProbeStore`**         | Persist Surface input failure        | If Surface input fails at runtime (or in probe), marks “Surface input bad” so future launches prefer Buffer mode without re-probing.                                                                                                                                                                                                                              |
| **`EncoderCapabilityDetector`** | Codec capability check               | Queries `MediaCodecList` for AVC Surface input support.                                                                                                                                                                                                                                                                                                           |
| **`DeviceQuirks`**              | UI / display hints only              | Legacy device hints; **not** used for pipeline decisions. Pipeline policy is in `CameraHardwareLevelPolicy` and the profile.                                                                                                                                                                                                                                      |
| **`SizeSelector`**              | Resolution selection                 | Intersects preview/YUV/recorder sizes; picks 4:3 portrait candidates; used when building the profile ladder.                                                                                                                                                                                                                                                      |

### 2.2. Threading & Executors

All streaming and capture use **shared executors** to avoid thread explosion and improve thermal behavior. See **`StreamingExecutors.kt`** and **`Docs/THREADING.md`**.

| Executor                | Use                                                                                                                                                                                     |
| ----------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`ioExecutor`**        | StreamServer accept loop, other I/O.                                                                                                                                                    |
| **`senderExecutor`**    | Single thread that drains the **FrameBus** and fans out frames to all `ViewerSession`s.                                                                                                 |
| **`controlExecutor`**   | **Command Bus** consumer (infinite loop); camera bind/unbind. Only this thread holds `encoderLock` for commands. Recording start/stop do **not** run here—they use `recordingExecutor`. |
| **`recordingExecutor`** | Start/stop recording and CustomRecorder file/MediaMuxer work. Dedicated so recording is never starved by the Command Bus loop.                                                          |
| **`encodingExecutor`**  | ImageAnalysis and encoding-related work (kept separate from control to avoid stalls).                                                                                                   |
| **`sessionPool`**       | Fixed pool for per-session work (listener, video sender, audio sender, heartbeat). Caps total session threads.                                                                          |

**Golden rule:** Session and accept threads **never** call into the encoder or camera directly. They post to the **Command Bus**; the control thread runs `handleStreamCommand()`.

### 2.3. Command Bus & Control Flow

Remote commands (keyframe request, start/stop recording, backpressure, zoom, reconfigure, encoder recovery) are **posted** to a **Command Bus**. A single consumer runs on **`controlExecutor`** and executes them.

| Component                                           | Role                                                                                                                                                                                           |
| --------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`CommandBus`**                                    | Bounded queue of `StreamCommand`; consumer loop runs on `StreamingExecutors.controlExecutor`.                                                                                                  |
| **`StreamCommand`**                                 | Sealed class: `RequestKeyframe`, `StartRecording`, `StopRecording`, `ReconfigureStream`, `Backpressure`, `PressureClear`, `AdjustBitrate`, `SwitchCamera`, `Zoom`, `RecoverEncoder`.           |
| **`CameraForegroundService.handleStreamCommand()`** | Runs **only** on the control thread. Holds `encoderLock` when touching the encoder; posts recording start/stop to **`recordingExecutor`** (actual MediaStore/File/MediaMuxer work runs there). |

Thus: **ViewerSession** (or StreamServer) receives a protocol command → converts to `StreamCommand` → `commandBus.post(cmd)` → control thread → `handleStreamCommand(cmd)` → encoder/camera/recording. No synchronous cross-layer calls from session threads into the pipeline.

### 2.4. Video Path: Encoder → FrameBus → Sessions

| Component           | Role                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **`VideoEncoder`**  | Wraps `MediaCodec`. Supports **Surface input** (zero-copy, preferred) and **ByteBuffer input** (fallback). Mode chosen by profile (`preferBufferMode`), user override (“Force Buffer Mode”), or runtime/EncoderProbeStore failure. Produces H.264; notifies `EncodedFrameListener` per frame.                                                                                                                                                                                                                                                                            |
| **`FrameBus`**      | Bounded queue (capacity 60). **Single producer**: encoder output is pushed via `StreamServer.enqueueFrame(frame)` (which calls `frameBus.publish(frame)`). **Single consumer**: sender loop on `senderExecutor` drains with `pollWithTimeout(ms)` (no blocking `take()`), then fans out to each `ViewerSession.enqueueFrame()`. **Drop policy:** DROP_NON_KEYFRAME_ON_FULL (keyframes prioritized; non-keyframes dropped when full). See `FrameBus.kt` KDoc and **THREADING.md**.                                                                                        |
| **`StreamServer`**  | Holds the `FrameBus` and `CommandBus`. Accept loop on `ioExecutor`; for each client, creates a **`ViewerSession`**. Encoder callback → `streamServer.enqueueFrame(frame)` → `frameBus.publish()`. Sender loop (on `senderExecutor`) pulls from FrameBus and calls `session.enqueueFrame(toSend)` for each session. Tracks active session count; triggers **low-power idle mode** when no viewers and not recording and UI not visible.                                                                                                                                   |
| **`ViewerSession`** | Per-client session. **Input:** listener thread (from session pool) reads protocol (HELLO, AUTH, CAPS, SET_STREAM, PING, etc.). **Output:** dedicated sender task (session pool) drains per-session frame queue and control queue; writes STREAM_STATE, CSD, FRAME, etc. **Stream epoch:** incremented on encoder restart/reconfigure; Viewer must drop frames for wrong epoch. **STREAM_STATE** is server-authoritative (numeric codes + epoch). Session lifecycle: CONNECTING → AUTHENTICATED → STREAMING / RECONFIGURING → DISCONNECTED. See **SESSION_LIFECYCLE.md**. |

**Encoder self-healing:** `VideoEncoder` detects (1) **stall** (input advancing, no output for >5s) and (2) **keyframe drought** (no keyframe for 2× then 3× GOP). It invokes `onRecoveryNeeded`; the service posts `RecoverEncoder` to the Command Bus. The control thread performs a soft encoder restart (stop/start) with a **cooldown** (e.g. 90s, or 180s on low-tier hardware) to avoid repeated restarts on unstable devices.

### 2.5. Audio Path

| Component               | Role                                                                                                                                                                                                 |
| ----------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- | ---------- | ------ |
| **`AudioSourceEngine`** | Singleton; single `AudioRecord`. Multiple consumers register as listeners and receive copies of the same PCM. Reference counting: streaming and recording increment refs; mic runs when any ref > 0. |
| **`StreamAudioSender`** | Listener that receives PCM from `AudioSourceEngine`; applies volume boost and soft limiter; encodes to AAC (e.g. 64 kbps); sends downstream via `StreamServer` (e.g. `AUDIO_FRAME                    | dir=down | format=aac | ...`). |
| **Talkback**            | Viewer sends PCM upstream. `StreamServer` is configured with `onAudioFrameUp`; the service plays chunks via a dedicated **AudioTrack** (noise gate applied to avoid hiss).                           |

### 2.6. Recording

| Component            | Role                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| -------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`CustomRecorder`** | Uses `MediaCodec` + `MediaMuxer` for MP4. **Separate** from the live stream encoder. Video source: CameraX `ImageAnalysis` (or shared with Buffer-mode streaming). Audio: registers as listener to `AudioSourceEngine`. Start/stop and file I/O run on **`recordingExecutor`** so they are never starved by the Command Bus. When recording is started **remotely** (`START_RECORDING` from Viewer), the service calls `startStreamingAudio()` before `startRecording(true)` so the saved file includes audio. **Camera switch during recording** is supported: after each camera rebind, the recording sink is re-applied so the same file captures the full duration. |

### 2.7. Low-Power Idle Mode

When **no viewers** are connected, **not recording**, and **Primary UI is not visible**, the service enters **low-power idle mode**: resolution and FPS are reduced (e.g. 480×640 @ 15 fps, bitrate capped), and the last “active” config is stored. When a viewer connects, recording starts, or the UI becomes visible again, the previous high-quality config is restored. This reduces battery and thermal load when the camera is unattended.

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

| Component          | Role                                                                                                                                                                                                                                       |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **`StreamClient`** | Network I/O, protocol parsing, **ConnectionState** machine, decode queue, jitter buffering, watchdogs (handshake, stream health, reconnect with backoff). Obeys **STREAM_STATE** from the server (ACTIVE, RECONFIGURING, PAUSED, STOPPED). |
| **`MainActivity`** | Compose UI; hosts `SurfaceView` or `TextureView` for decoded video; touch (zoom/pan); connects to `StreamClient`.                                                                                                                          |
| **Video pipeline** | Frames from socket → decode queue → **MediaCodec** decoder → Surface. Epoch and sequence checks; frames for wrong epoch are dropped.                                                                                                       |
| **Audio**          | Downstream audio (AAC/PCM) → decode/play via **AudioTrack**. Talkback: **AudioRecord** → upstream PCM to Primary.                                                                                                                          |

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

- Pipeline choices (buffer vs surface, FPS cap, dynamic bitrate) come from **Camera2 hardware level** and **codec capabilities**, not from device name. `CameraHardwareLevelPolicy` and `DeviceProfile` are the source of truth; `DeviceQuirks` is UI-only.

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

# Project Workflows

## 0. First Launch / Probing (One-Time)

Before the first connection is possible:

1.  **Permission Grant**: User accepts Camera/Audio permissions.
2.  **Auto-Probe**: `ActivePipelineProber` silently tests different encoder configurations.
    - _Step A_: Try 1080p @ 30fps (Surface Mode). If crash/timeout ->
    - _Step B_: Try 720p @ 30fps (Surface Mode). If crash/timeout ->
    - _Step C_: Try 720p @ 30fps (**Buffer Mode**).
3.  **Profile Saved**: The working configuration is persisted via `DeviceProfileStore` (SharedPreferences JSON, keyed by firmware fingerprint + camera).
4.  **Ready**: The "Start Capture" button becomes enabled.

## 1. Connection & Handshake Workflow

This process establishes the session between Viewer and Primary. For a consolidated list of every protocol command and its fields, see [Protocol & Message Fields](PROTOCOL_REFERENCE.md#protocol--message-fields-consolidated).

1.  **Discovery/Initiation**: User enters IP/Port in Viewer and clicks "Connect".
2.  **TCP Connection**: `StreamClient` opens a socket to `StreamServer`.
3.  **Protocol Handshake**:
    - **Viewer**: Sends `HELLO|client=viewer|version=1` or `version=2` (version 2 = supports server-authoritative STREAM_STATE).
    - **Primary**: Responds `AUTH_CHALLENGE|v=2|salt={salt}`.
    - **Viewer**: Sends `AUTH_RESPONSE|hash={hmac_sha256(password, salt)}`.
    - **Primary**:
      - On success: Sends `AUTH_OK`, allocates a unique Session ID and sends `SESSION|id={uuid}`.
      - On failure: Sends `AUTH_FAIL` and closes the socket.
4.  **Capability Negotiation**:
    - **Viewer**: Sends `CAPS|maxWidth=...|maxHeight=...` (Device limits).
    - **Primary**: Responds `CAPS_OK`.
5.  **Stream Setup**:
    - **Viewer**: Sends `SET_STREAM|width=1440|height=1080|fps=30|bitrate=2000000`.
      - Note: newer Viewers/Primaries may include extra params; unknown params are ignored by the receiver as long as framing stays correct.
    - **Primary**:
      - Arbitrates the request against current Hardware limitations.
      - Configures/Reconfigures the `VideoEncoder` if necessary (or joins existing stream).
      - Sends `STREAM_ACCEPTED|width=...|...` confirming actual parameters.
    - **Primary**: Sends `CSD` (Codec Specific Data - SPS/PPS) immediately.
6.  **State**: Both parties transition to **STREAMING**.

## 2. Video Streaming Loop

Once in **STREAMING** state:

1.  **Capture**: `CameraX` Preview sends texture data to the Input Surface of `VideoEncoder`.
2.  **Encode**: `MediaCodec` (Primary) processes the frame -> Output Buffer (H.264 NALUs).
3.  **Packetize**:
    - `StreamServer` wraps the data: `FRAME|epoch=...|key=true/false|tsUs=...|size=...`.
    - Appends payload size and payload data.
4.  **Transmit**: Data sent over TCP Socket.
5.  **Receive**: `StreamClient` (Viewer) reads the header, allocates buffer, reads payload.
6.  **Decode**:
    - Client checks identifying header. If `FRAME`:
    - Feeds data into `MediaCodec` (Viewer) Input Buffer.
7.  **Render**: `MediaCodec` decodes to the Viewer's `Surface`, instantly visible on screen.

## 3. Audio Streaming (Two-Way)

### Downstream (Primary -> Viewer)

1.  **Source**: `AudioSourceEngine` captures MIC data (PCM 16-bit, 48kHz).
2.  **Process**: Hardware Noise Suppression / AEC applied.
3.  **Route**: Data passed to `StreamServer`.
4.  **Send**: `AUDIO_FRAME|dir=down|...` sent over TCP.
5.  **Play**: Viewer receives, queues into a jitter buffer, and plays via `AudioTrack`.

### Upstream / Talkback (Viewer -> Primary)

1.  **Trigger**: User holds "Mic" button on Viewer.
2.  **Source**: Viewer captures MIC data.
3.  **Send**: `AUDIO_FRAME|dir=up|...` sent over TCP.
4.  **Receive**: Primary `ViewerSession` receives packet.
5.  **Play**: `CameraForegroundService` takes data and writes to a dedicated High-Priority `AudioTrack`.

## 4. Adaptive Quality Workflow

To handle bad networks without reconnecting:

1.  **Detection**: Viewer detects High Latency or Emptying/Full Buffers.
2.  **Request Change**:
    - **Bitrate**: Viewer sends `ADJUST_BITRATE|bitrate=500000`.
    - **Primary**: Calls `encoder.setParameters(bitrate=500000)`. **No Black Screen.**
3.  **Request Resolution** (Severe Downgrade):
    - Viewer sends `SET_STREAM|width=640|height=480...`.
    - **Primary (Standard Mode)**: MUST restart encoder (Brief hiccup).
    - **Primary (Buffer Mode / Stability Path)**:
      - Used on problematic devices (e.g., Samsung M30s) where restarts cause crashes (`libc`).
      - **Ignores resolution/FPS changes** to prevent native crashes.
      - Only adjusts bitrate. The stream remains active and stable.
    - Primary sends `STREAM_ACCEPTED` with new dims.
    - Viewer reconfigures Decoder.
    - Stream resumes.

## 5. Recording Workflow

1.  **Trigger**: User clicks "Record" on Primary UI or on the Viewer (sends `START_RECORDING`). When triggered from the Viewer, the Primary ensures streaming audio is started before starting the recorder so the file includes audio.
2.  **Initialization**: `CameraForegroundService` initializes `CustomRecorder`.
3.  **Pipeline Split**:
    - CameraX `ImageAnalysis` (or secondary Surface) feeds the `CustomRecorder`.
    - _Note_: Streaming continues efficiently via the Zero-Copy surface path; Recording runs parallel (potentially using CPU for YUV if ImageAnalysis is used, or a second surface).
4.  **Audio**: `CustomRecorder` registers as a listener to `AudioSourceEngine`, receiving a copy of the existing mic feed.
5.  **Muxing**: Video + Audio written to `.mp4` file.
6.  **Camera switch**: If the user switches camera (Primary or Viewer) during recording, the pipeline rebinds and the recording sink is re-applied; the same file continues for the full duration.
7.  **Stop**: File finalized and added to MediaStore.

## 6. Device Rotation Workflow

1.  **Event**: Primary device rotates 90 degrees.
2.  **No Restart**: Encoder continues running as-is (Width/Height don't flip in the encoder).
3.  **Metadata**: Primary sends `ENC_ROT|deg=90`.
4.  **Client Action**: Viewer UI reads this packet and rotates the `TextureView` container 90 degrees to match.
5.  **Result**: Smooth rotation without stream interruption.
