# Video Recording Documentation

## 1. Overview
The **Video Recording** module allows the **CCTVPrimary** application to save high-quality surveillance footage directly to the device's local storage. This system operates independently of the network stream, meaning network lag or disconnection does not affect the quality of the local recording.

It employs a custom Recording Engine (`CustomRecorder`) to ensure that **Video** and **Audio** are synchronized and saved efficiently, even when the App is performing other tasks.

## 2. Recording Pipeline

The recording process uses a custom implementation based on Android's `MediaCodec` and `MediaMuxer` APIs, replacing the default CameraX `Recorder` to allow for shared audio access.

### A. Video Source
*   **Input**: Derived from CameraX's `ImageAnalysis` use case.
*   **Buffer Mode Frame Sharing**:
    *   To prevent resource conflicts and "frame stealing," the system uses a **Shared Pipeline**.
    *   A single `ImageAnalysis` stream feeds **BOTH** the `CustomRecorder` (for file recording) and the `VideoEncoder` (for streaming in Buffer Mode) sequentially.
    *   Frames are closed only after *all* consumers have processed them, ensuring neither pipeline is starved.
*   **Format**: YUV_420_888 frames.
*   **Encoding**:
    *   **Codec**: H.264 (AVC).
    *   **Bitrate**: VBR (Matching stream settings).
    *   **Resolution**: 
        *   **Surface Mode**: Full resolution (aligned to 16).
        *   **Buffer Mode**: Matches the negotiated streaming resolution (fixed 4:3 sizes, e.g. **720x960** portrait or **960x720** landscape).

### B. Audio Source
*   **Component**: `AudioSourceEngine` (Singleton).
*   **Mechanism**: The recorder registers as a "Listener" to the shared audio engine.
*   **Separation**: `CustomRecorder` and `StreamServer` receive independent copies of the microphone data, preventing resource conflicts without blocking each other.
*   **Encoding**: AAC-LC at 192kbps (Mono, 48kHz).

### C. Muxing & File Writing
*   **Component**: `CustomRecorder` wraps `MediaMuxer`.
*   **Process**:
    1.  Receives encoded H.264 NAL units and AAC ADTS frames.
    2.  Write them into an MP4 container.
    3.  **Rotation**:
        *   **Surface Mode**: Prefer MP4 container orientation hint (`MediaMuxer.setOrientationHint(...)`) to avoid rotating pixels in Kotlin.
        *   **Buffer Mode**: Keep container hint at `0` and rotate pixels in the YUV conversion path (matches the streaming orientation/constraints).

### D. Technical Considerations
*   **Threading**: Start/stop recording run on a dedicated **recording executor** (`StreamingExecutors.recordingExecutor`), not the control executor, so they are never starved by the Command Bus. See `Docs/THREADING.md`.
*   **Scoped Storage Compatibility**: Uses `MediaMuxer(FileDescriptor)` to support Android 10+ SAF.
*   **Crash-Safe Recovery**:
    *   If the app crashes or restarts while state is `RECORDING`, the service detects the inconsistent state (persisted "RECORDING" flag vs. no active generic recorder).
    *   **Safe Downgrade**: Instead of attempting a complex resume (which risks corruption), it safely downgrades the state to `PREVIEW` on startup, ensuring the UI and internal state remain consistent.
*   **Crash-Safe Stop**:
    *   Robust anti-deadlock mechanism signals `EOS` and drains buffers.

## 3. Storage & File Management

The system supports flexible storage options to handle Android's scoped storage restrictions and user preferences.

### A. Storage Location Options
The storage location is determined by settings in `AppSettings`.

1.  **SAF (Storage Access Framework) - *Preferred***
    *   **Trigger**: If the user has explicitly selected a folder via the System Picker (saved in `video_tree_uri`).
    *   **Behavior**: Files are written to the user-selected folder (e.g., on an SD Card).
2.  **MediaStore (Internal/External) - *Fallback***
    *   **Trigger**: Default behavior if no custom folder is selected.
    *   **Path**: `Movies/CCTV` (standard Android directory).
    *   **Behavior**: Files are inserted into `MediaStore.Video.Media.EXTERNAL_CONTENT_URI`.

### B. File Naming & Rotation
*   **Naming Convention**: `cctv_{timestamp}.mp4` (e.g., `cctv_1700000000000.mp4`).
*   **File Rotation**:
    *   **Enabled**: The system creates a new file for every recording session. If the storage limit is reached, it deletes the oldest files (*FIFO - First In, First Out*).
    *   **Disabled**: The system ensures unique filenames to preserve history but does not automatically prune old footage (User must manage space).

### C. Storage Management
*   **Limit**: Default 600 MB.
*   **Enforcement**: Use `enforceStorageLimit()` before starting a new recording. It checks the total size of files in the target directory and deletes the oldest `cctv_*.mp4` files until space is available.

## 4. How to Record (User Workflow)
1.  **Start**:
    *   Tap the **Record** button on the `CCTVPrimary` interface.
    *   OR: Send a `START_RECORDING` command remotely from `CCTVViewer`.
2.  **During Recording**:
    *   A notification or UI indicator shows recording status.
    *   Camera controls (FPS, Anti-banding) switch to "Recording Mode" for smoother motion (steady FPS).
3.  **Stop**:
    *   Tap **Stop** (or remote stop).
    *   The `CustomRecorder` drains logic ensures all buffered frames are written to disk before closing the file to prevent corruption.
    *   The file is finalized and becomes visible in Gallery apps immediately.
