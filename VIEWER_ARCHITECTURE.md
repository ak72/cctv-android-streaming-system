# Viewer Application Architecture

## 1. Overview
The **CCTVViewer** app is a complex real-time video receiver. Unlike a standard video player (ExoPlayer/VLC) which buffers seconds of content, this app must play video with **minimum latency** (sub-500ms) while handling network jitter, packet loss, and device diversity.

## 2. Core Components `(StreamClient.kt)`
The `StreamClient` class orchestrates the entire session. It uses a **multi-threaded architecture** to prevent blocking the UI or the network.

### A. The Threading Model
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
    *   Feeds incoming audio PCM chunks to `AudioTrack`.
5.  **Watchdog (Heartbeat) Thread**:
    *   Monitors connection health every 2 seconds.

## 3. The Video Pipeline

### A. Adaptive Jitter Buffering
*   **Queue**: `ArrayBlockingQueue<IncomingFrame>` (Adaptive, typically 2-4 frames).
*   **Purpose**: Absorbs network jitter. If the network delivers 5 frames in 10ms (burst), the queue absorbs them so the decoder can process them at a steady pace.
*   **Adaptive Logic**: The system monitors inter-arrival times and calculates an EWMA (Exponential Weighted Moving Average) of jitter. It dynamically adjusts the target queue size:
    *   **Low Jitter (LAN)**: 2 frames (Min latency).
    *   **High Jitter (WAN/WiFi)**: Up to 4 frames (Smoothness).
*   **Latency Control**: If the queue grows beyond the target (latency increasing), the Viewer drops frames directly or sends `BACKPRESSURE` to the Primary.

### B. One-Surface-Two-Renderers
To support the widest range of devices, the Viewer supports two rendering modes:
1.  **SurfaceView (Default)**:
    *   *Pros*: Lower battery usage, better performance.
    *   *Cons*: Fails on some Samsung Exynos devices (Black Screen).
2.  **TextureView (Fallback)**:
    *   *Pros*: Works on almost everything. Supports complex transforms (Zoom/Pan).
    *   *Cons*: Higher battery usage.
*   **Auto-Fallback**: The app attempts SurfaceView first. If `PixelCopy` detects a black screen after connection, it automatically switches to TextureView.

## 4. State Management
The `ConnectionState` enum drives the UI:
1.  **DISCONNECTED**: Idle.
2.  **CONNECTING**: Socket opening.
3.  **CONNECTED**: TCP open, waiting for Handshake.
4.  **AUTHENTICATED**: Password accepted.
5.  **STREAMING**: First frame received.
6.  **RECOVERING**: Automatic attempt to restore stall (re-negotiate).

## 5. Resilience & Watchdogs
Real-world networks are unstable. The Viewer employs aggressive self-healing:
*   **Connection Watchdog**:
    *   **Dead Socket**: If no PING/PONG for 10s, kills socket.
    *   **Stalled Frames**: If frames stop arriving (>6s) or never arrive (>12s), it downgrades state to attempt recovery.
*   **Render Watchdog**: If the decoder stops producing output (e.g., background corruption), it resets the decoder.
*   **Reconnect Loop**: If disconnected, it attempts to reconnect with **Exponential Backoff**:
    *   Delays: 1s -> 2s -> 4s -> 8s -> 10s (capped).
    *   Prevents rapid retry loops when server is down.

*   **StreamHealthSnapshot**:
    *   Provides a thread-safe, immutable snapshot of the entire pipeline state (FPS, Drops, Latency) for UI consumption. This prevents race conditions when the UI queries background threads.

## 6. Lifecycle & Backgrounding
To ensure reliability and battery efficiency, `StreamClient` handles Android lifecycle events explicitly:
*   **Backgrounded (`onAppBackgrounded`)**:
    *   **Action**: Intentionally closes the TCP socket and releases the Decoder.
    *   **Reason**: Prevents "Connection Reset" errors on the Server, stops battery drain, and avoids accumulating stale frames while invisible.
*   **Foregrounded (`onAppForegrounded`)**:
    *   **Action**: Automatically calls `connect()` to restore the session.
    *   **Reason**: Seamless user experience without manual intervention.

## 7. Talkback (Two-Way Audio)
*   **Input**: `AudioRecord` (Viewer Mic).
*   **Transport**: Muted by default to prevent feedback loops.
*   **Push-to-Talk**: When the button is held, audio is recorded and sent upstream via the `Sender` thread.

## 8. Device Specific Mitigations
*   **OnePlus Nord CE4 (and similar)**:
    *   **Issue**: Decoder outputs green/garbled frames immediately after a Reset/Keyframe.
    *   **Fix**: The `StreamClient` suppresses rendering for a short "warmup" period (dropping the first few output buffers) after a keyframe to ensure only clean video is shown.
