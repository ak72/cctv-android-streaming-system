# Project Architecture

## 1. CCTVPrimary (Server) Internals

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

---
