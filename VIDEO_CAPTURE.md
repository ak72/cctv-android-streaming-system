# Video Capture Documentation

## 1. Overview
The video capture system in **CCTVPrimary** is the foundation of the entire surveillance application. It is responsible for interfacing with the Android hardware camera, managing the lifecycle of camera resources, and delivering frames to two primary consumers:
1.  **Live Streaming** (to Viewer)
2.  **Local Recording** (to Storage)

The system is designed to be **robust** (recovering from errors), **efficient** (minimizing CPU usage), and **compatible** (working across diverse Android devices).

## 2. Core Components

### A. `CameraForegroundService.kt`
*   **Role**: The central orchestrator.
*   **Responsibility**: Running as a Foreground Service, it ensures the camera remains active even when the app is minimized. It manages the `CameraX` library, binds use cases, and handles the switching between front/back cameras.
*   **Lifecycle Fix**: On `ON_START`, it *always* re-attaches the local `PreviewView` (if bound) ensures the local display recovers correctly after a crash or background resume, regardless of the previous capture state.

### B. `VideoEncoder.kt`
*   **Role**: The hardware-accelerated H.264 encoder.
*   **Key Feature**: Supports two distinct input modes (Surface and ByteBuffer) to maximize compatibility and performance.

### C. `CameraX` (Jetpack Library)
*   **Role**: The abstraction layer over Android's `Camera2` API.
*   **Configuration**:
    *   **Resolution**: Target 4:3 aspect ratio (e.g., 1440x1080), aligned to 16-pixel boundaries for encoder compatibility.
    *   **Focus**: Continuous Auto Focus (CAF).
    *   **Exposure**: Auto Exposure with target FPS ranges (10-30fps).

    *   **Exposure**: Auto Exposure with target FPS ranges (10-30fps).

### D. New Capability Modules
*   **`EncoderCapabilityDetector`**: Queries `MediaCodecList` to verify `COLOR_FormatSurface` support instead of hardcoding models.
*   **`ActivePipelineProber`**: Runs a quick, invisible test of the camera->encoder pipeline on first launch to confirm it actually delivers frames.
*   **`DeviceProfile`**: Persists the optimal configuration (resolution, FPS, buffer mode preference) per camera, based on probing results.
*   **`DeviceQuirks`**: Centralized registry for the few remaining confirmed vendor bugs.

## 3. The Capture Pipeline

The project implements a "Zero-Copy" pipeline by default but retains a "Buffer Mode" fallback.

### Pipeline A: Surface Mode (Zero-Copy) - *Primary/Preferred*
This is the modern, high-performance path.

1.  **Encoder Setup**: `VideoEncoder` creates a hardware `Input Surface` via `MediaCodec.createInputSurface()`.
2.  **Binding**: `CameraForegroundService` passes this Surface to CameraX as a `Preview` use case.
3.  **Flow**:
    *   Camera Hardware -> GPU/DSP -> `VideoEncoder` Surface.
    *   **Benefit**: **Zero CPU usage** for frame transport. The CPU never touches the pixel data (no YUV conversion), resulting in 40-60% lower CPU load and less battery drain.
4.  **Rotation**: Frames are encoded "as-is" from the sensor. Rotation metadata (`ENC_ROT`) is sent to the Viewer to rotate the display, rather than rotating pixel data.

### Pipeline B: Buffer Mode (Fallback) - *Compatibility*
This path is used for devices that crash with Surface input (e.g., some Samsung Exynos models) or when "Force Buffer Mode" is enabled.

1.  **Analysis Setup**: `CameraForegroundService` creates an `ImageAnalysis` use case.
    *   **Problematic Devices**: Checked via `DeviceQuirks` (e.g., Samsung Exynos M30s).
    *   **Manual Override**: "Force Buffer Mode" in settings.
    *   **Capability/Probe Failure**: Determined by `EncoderCapabilityDetector` or `EncoderProbeStore` history.
    *   **Runtime Failure**: If Surface mode fails to start, the system automatically retries with Buffer mode.
2.  **Binding**: CameraX delivers `ImageProxy` objects (YUV_420_888) to a CPU executor.
3.  **Conversion**:
    *   `YuvUtils` converts the YUV data to the layout expected by the encoder (NV12 or I420).
    *   This step uses the CPU and memory bandwidth.
4.  **Encoding**: The converted buffer is queued into `MediaCodec` via `queueInputBuffer()`.
5.  **Flow**:
    *   Camera Hardware -> CPU Memory -> Format Conversion -> `VideoEncoder`.
    *   **Drawback**: Higher CPU usage / potentially lower framerate on weak devices.

### Smart Selection Strategy
The system now picks the best pipeline automatically:
1.  **User Override**: If "Force Buffer Mode" is ON, use Pipeline B.
2.  **Quirk Registry**: If `DeviceQuirks.forceBufferInputMode()` is true, use Pipeline B.
3.  **Capability Check**: If `EncoderCapabilityDetector` says Surface input is unsupported, use Pipeline B.
4.  **Probe History**: If `EncoderProbeStore` recalls a past failure, use Pipeline B.
5.  **Active Probe**: If `ActivePipelineProber` fails the Surface test on first run, fallback to Pipeline B (and persist the decision).
6.  **Default**: Use Pipeline A (Surface).

## 4. Dual-Stream Architecture
When **Recording** is active while **Streaming**, the system manages two parallel pipelines:

1.  **Streaming**: Continues using **Pipeline A** (Surface Mode) where possible.
2.  **Recording**: Uses `ImageAnalysis` (similar to Pipeline B components) to feed the `CustomRecorder`.
    *   *Note*: If streaming is in Surface Mode, `ImageAnalysis` is added dynamically for recording. If streaming is in Buffer Mode, the existing `ImageAnalysis` stream is shared.

## 5. Camera Control Features
*   **Flash/Torch**: Toggled via `CameraControl.enableTorch()`.
*   **Zoom**: Digital zoom managed via `CameraControl.setZoomRatio()`.
*   **Exposure Compensation**: Adjustable +/- EV steps.
*   **Switch Camera**: Seamless tear-down and re-bind of the pipeline to switch between Front and Back lenses.
*   **Low Power Mode**:
    *   **Trigger**: No connected Viewers AND not recording AND UI hidden.
    *   **Action**: Downgrades stream to ~480p @ 15fps and caps bitrate to 900kbps to save battery/thermal headroom.
    *   **Restore**: Instantly restores high-quality settings when a Viewer connects or Recording starts.
