# Video Streaming Architecture

## 1. Overview
Streaming is the core function of **CCTVPrimary**. The system is designed for **low-latency** (real-time) delivery of H.264 video from the Camera to one or more Viewers. It employs a "Fan-Out" architecture where a single hardware encoder feeds multiple network clients simultaneously.

## 2. The Pipeline
The flow of video data is as follows:

1.  **Source**: Camera Hardware (Surface) or CPU Buffer (YUV).
2.  **Encoder**: `VideoEncoder` (MediaCodec).
3.  **Fan-Out**: `StreamServer` (Queue).
4.  **Transport**: `ViewerSession` (TCP Socket).
5.  **Decoder**: Remote Viewer (MediaCodec).

## 3. StreamServer `(StreamServer.kt)`
This is the central hub. It decouples the *Producer* (Encoder) from the *Consumers* (Viewers).

*   **Session Tracking**: Maintains a count of active authenticated sessions (`activeSessionCount()`).
    *   Used by `CameraForegroundService` to trigger **Low Power Mode** (downgrading resolution/FPS) when the session count hits zero.
*   **Frame Queue**: A `LinkedBlockingQueue` (capacity 60).
    *   The Encoder pushes frames here.
    *   The `Sender` thread pulls them.
*   **Fan-Out Logic**:
    *   The `Sender` thread takes the *latest* frame.
    *   It iterates over all active `ViewerSession`s.
    *   It calls `session.enqueueFrame(frame)`.
*   **Keyframe Logic**:
    *   Keyframes (IDR) are critical. They are *never* dropped unless absolutely necessary.
    *   New Viewers receive the latest Keyframe immediately to start decoding fast.

## 4a. Audio Protocol (AAC & PCM)
Audio is treated as a first-class stream with two-way support:

*   **Downstream (Camera -> Viewer)**:
    *   Command: `AUDIO_FRAME|dir=down|size=...|rate=...|ch=...|format=aac`
    *   Payload: Raw AAC-LC frames (with ADTS headers removed usually, or raw PCM).
    *   Features: Volume boosted (1.5x), Soft Limited, Compressed (64kbps).

*   **Upstream (Viewer -> Camera)**:
    *   Command: `AUDIO_FRAME|size=...|...`
    *   Payload: Raw PCM (for low latency talkback).

## 4b. Protocol & Data Format
The stream wraps raw H.264 NAL units in a custom lightweight container.

### A. Codec Specific Data (CSD)
Before any video plays, the Decoder needs the `SPS` (Sequence Parameter Set) and `PPS` (Picture Parameter Set).
*   **Command**: `CSD|epoch=...|sps={size}|pps={size}`
*   **Payload**: Binary SPS + Binary PPS.
*   *Trigger*: Sent immediately after Authentication and whenever the Encoder restarts.

### B. Video Frames
Each frame is sent as a packet:
*   **Header**: `FRAME|epoch=...|size={bytes}|key={boolean}|tsUs={timestamp}|...`
*   **Payload**: The raw H.264 byte stream.

### C. Epochs
To handle resolution changes (e.g., user switches 720p -> 480p), we use **Epochs**.
*   **Problem**: If the Viewer puts a 480p frame into a decoder configured for 720p, it crashes.
*   **Solution**:
    1.  `StreamServer` increments `epoch` (1 -> 2).
    2.  Broadcasts `STREAM_ACCEPTED|epoch=2`.
    3.  Sends `CSD|epoch=2`.
    4.  Sends `FRAME|epoch=2`.
    5.  **Viewer Rule**: If `current_epoch < 2`, drop the frame. If `message_epoch < 2`, ignore it.

## 5. Adaptive Streaming
The system adapts to network conditions dynamically.

### A. Bitrate Adjustment (Seamless)
*   **Mechanism**: Android's `MediaCodec.setParameters()`.
*   **Trigger**: Viewer detects high latency or buffer bloat -> sends `ADJUST_BITRATE`.
*   **Action**: `VideoEncoder` lowers the bitrate *on the fly* without restarting.
*   **Benefit**: No glitch, no freeze, just lower quality to maintain fluidity.

### B. Backpressure Handling
*   **Detection**: If `ViewerSession`'s internal socket buffer fills up.
*   **Action**:
    1.  **Drop Frames**: `ViewerSession` starts dropping P-Frames (non-key frames) to let the TCP queue drain.
    2.  **Keyframe Preservation**: It tries hard *not* to drop Keyframes, as that causes artifacts (grey smearing) for seconds.
    3.  **Client Notification**: Sends `BACKPRESSURE` command to Viewer (optional, debug).

## 6. Latency Management
Real-time surveillance requires minimal delay (sub-500ms).

1.  **TCP NoDelay**: Disabled Nagle's algorithm (`socket.tcpNoDelay = true`) for immediate packet sending.
2.  **Queue Management**:
    *   `StreamServer`: Drops old frames if the Encoder runs faster than the Sender thread.
    *   `ViewerSession`: Drops old frames if the Network is slower than the Sender thread.
    *   *Logic*: "Better to skip a frame than to show it 2 seconds late."

## 7. Resolution & Rotation
*   **Resolution Alignment**: All dimensions are aligned to 16 pixels (e.g., 1080 -> 1072) to prevent green lines on some devices.
*   **Rotation**:
    *   **Zero-Copy Mode**: The pixel data is NOT rotated (expensive).
    *   **Metadata**: We send `ENC_ROT|deg=90`.
    *   **Viewer**: The Viewer's `TextureView` rotates the display to match.
