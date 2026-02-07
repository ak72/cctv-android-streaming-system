# Project Workflows

## 0. First Launch / Probing (One-Time)
Before the first connection is possible:
1.  **Permission Grant**: User accepts Camera/Audio permissions.
2.  **Auto-Probe**: `ActivePipelineProber` silently tests different encoder configurations.
    *   *Step A*: Try 1080p @ 30fps (Surface Mode). If crash/timeout ->
    *   *Step B*: Try 720p @ 30fps (Surface Mode). If crash/timeout ->
    *   *Step C*: Try 720p @ 30fps (**Buffer Mode**).
3.  **Profile Saved**: The working configuration is persisted via `DeviceProfileStore` (SharedPreferences JSON, keyed by firmware fingerprint + camera).
4.  **Ready**: The "Start Capture" button becomes enabled.


## 1. Connection & Handshake Workflow

This process establishes the session between Viewer and Primary. For a consolidated list of every protocol command and its fields, see [Protocol & Message Fields](PROTOCOL_REFERENCE.md#protocol--message-fields-consolidated).

1.  **Discovery/Initiation**: User enters IP/Port in Viewer and clicks "Connect".
2.  **TCP Connection**: `StreamClient` opens a socket to `StreamServer`.
3.  **Protocol Handshake**:
    *   **Viewer**: Sends `HELLO|client=viewer|version=1` or `version=2` (version 2 = supports server-authoritative STREAM_STATE).
    *   **Primary**: Responds `AUTH_CHALLENGE|v=2|salt={salt}`.
    *   **Viewer**: Sends `AUTH_RESPONSE|hash={hmac_sha256(password, salt)}`.
    *   **Primary**:
        *   On success: Sends `AUTH_OK`, allocates a unique Session ID and sends `SESSION|id={uuid}`.
        *   On failure: Sends `AUTH_FAIL` and closes the socket.
4.  **Capability Negotiation**:
    *   **Viewer**: Sends `CAPS|maxWidth=...|maxHeight=...` (Device limits).
    *   **Primary**: Responds `CAPS_OK`.
5.  **Stream Setup**:
    *   **Viewer**: Sends `SET_STREAM|width=1440|height=1080|fps=30|bitrate=2000000`.
        *   Note: newer Viewers/Primaries may include extra params; unknown params are ignored by the receiver as long as framing stays correct.
    *   **Primary**:
        *   Arbitrates the request against current Hardware limitations.
        *   Configures/Reconfigures the `VideoEncoder` if necessary (or joins existing stream).
        *   Sends `STREAM_ACCEPTED|width=...|...` confirming actual parameters.
    *   **Primary**: Sends `CSD` (Codec Specific Data - SPS/PPS) immediately.
6.  **State**: Both parties transition to **STREAMING**.

## 2. Video Streaming Loop

Once in **STREAMING** state:

1.  **Capture**: `CameraX` Preview sends texture data to the Input Surface of `VideoEncoder`.
2.  **Encode**: `MediaCodec` (Primary) processes the frame -> Output Buffer (H.264 NALUs).
3.  **Packetize**:
    *   `StreamServer` wraps the data: `FRAME|epoch=...|key=true/false|tsUs=...|size=...`.
    *   Appends payload size and payload data.
4.  **Transmit**: Data sent over TCP Socket.
5.  **Receive**: `StreamClient` (Viewer) reads the header, allocates buffer, reads payload.
6.  **Decode**:
    *   Client checks identifying header. If `FRAME`:
    *   Feeds data into `MediaCodec` (Viewer) Input Buffer.
7.  **Render**: `MediaCodec` decodes to the Viewer's `Surface`, instantly visible on screen.

## 3. Audio Streaming (Two-Way)

### Downstream (Primary -> Viewer)
1.  **Source**: `AudioSourceEngine` captures MIC data (PCM 16-bit, 48kHz).
2.  **Process**: Hardware Noise Suppression / AEC applied.
3.  **Route**: Data passed to `StreamServer`.
4.  **Send**: `AUDIO_FRAME|dir=down|...` sent over TCP.
5.  **Play**: Viewer receives, queues into a jitter buffer, and plays via `AudioTrack`.

### Upstream / Talkback (Viewer -> Primary)
1.  **Trigger**: User holds "Mic" button on Viewer.
2.  **Source**: Viewer captures MIC data.
3.  **Send**: `AUDIO_FRAME|dir=up|...` sent over TCP.
4.  **Receive**: Primary `ViewerSession` receives packet.
5.  **Play**: `CameraForegroundService` takes data and writes to a dedicated High-Priority `AudioTrack`.

## 4. Adaptive Quality Workflow

To handle bad networks without reconnecting:

1.  **Detection**: Viewer detects High Latency or Emptying/Full Buffers.
2.  **Request Change**:
    *   **Bitrate**: Viewer sends `ADJUST_BITRATE|bitrate=500000`.
    *   **Primary**: Calls `encoder.setParameters(bitrate=500000)`. **No Black Screen.**
3.  **Request Resolution** (Severe Downgrade):
    *   Viewer sends `SET_STREAM|width=640|height=480...`.
    *   **Primary (Standard Mode)**: MUST restart encoder (Brief hiccup).
    *   **Primary (Buffer Mode / Stability Path)**:
        *   Used on problematic devices (e.g., Samsung M30s) where restarts cause crashes (`libc`).
        *   **Ignores resolution/FPS changes** to prevent native crashes.
        *   Only adjusts bitrate. The stream remains active and stable.
    *   Primary sends `STREAM_ACCEPTED` with new dims.
    *   Viewer reconfigures Decoder.
    *   Stream resumes.

## 5. Recording Workflow

1.  **Trigger**: User clicks "Record" on Primary UI or on the Viewer (sends `START_RECORDING`). When triggered from the Viewer, the Primary ensures streaming audio is started before starting the recorder so the file includes audio.
2.  **Initialization**: `CameraForegroundService` initializes `CustomRecorder`.
3.  **Pipeline Split**:
    *   CameraX `ImageAnalysis` (or secondary Surface) feeds the `CustomRecorder`.
    *   *Note*: Streaming continues efficiently via the Zero-Copy surface path; Recording runs parallel (potentially using CPU for YUV if ImageAnalysis is used, or a second surface).
4.  **Audio**: `CustomRecorder` registers as a listener to `AudioSourceEngine`, receiving a copy of the existing mic feed.
5.  **Muxing**: Video + Audio written to `.mp4` file.
6.  **Camera switch**: If the user switches camera (Primary or Viewer) during recording, the pipeline rebinds and the recording sink is re-applied; the same file continues for the full duration.
7.  **Stop**: File finalized and added to MediaStore.

## 6. Device Rotation Workflow

1.  **Event**: Primary device rotates 90 degrees.
2.  **No Restart**: Encoder continues running as-is (Width/Height don't flip in the encoder).
3.  **Metadata**: Primary sends `ENC_ROT|deg=90`.
4.  **Client Action**: Viewer UI reads this packet and rotates the `TextureView` container 90 degrees to match.
5.  **Result**: Smooth rotation without stream interruption.
