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
