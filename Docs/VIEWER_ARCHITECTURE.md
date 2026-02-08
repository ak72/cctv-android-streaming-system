# Viewer Application Architecture

## 1. Overview
The **CCTVViewer** app is a complex real-time video receiver. Unlike a standard video player (ExoPlayer/VLC) which buffers seconds of content, this app must play video with **minimum latency** (sub-500ms) while handling network jitter, packet loss, and device diversity. For the full list of protocol commands and message fields, see [Protocol & Message Fields](PROTOCOL_REFERENCE.md#protocol--message-fields-consolidated).

## 2. Core Components `(StreamClient.kt)`
The `StreamClient` class orchestrates the entire session. It uses a **multi-threaded architecture** via **`StreamClientExecutors`** to prevent blocking the UI or the network.

### A. The Threading Model (`StreamClientExecutors`)

All executors are owned by a centralized **`StreamClientExecutors`** manager, which handles create/recreate/shutdown lifecycle and reduces race complexity. Roles: CONNECT, DECODE, HEARTBEAT, SENDER, AUDIO_RECORD, AUDIO_PLAYBACK, RECONNECT.

1.  **Network Receiver Thread** (`listen()`):
    *   Reads from the TCP socket.
    *   Parses text commands (`SESSION`, `CSD`).
    *   Reads binary frames (`FRAME`) and pushes them to the `decodeQueue`.
2.  **Decoder Thread** (`decodeLoop()`):
    *   Pulls compressed frames from the `decodeQueue`.
    *   Feeds them into `MediaCodec`.
    *   Releases decoded buffers to the Surface.
3.  **Sender Thread**:
    *   Sends commands (Heartbeats, Talkback Audio) to the Primary.
    *   Decoupled from the UI to prevent "Application Not Responding" (ANR) if the network stalls.
4.  **Audio Playback Thread**:
    *   Feeds incoming audio PCM chunks to `AudioTrack`. Uses a **device-tier** playback queue (low: 40, mid: 60, high: 80 packets; ~2–3 s at 20 ms/frame).
5.  **Watchdog (Heartbeat) Thread**:
    *   Monitors connection health every 2 seconds.

## 3. The Video Pipeline

### A. Adaptive Jitter Buffering
*   **Queue**: `ArrayBlockingQueue<IncomingFrame>` with **device-tier capacity** (low: 15, mid: 25, high: 30). Target backlog is adaptive (typically 2-4 frames).
*   **Purpose**: Absorbs network jitter. If the network delivers 5 frames in 10ms (burst), the queue absorbs them so the decoder can process them at a steady pace.
*   **Adaptive Logic**: The system monitors inter-arrival times and calculates an EWMA (Exponential Weighted Moving Average) of jitter. It dynamically adjusts the target queue size:
    *   **Low Jitter (LAN)**: 2 frames (Min latency).
    *   **High Jitter (WAN/WiFi)**: Up to 4 frames (Smoothness).
*   **Latency Control**: If the queue grows beyond the target (latency increasing), the Viewer trims backlog and can send `BACKPRESSURE` / `PRESSURE_CLEAR` to the Primary.

### B. One-Surface-Two-Renderers
To support the widest range of devices, the Viewer supports two rendering modes:
1.  **SurfaceView (Default)**:
    *   *Pros*: Lower battery usage, better performance.
    *   *Cons*: Fails on some Samsung Exynos devices (Black Screen).
2.  **TextureView (Fallback)**:
    *   *Pros*: Works on almost everything. Supports complex transforms (Zoom/Pan).
    *   *Cons*: Higher battery usage.
*   **Renderer policy (current implementation)**:
    *   **Known-bad devices**: The app force-disables SurfaceView on a small quirk list (e.g., specific Samsung models) and persists that choice.
    *   **SurfaceView correctness**: The app uses `PixelCopy` sampling to confirm that *real pixels* are visible before declaring the preview “Playing”.
    *   **TextureView correctness**: The app avoids applying placeholder buffer sizes and only transforms once decoder output dimensions are known (prevents persistent “zoomed” preview on some devices).

## 4. State Management
The `ConnectionState` enum drives the UI:
1.  **DISCONNECTED**: Idle.
2.  **CONNECTING**: Socket opening.
3.  **CONNECTED**: TCP open, waiting for handshake or "No Video" (stream not yet active).
4.  **AUTHENTICATED**: Password accepted.
5.  **STREAMING**: Stream active; server has sent STREAM_STATE ACTIVE (after first keyframe).
6.  **RECOVERING**: Automatic attempt to restore stall (re-negotiate).
7.  **IDLE**: Stream intentionally stopped by server (STREAM_STATE|4). Pipeline inactive; distinct from CONNECTED.

## 5. Resilience & Watchdogs
Real-world networks are unstable. The Viewer employs aggressive self-healing. Handshake and stream health timeouts are defined in **`StreamClientConstants.kt`** (e.g. `HANDSHAKE_AUTH_TIMEOUT_MS`, `HANDSHAKE_STREAM_STALL_TIMEOUT_MS`, `CONNECTED_PONG_TIMEOUT_MS`).

*   **Connection Watchdog**:
    *   **Handshake watchdog**: If no `AUTH_OK` after `HANDSHAKE_AUTH_TIMEOUT_MS` (~10 s) of TCP connect, it forces a reconnect.
    *   **Start-stall watchdog**: If authenticated but no frames start, it retries negotiation (re-sends `CAPS` + `SET_STREAM`) and requests keyframes. It downgrades UI to `CONNECTED` ("No Video") after ~12s, and may reconnect after ~25s (unless in a recording/reconfigure grace window).
    *   **Connected/No-Video watchdog**: If PONGs stop for a short, state-dependent timeout (≈7s normally, longer if audio is flowing or during grace), it disconnects to recover. Otherwise it escalates in steps: keyframe probe → renegotiate → reconnect (last resort).
*   **Stream watchdog**:
    *   If the app is `STREAMING` but frames stall for ~2s, it requests a keyframe and downgrades to `CONNECTED` ("No Video") unless audio/grace indicates an expected pause.
*   **Render Watchdog**: If the decoder stops producing output (e.g., background corruption), it resets the decoder.
*   **Reconnect Loop**: If disconnected, it attempts to reconnect with **Exponential Backoff**:
    *   Delays: 1s -> 2s -> 4s -> 8s -> 10s (capped).
    *   Prevents rapid retry loops when server is down.

*   **StreamHealthSnapshot**:
    *   Provides a thread-safe, immutable snapshot of the entire pipeline state (FPS, Drops, Latency) for UI consumption. This prevents race conditions when the UI queries background threads.

## 6. Lifecycle & Backgrounding
To ensure reliability and battery efficiency, `StreamClient` handles Android lifecycle events explicitly:
*   **Backgrounded (`onAppBackgrounded`)**:
    *   **Action**: Intentionally closes the TCP socket to prevent server-side “connection reset” churn and to avoid wasting battery/data while not visible.
    *   **Reason**: Prevents "Connection Reset" errors on the Server, stops battery drain, and avoids accumulating stale frames while invisible.
*   **Foregrounded (`onAppForegrounded`)**:
    *   **Action**: Automatically calls `connect()` to restore the session.
    *   **Reason**: Seamless user experience without manual intervention.

## 7. Talkback (Two-Way Audio)
*   **Input**: `AudioRecord` (Viewer Mic).
*   **Transport**: Push-to-talk; audio is sent upstream only while active.
*   **Push-to-Talk**: When the button is held, audio is recorded and sent upstream via the `Sender` thread.

## 8. Device Specific Mitigations
*   **OnePlus Nord CE4 (and similar)**:
    *   **Issue**: Decoder outputs green/garbled frames immediately after a Reset/Keyframe.
    *   **Fix**: The `StreamClient` suppresses rendering for a short "warmup" period (dropping the first few output buffers) after a keyframe to ensure only clean video is shown.
