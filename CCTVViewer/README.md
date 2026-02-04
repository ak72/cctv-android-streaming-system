# Project Architecture

## 1. CCTVViewer (Client) Internals

The `CCTVViewer` focuses on low-latency decoding and robust state management.

### Core Components

1.  **`StreamClient.kt`**:
    - **Role**: The "Brain" of the viewer.
    - **Responsibilities**:
      - **Network IO**: Connects socket, reads raw bytes, parses protocol packets.
      - **State Machine**: Manages transitions (CONNECTING -> HANDSHAKING -> STREAMING -> RECOVERING).
      - **Jitter Buffer**: Queues incoming frames slightly to smooth out network variance.
      - **Adaptive Logic**: Monitors FPS and Queue depth to request Bitrate/Resolution changes.
2.  **`MainActivity.kt`**:
    - **Role**: UI & Rendering.
    - **Responsibilities**: Hosts the `SurfaceView` or `TextureView`. Handles touch events (PTZ - Pan/Tilt/Zoom gestures) and relays them to the client.
3.  **Video Pipeline**:
    - **Decoder**: `MediaCodec` (Async mode or Sync loop).
    - **Rendering**: Decodes directly to the Surface provided by the View. No manual buffer copying.
4.  **Audio Pipeline**:
    - **Playback**: `AudioTrack` running on a high-priority thread to minimize audio glitches.
    - **Talkback**: `AudioRecord` captures local mic -> sends UPSTREAM to Primary.

# Project Workflows

## Video Streaming Loop

Once in **STREAMING** state:

1.  **Capture**: `CameraX` Preview sends texture data to the Input Surface of `VideoEncoder`.
2.  **Encode**: `MediaCodec` (Primary) processes the frame -> Output Buffer (H.264 NALUs).
3.  **Packetize**:
    - `StreamServer` wraps the data: `FRAME|epoch=...|key=true/false|tsUs=...|size=...`.
    - Appends payload size and payload data.
4.  **Transmit**: Data sent over TCP Socket.
5.  **Receive**: `StreamClient` (Viewer) reads the header, allocates buffer, reads payload.
6.  **Decode**:
    - Client checks identifying header. If `FRAME`:
    - Feeds data into `MediaCodec` (Viewer) Input Buffer.
7.  **Render**: `MediaCodec` decodes to the Viewer's `Surface`, instantly visible on screen.

---
