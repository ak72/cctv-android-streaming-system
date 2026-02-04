# Troubleshooting & Known Issues

This project deals with low-level Android hardware (Camera/Codec), which varies wildly between manufacturers. This document tracks specific "Battle Scars" and their fixes.

## 1. Video Rendering Issues

### Samsung Exynos (M30s / M31) - "The Black Screen"
*   **Symptom**: Audio plays, logs show "Frame Rendered", but the screen is pure black.
*   **Cause**: The hardware decoder produces a proprietary color format that `SurfaceView` cannot render directly on some OS versions.
*   **Fix**:
    *   **Detection**: `MainActivity.kt` checks for "Samsung" + "M30s/M31".
    *   **Action**: Forces `TextureView` mode instead of `SurfaceView`.
    *   **Code**: Look for `forceTextureViewDevice` in `MainActivity`.

### OnePlus Nord CE4 - "The Green Flash"
*   **Symptom**: Upon connection, the screen flashes solid green for 1-2 seconds, or remains green.
*   **Cause**: The decoder outputs uninitialized memory buffers before the first valid IDR frame is fully processed.
*   **Fix**:
    *   **Suppression**: `StreamClient.kt` ignores the first few output buffers.
    *   **Gating**: `nordCe4SuppressRenderUntilUptimeMs` prevents `Surface.unlockCanvas` until safety timer expires.

### Blank Screen after Resume (Primary)
*   **Symptom**: Local camera preview remains black after the app crashes and restarts, or resumes from background, even though the service is running.
*   **Cause**: The lifecycle observer blindly trusted `captureState` (thinking it was `PREVIEW`) and skipped re-attaching the `PreviewView`.
*   **Fix**: `CameraForegroundService` now *always* re-attaches the `PreviewView` on `ON_START` events if the service is bound.

## 2. Audio Issues

### Feedback Loop (Echo)
*   **Symptom**: User hears their own voice echoing back loudly when Talkback is on.
*   **Cause**: Physical coupling between Speaker and Mic on the Camera device. `AcousticEchoCanceler` (AEC) is requested but often fails on cheap hardware.
*   **Workaround**:
    *   We cannot fix physics. Users are advised to lower volume.
    *   **Software**: We use `VoiceCommunication` source which attempts to enable hardware DSP AEC.

### Static / White Noise
*   **Symptom**: Loud hiss when the room is silent.
*   **Cause**: High microphone gain amplifying thermal noise.
*   **Fix**: **Noise Gate** in `StreamClient` and `CameraForegroundService`. Audio chunks with RMS < Threshold are replaced with absolute silence.

## 3. Creating New Features
*   **Warning**: If you add a new command, you MUST add it to `StreamClient.listen()` and `ViewerSession.run()` or the protocol will DESYNC (read binary data as text).
