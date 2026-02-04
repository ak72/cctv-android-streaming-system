# Video Encoding

## 1. Overview
The video encoding module (`VideoEncoder.kt`) is the engine room of the camera application. Its job is to take raw video frames from the camera and compress them into an efficient H.264 stream for network transmission.

Crucially, it supports **two distinct input modes** to handle the fragmentation of the Android ecosystem:
1.  **Surface Input Mode (Preferred)**: Zero-copy, high performance.
2.  **ByteBuffer Input Mode (Fallback)**: CPU-based, high compatibility.

## 2. Input Modes

### A. Surface Mode (Zero-Copy)
*   **Mechanism**: Creates a hardware `Input Surface` via `MediaCodec.createInputSurface()`.
*   **Data Flow**: Camera Hardware -> GPU/DSP -> Encoder.
*   **Benefit**: The CPU never touches the pixel data. This reduces CPU usage by ~50% and battery drain significantly.
*   **Usage**: Default for most devices (Pixel, Xiaomi, etc.).

### B. ByteBuffer Mode (Fallback)
*   **Mechanism**: Uses `MediaCodec.getInputBuffer()` and `queueInputBuffer()`.
*   **Data Flow**: Camera -> ImageReader (YUV) -> CPU Copy/Convert -> Encoder.
*   **Triggers**:
    *   **Problematic Devices**: Checked via `DeviceQuirks` (e.g., Samsung Exynos M30s).
    *   **Manual Override**: "Force Buffer Mode" in settings.
    *   **Capability/Probe Failure**: Determined by `EncoderCapabilityDetector` or `EncoderProbeStore` history.
    *   **Runtime Failure**: If Surface mode fails to start, the system automatically retries with Buffer mode.
*   **YUV Handling**: Includes a `YuvUtils` helper to convert standard `YUV_420_888` from CameraX into the specific format required by the encoder (often `NV12` or `I420`).

## 3. Configuration & Compatibility

### A. Configuration Strategies
Android encoders are picky. `VideoEncoder` now uses a **Capability-Driven Quality Ladder** provided by `DeviceProfile`:
1.  **Probed High Quality**: The highest confirmed working resolution (e.g. 1080p-ish) found during `ActivePipelineProber`.
2.  **Fallback Logic**: If no profile exists, it tries:
    1.  **Aligned + Baseline**: Explicit 16-pixel aligned resolution.
    2.  **Standard 1080p**: Forces 1080x1440 if close.
    3.  **Surface-Derived**: Labeled 0x0 (Surface dictates size).

### B. Codec Specific Data (CSD)
*   **What**: SPS (Sequence Parameter Set) and PPS (Picture Parameter Set).
*   **Extraction**:
    *   The encoder outputs these as a special buffer (flag `CODEC_CONFIG`) or embedded in the `outputFormat`.
    *   `VideoEncoder` extracts them immediately and invokes `codecConfigListener` to broadcast them to Viewers.
    *   *Critical*: Without CSD, the Viewer screen remains black.

## 4. Runtime Control

### A. Adaptive Bitrate (ABS)
*   **Bitrate Mode**: Configured as **VBR** (Variable Bitrate) for efficiency.
*   **Adjustment**:
    *   Call `adjustBitrate(newBitrate)` to change quality on the fly.
    *   Uses `MediaCodec.setParameters()` to avoid restarting the encoder (which would cause a visible glitch).

### B. Frame Draining
*   **Drain Loop**: A dedicated background thread continuously polls `dequeueOutputBuffer()`.
*   **Robustness**:
    *   **State Check**: Verifies encoder is running before processing buffers.
    *   **Safety**: Wraps read/write operations in `try-catch` to handle `IllegalStateException`.
    *   **Release**: If write fails, releases buffer with `size=0` to avoid leaking encoder buffers.
*   **Stuck Detection**:
    *   The loop monitors input vs. output counts.
    *   If the encoder accepts inputs but produces no output for >5 seconds, it flags a **CRITICAL STALL**.
    *   *Recovery*: Tries to "unstick" by requesting a Sync Frame (`requestSyncFrame()`).

## 5. Encoded Frame Object
Output frames are wrapped in a generic `EncodedFrame` class (shared with the Stream Server):
*   **Data**: The H.264 byte stream.
*   **IsKeyFrame**: Boolean flag (Critical for joining/recovery).
*   **PresentationTimeUs**: Timestamp for playback sync.
*   **Epoch**: Handling resolution changes (see *Streaming.md*).
