# Video Streaming Architecture

## 1. Overview
Streaming is the core function of **CCTVPrimary**. The system is designed for **low-latency** (real-time) delivery of H.264 video from the Camera to one or more Viewers. It employs a "Fan-Out" architecture where a single hardware encoder feeds multiple network clients simultaneously.

## 2. The Pipeline
The flow of video data is as follows:

1.  **Source**: Camera Hardware (Surface) or CPU Buffer (YUV).
2.  **Encoder**: `VideoEncoder` (MediaCodec).
3.  **Fan-Out**: `StreamServer` and its **FrameBus** (bounded queue; see `FrameBus.kt` and [THREADING.md](THREADING.md)).
4.  **Transport**: `ViewerSession` (TCP Socket).
5.  **Decoder**: Remote Viewer (MediaCodec).

## 3. StreamServer `(StreamServer.kt)`
This is the central hub. It decouples the *Producer* (Encoder) from the *Consumers* (Viewers).

*   **Session Tracking**: Maintains a count of active authenticated sessions (`activeSessionCount()`).
    *   Used by `CameraForegroundService` to trigger **Low Power Mode** (downgrading resolution/FPS) when the session count hits zero.
*   **FrameBus**: A bounded queue (see `FrameBus.kt`; capacity 60). The encoder pushes frames via `StreamServer.enqueueFrame()`; the `Sender` thread (on `StreamingExecutors.senderExecutor`) drains the FrameBus with `pollWithTimeout` and fans out to sessions.
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
    *   Payload: AAC-LC frames. In the current Primary implementation, the payload includes an **ADTS header** + AAC payload per frame when `format=aac`.
    *   Features: Volume boosted (1.5x), Soft Limited, Compressed (64kbps).

*   **Upstream (Viewer -> Camera)**:
    *   Command: `AUDIO_FRAME|dir=up|size=...|rate=48000|ch=1`
    *   Payload: Raw PCM16LE (for low latency talkback).

## 4b. Protocol & Data Format
The stream wraps raw H.264 NAL units in a custom lightweight container. For a single list of all protocol commands and fields, see [Protocol & Message Fields](PROTOCOL_REFERENCE.md#protocol--message-fields-consolidated).

### A. Codec Specific Data (CSD)
Before any video plays, the Decoder needs the `SPS` (Sequence Parameter Set) and `PPS` (Picture Parameter Set).
*   **Command**: `CSD|epoch=...|sps={size}|pps={size}`
*   **Payload**: Binary SPS + Binary PPS.
*   *Trigger*: Sent immediately after Authentication and whenever the Encoder restarts.

### B. Video Frames
Each frame is sent as a packet:
*   **Header**: `FRAME|epoch=...|size={bytes}|key={boolean}|tsUs={timestamp}|...`
*   **Payload**: The raw H.264 byte stream.

Common optional fields (newer builds):
- `seq=...` (monotonic per-epoch sequence number; useful for gap diagnostics)
- `srvMs=...` (Primary wall-clock send time in ms)
- `capMs=...` (Primary capture wall-clock time in ms)
- `ageMs=...` (capture→send age computed on Primary)

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
Backpressure is handled in multiple layers:

*   **Viewer -> Primary signal**:
    *   If the Viewer detects it is falling behind, it sends `BACKPRESSURE` (and later `PRESSURE_CLEAR`) to the Primary.
    *   The Primary uses this as a *proxy* signal for network/receiver stress and may lower bitrate (seamless) to recover.
*   **Primary-side load shedding**:
    1.  **StreamServer** prefers to broadcast the most recent frame (and prefers keyframes when present) to keep latency bounded.
    2.  **ViewerSession** maintains per-session queues and will drop older frames under sustained network slowness, prioritizing keyframes when possible.

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
