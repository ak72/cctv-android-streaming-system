# Project Knowledge Base

> [!IMPORTANT]
> This document consolidates "tribal knowledge," debugging war stories, and critical technical constraints found during development. Read this before attempting to "optimize" core pipelines.

## 1. Device-Specific Quirks & Workarounds

Android fragmentation is the primary challenge in this project. We maintain a `DeviceQuirks.kt` file to handle these programmatically, but here is the comprehensive history.

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
