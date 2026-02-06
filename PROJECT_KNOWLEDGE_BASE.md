# Project Knowledge Base

> [!IMPORTANT]
> This document consolidates "tribal knowledge," debugging war stories, and critical technical constraints found during development. Read this before attempting to "optimize" core pipelines.

## 1. Device-Specific Quirks & Workarounds

Android fragmentation is the primary challenge in this project. 

> [!NOTE]
> **Evolution of Device Logic**: We previously used `DeviceQuirks.kt` to hardcode fixes for specific models. This has been superseded by `CameraHardwareLevelPolicy.kt`, which drives logic based on **Camera2 Hardware Level** (LEGACY/LIMITED/FULL) and **Capabilities**. `DeviceQuirks` is now primarily used for **UI display hints** only.

We maintain a history of the issues that led to these architectural decisions below.

### Samsung Galaxy M30s (Exynos 9611 / Android 11)
*   **Issue**: `MediaCodec` encoder crashes the entire OS (`libc` fault) if we attempt to reconfigure the bitrate/resolution while the session is active in **Surface Mode**.
*   **Issue**: `SurfaceView` renders black frames in the Viewer, even though the decoder reports success.
*   **Fixes**:
    *   **Primary Side**: Use **Buffer Mode** (ByteBuffer input). The `ActivePipelineProber` should automatically detect this failure and downgrade.
    *   **Viewer Side**: Force **TextureView** instead of `SurfaceView`.

### OnePlus Nord CE4 (Snapdragon 7 Gen 3 / Android 14)
*   **Issue**: "Green Flash" on startup. The first encoded frame often contains garbage YUV data.
*   **Fix**: The Viewer drops the first 2-3 frames after a connection is established before making the surface visible (`StreamClient` logic).
*   **Issue**: CameraX fails to bind multiple use cases (Preview + Analysis + Recorder) simultaneously due to hardware resource limits.
*   **Fix**: We use `ActivePipelineProber` to verify the "Combo" support. If it fails, we disable local recording while streaming or downgrade resolution.

### RealMe Narzo N65 5G (Android 16)
*   **Status**: Reference Device. Works correctly with Zero-Copy Surface Mode and standard `SurfaceView` rendering.

## 2. Technical Constraints

### A. 16KB Page Alignment
Android 15+ devices (Pixel 9, etc.) require native libraries and memory mappings to be 16KB aligned.
*   **Impact**: Ensure any NDK dependencies (if added later) are built with `-Wl,-z,max-page-size=16384`.
*   **Current State**: Our Java/Kotlin implementation is safe, but be careful if adding ffmpeg or other native libs.

### B. Scoped Storage
We cannot write to arbitrary file paths (e.g., `/sdcard/CCTV`).
*   **Solution**: `CustomRecorder.kt` uses `MediaStore` API to write video files to the public `DCIM/` folder.
*   **Note**: We do not need `MANAGE_EXTERNAL_STORAGE` permission, which is hard to get approved on Play Store.

### C. Foreground Service Types
Android 14+ requires declaring specific foreground service types.
*   **Manifest**: `<service ... android:foregroundServiceType="camera microphone" />`
*   **Runtime**: We must request `postNotifications` permission to show the persistent notification.

## 3. Architecture Decisions

### Why Independent Apps?
*   **Decision**: Split `CCTVPrimary` (Server) and `CCTVViewer` (Client) into two apps.
*   **Reason**: Distinct permission profiles. The Camera app needs Camera/Mic. The Viewer app only needs Mic (for talkback) and Network. This simplifies security review and user trust.

### Why TCP and not UDP/RTP?
*   **Decision**: Custom framed protocol over TCP.
*   **Reason**:
    1.  **Simplicity**: Handling packet loss/reordering in UDP requires complex logic (jitter buffers, NACKs).
    2.  **Firewalls**: TCP (HTTP-like) usually punches through routers easier than UDP if we eventually add cloud relaying.
    3.  **Head-of-Line Blocking**: For real-time low-latency video, TCP HOL blocking is a downside, but on modern WiFi 5/6, packet loss is low enough that TCP is acceptable for <200ms latency.

