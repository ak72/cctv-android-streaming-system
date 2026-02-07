# Project Structure & Dependencies

## 1. Workspace
The workspace contains two distinct Android Studio projects:
1.  **`CCTVPrimary/`**: The Camera App (Server).
2.  **`CCTVViewer/`**: The Monitor App (Client).

## 2. Directory Map

### Common Structure
Both apps follow the standard Android architecture:
*   `app/src/main/java/com/anurag/cctv...`: Kotlin Source code.
*   `app/src/main/res`: XML Resources (Icons, Strings, Themes).
*   `Docs/`: Documentation (You are here).

### Key Files (Primary)
*   `VideoEncoder.kt`: MediaCodec wrapper.
*   `StreamServer.kt`: TCP Server implementation.
*   `CameraForegroundService.kt`: Headless camera adaptation.
*   **Startup & Resilience**:
    *   `ActivePipelineProber.kt`: Validates camera/encoder pipelines on first launch.
    *   `DeviceProfileManager.kt` & `DeviceProfileStore.kt`: Persists device capabilities and "safe" configurations.
    *   `DeviceQuirks.kt`: Hardcoded known issues for specific models.

### Key Files (Viewer)
*   `StreamClient.kt`: TCP Client & Jitter Buffer.
*   `MainActivity.kt`: UI & Rendering logic (SurfaceView/TextureView).
*   **Protocol & Helpers**:
    *   `ConnectionState.kt`, `StreamConfig.kt`: Shared data types.
    *   `StreamClientProtocol.kt`: Packet definition helpers.
    *   `DeviceQuirks.kt`: Renderer workarounds.

## 3. Libraries & Tech Stack

### User Interface
*   **Jetpack Compose**: 100% of the UI is built with Compose.
*   `Material3`: Design system.

### Camera & Media
*   **CameraX**: `androidx.camera:camera-*` for easier hardware access.
*   **MediaCodec**: *Direct API usage*. We do NOT use ExoPlayer or Media3 for the stream because we need raw low-latency control.

### Concurrency
*   **Java Threads**: Used for long-running loops (Network, Encoding) to avoid GC overhead of coroutines in tight loops.
*   **Coroutines**: Used for UI-bound asynchronous tasks.
