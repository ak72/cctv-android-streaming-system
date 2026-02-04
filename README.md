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

## 1. High-Level System Architecture

The system follows a classic **Client-Server** model communication over **TCP/IP**.

- **Server**: `CCTVPrimary` (The Camera Device)
- **Client**: `CCTVViewer` (The Display Device)

```mermaid
graph LR
    subgraph Primary Device [CCTVPrimary]
        Camera[CameraX] --> Encoder[VideoEncoder H.264]
        Mic[Microphone] --> AudioEngine[AudioSourceEngine]
        Encoder --> StreamServer[Stream Server TCP]
        AudioEngine --> StreamServer
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

The `CCTVPrimary` application is built around a Foreground Service to ensure long-running operation even when the app is minimized or the screen is off.

### Core Components

1.  **`CameraForegroundService.kt`**:
    - **Role**: The central orchestrator.
    - **Responsibilities**: Manages the CameraX lifecycle, binds camera use cases, initializes the Encoder, manages the StreamServer, and handles foreground service notifications.

### 2.1. Startup & Resilience Layer

Before the main service starts streaming, the app ensures stability on the specific device hardware.

1.  **`ActivePipelineProber.kt`**:
    - **Role**: Pre-flight Check.
    - **Behavior**: On the _very first launch_, it runs a quick invisible series of test streams (Surface Mode @ 30fps -> Buffer Mode @ 30fps -> Lower Resolutions).
    - **Goal**: Detect if the device crashes on SurfaceView input (common on Samsung Exynos) or refuses high resolutions, _before_ the user ever connects.
2.  **`DeviceProfileManager.kt`**:
    - **Role**: Persistence.
    - **Behavior**: Saves the result of the Probe (e.g., "Use Buffer Mode, Max 720p") to a JSON store. Future launches load this profile instantly.

### 2.2. Streaming Components

1.  **`VideoEncoder.kt`**:
    - **Role**: Hardware-accelerated Video Encoding.
    - **Implementation**: Wraps `MediaCodec`.
    - **Pipeline**: Uses a **Zero-Copy Pipeline**.
      - Obtains an Input Surface from `MediaCodec.createInputSurface()`.
      - Passes this Surface directly to CameraX `Preview`.
      - This bypasses CPU memory entirely (no YUV→NV21 conversions), executing strictly on the GPU/DSP.
2.  **`StreamServer.kt`**:
    - **Role**: Network Layer.
    - **Implementation**: A TCP server socket that listens for connections.
    - **Concurrency**: Spawns a dedicated logic handler for each connected client, encapsulated in `ViewerSession`.
3.  **`ViewerSession.kt`**:
    - **Role**: Session Manager.
    - **Responsibilities**: Handshakes, authentication, command parsing (Control channel), and data packetization (multiplexing Video and Audio frames). Handles backpressure logic to prevent buffer bloat.
4.  **`AudioSourceEngine.kt`**:
    - **Role**: Centralized Audio Capture.
    - **Implementation**: A Singleton managing a single `AudioRecord` instance.
    - **Feature**: Allows multiple consumers (Streaming and Recording) to "subscribe" to audio data simultaneously without fighting for hardware mic access. Supports reference counting to auto-start/stop hardware.
5.  **`CustomRecorder.kt`**:
    - **Role**: Local Recording.
    - **Implementation**: Uses `MediaCodec` + `MediaMuxer` to create MP4 files. Separate from the live stream encoder to allow independent resolutions/bitrates if needed.

---

## 3. CCTVViewer (Client) Internals

The `CCTVViewer` focuses on low-latency decoding and robust state management.

### Core Components

1.  **`StreamClient.kt`**:
    - **Role**: The "Brain" of the viewer.
    - **Responsibilities**:
      - **Network IO**: Connects socket, reads raw bytes, parses protocol packets.
      - **State Machine**: Manages transitions (CONNECTING -> HANDSHAKING -> STREAMING -> RECOVERING).
      - **Jitter Buffer**: Queues incoming frames slightly to smooth out network variance.
      - **Adaptive Logic**: Monitors FPS and Queue depth to request Bitrate/Resolution changes.
2.  **`MainActivity.kt`**:
    - **Role**: UI & Rendering.
    - **Responsibilities**: Hosts the `SurfaceView` or `TextureView`. Handles touch events (PTZ - Pan/Tilt/Zoom gestures) and relays them to the client.
3.  **Video Pipeline**:
    - **Decoder**: `MediaCodec` (Async mode or Sync loop).
    - **Rendering**: Decodes directly to the Surface provided by the View. No manual buffer copying.
4.  **Audio Pipeline**:
    - **Playback**: `AudioTrack` running on a high-priority thread to minimize audio glitches.
    - **Talkback**: `AudioRecord` captures local mic -> sends UPSTREAM to Primary.

---

## 4. Key Architectural Patterns

### A. Zero-Copy Video Path

To achieve 30fps at 1080p on mid-range devices without overheating, the project avoids touching video frames with the CPU.

- **Old Way**: Camera -> ImageAnalysis -> CPU YUV Conversion -> ByteBuffer -> Encoder. (High CPU).
- **New Way**: Camera -> Surface -> Encoder. (Zero CPU).

### B. Seamless Reconfiguration

Changing quality usually requires restarting the encoder (which causes a black screen glitch).
This project uses **On-the-fly Parameter Updates**:

- `VideoEncoder` uses `MediaCodec.setParameters(Bundle)` to change bitrate dynamically while running.
- Rotation is handled via **Metadata**: The stream itself doesn't rotate; a metadata packet `ENC_ROT` communicates orientation, and the Viewer UI rotates the Surface. This prevents encoder resets on device rotation.

### C. State Machine Protocol

The communication isn't just a raw stream; it's a strict state machine.

- **Handshake**: `HELLO` -> `AUTH` -> `CAPS` -> `SET_STREAM`.
- **Recovery**: If an error occurs, the client moves to `RECOVERING`, sends a special `RESUME` packet (if supported) or re-handshakes, often without tearing down the UI, providing a "glitch-free" recovery experience.

### D. Audio/Video Sync strategy

- The system uses **Presentation Time Stamps (PTS)** derived from `System.nanoTime()` (converted to microseconds) at the source (Primary).
- The Viewer respects these timestamps relative to the first frame received to maintain sync, though currently, low-latency "push" (render immediately) is often prioritized over strict A/V sync buffering for surveillance use cases.

# Project Workflows

## 0. First Launch / Probing (One-Time)

Before the first connection is possible:

1.  **Permission Grant**: User accepts Camera/Audio permissions.
2.  **Auto-Probe**: `ActivePipelineProber` silently tests different encoder configurations.
    - _Step A_: Try 1080p @ 30fps (Surface Mode). If crash/timeout ->
    - _Step B_: Try 720p @ 30fps (Surface Mode). If crash/timeout ->
    - _Step C_: Try 720p @ 30fps (**Buffer Mode**).
3.  **Profile Saved**: The working configuration is written to `DeviceProfile.json`.
4.  **Ready**: The "Start Capture" button becomes enabled.

## 1. Connection & Handshake Workflow

This process establishes the session between Viewer and Primary.

1.  **Discovery/Initiation**: User enters IP/Port in Viewer and clicks "Connect".
2.  **TCP Connection**: `StreamClient` opens a socket to `StreamServer`.
3.  **Protocol Handshake**:
    - **Viewer**: Sends `HELLO|client=viewer|version=1`
    - **Primary**: Responds `AUTH_CHALLENGE|v=2|salt={salt}`.
    - **Viewer**: Sends `AUTH_RESPONSE|hash={hmac_sha256(password, salt)}`.
    - **Primary**:
      - On success: Sends `AUTH_OK`, allocates a unique Session ID and sends `SESSION|id={uuid}`.
      - On failure: Sends `AUTH_FAIL` and closes the socket.
4.  **Capability Negotiation**:
    - **Viewer**: Sends `CAPS|maxWidth=...|maxHeight=...` (Device limits).
    - **Primary**: Responds `CAPS_OK`.
5.  **Stream Setup**:
    - **Viewer**: Sends `SET_STREAM|width=1440|height=1080|fps=30|bitrate=2000000|ordering=0`.
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

1.  **Trigger**: User clicks "Record" on Primary UI (or remotely).
2.  **Initialization**: `CameraForegroundService` initializes `CustomRecorder`.
3.  **Pipeline Split**:
    - CameraX `ImageAnalysis` (or secondary Surface) feeds the `CustomRecorder`.
    - _Note_: Streaming continues efficiently via the Zero-Copy surface path; Recording runs parallel (potentially using CPU for YUV if ImageAnalysis is used, or a second surface).
4.  **Audio**: `CustomRecorder` registers as a listener to `AudioSourceEngine`, receiving a copy of the existing mic feed.
5.  **Muxing**: Video + Audio written to `.mp4` file.
6.  **Stop**: File finalized and added to MediaStore.

## 6. Device Rotation Workflow

1.  **Event**: Primary device rotates 90 degrees.
2.  **No Restart**: Encoder continues running as-is (Width/Height don't flip in the encoder).
3.  **Metadata**: Primary sends `ENC_ROT|degrees=90`.
4.  **Client Action**: Viewer UI reads this packet and rotates the `TextureView` container 90 degrees to match.
5.  **Result**: Smooth rotation without stream interruption.