### Why "Active Pipeline Prober"?
*   **History**: We used to rely on `Camera2Capabilities` (static checks).
*   **Failure**: Many devices *claim* to support 4k@60fps or specific color formats, but crash when you actually call `configure()`.
*   **Solution**: The "Prober" actually *runs* the pipeline for 200ms. If it doesn't output a frame, we mark it bad. This "Show, Don't Tell" approach is the only reliable way to handle cheap Android hardware.

### Recording vs. Streaming Pipeline
*   **Architecture**: Decoupled.
    *   **Streaming**: Uses `VideoEncoder` (usually Surface Input for zero-copy performance).
    *   **Recording**: Uses `CustomRecorder` (MediaCodec + Muxer) fed by `ImageAnalysis` (ByteBuffer input).
*   **Reason**: Allows recording to use different resolutions/aspect ratios than the real-time stream. Specifically, we enforce a 4:3 (or device-native) aspect ratio for recording to ensure consistent Field of View (FOV) regardless of the streaming resolution.

## 4. Debugging War Stories

### Viewer Connection Deadlock (Reconfiguring State)
*   **Symptoms**: Viewer connects, transitions to `RECONFIGURING`, but never enters `STREAMING` (video stays black or loading).
*   **Cause**: The State Machine requires a **KeyFrame** to propagate the `STREAMING` state transition. However, `ViewerSession.kt` was dropping all frames (including KeyFrames) while in `RECONFIGURING` state, creating a catch-22.
*   **Fix**: Modified `ViewerSession` to explicitly accept frames during `RECONFIGURING` state.

## 6. Self-Healing & Supervisor

### A. Stream Supervisor (Encoder Watchdog)
*   **Problem**: Encoders can silently stall (input frames accepted, no output) or stop producing Keyframes (artifacts/gray screen).
*   **Solution**: `VideoEncoder` tracks input/output timestamps.
    *   **Stall**: No output for >5s -> Request recovery.
    *   **Keyframe Drought**: No Keyframe for 2x GOP -> Request Sync Frame. No Keyframe for 3x GOP -> Request recovery.
*   **Recovery**: `CameraForegroundService` triggers a "soft restart" (stop/start encoder) while keeping the session/socket alive.

### B. Viewer Watchdog
*   **Problem**: Viewer receives Pongs (socket alive) but no video frames (encoder/network blocking).
*   **Solution**: `StreamClient.checkStreamHealth()`.
    *   **Drought**: No frames for >2s -> Request Keyframe.
    *   **Stuck**: No frames for >10s -> Force Disconnect/Reconnect.


### A. FrameBus Polling (Zero-Deadlock)
*   **Pattern**: Do **NOT** use `take()` (blocking) on shared queues like `FrameBus`.
*   **Fix**: Use `pollWithTimeout(ms)` in consumers (e.g., StreamServer sender loop).
*   **Reason**: Blocking `take()` makes clean thread shutdown difficult without a special "poison pill" object. `pollWithTimeout` allows the thread to check its `running` flag periodically.

### B. Thread Safety Assertions
*   **Pattern**: Fail fast if a critical method runs on the wrong thread.
*   **Implementation**:
    *   `check(Looper.myLooper() == Looper.getMainLooper())` for UI/Lifecycle methods.
    *   `@Volatile private var owningThread: Thread? = null` logic for custom executor threads (e.g., `CommandBus` consumer).
*   **Reason**: Prevents silent race conditions where a heavy operation (like DB access or Encoder start) accidentally runs on the latency-sensitive Control Thread.

### C. Offloading Heavy Ops
*   **Rule**: Never run File I/O, DB access, or MediaMuxer start/stop on the **Control Thread**.
*   **Solution**: Offload `startRecording`/`stopRecording` to `recordingControlExecutor`. The Control Thread acts only as a dispatcher.
