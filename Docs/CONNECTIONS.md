# Connections & Session Management

## 1. Overview
The connection system in **CCTVPrimary** is a robust, threaded TCP Server implementation designed to handle multiple concurrent Viewers (CCTVViewer) with high reliability. It orchestrates the entire lifecycle of a remote surveillance session, from the initial TCP handshake to authentication, capability negotiation, and eventual disconnection.

Key characteristics:
*   **Protocol**: Custom line-based TCP protocol (ASCII commands for control, Binary for data). For a full list of every command and its fields, see [Protocol & Message Fields](PROTOCOL_REFERENCE.md#protocol--message-fields-consolidated).
*   **Port**: Configurable (defaults to `9090` via `AppSettings.DEFAULT_PORT`).
*   **Security**: CHAP-style password authentication (HMAC-SHA256 challenge/response; no plaintext password on the wire).
*   **Concurrency**: One thread per connection (Listener) + One thread per sender.
*   **Crash Hardening**: The server caps concurrent authenticated sessions (see `StreamServer.MAX_ACTIVE_SESSIONS`) to protect low-end devices from reconnect storms.

## 2. The Connection Process (Handshake)
The connection flow follows a strict state machine to ensure security and compatibility.

### Phase 1: TCP Connection
1.  **Listener**: `StreamServer` runs a dedicated `Accept` thread dealing with `ServerSocket.accept()`.
2.  **Client**: `CCTVViewer` initiates a raw TCP connection.
3.  **Result**: `StreamServer` creates a new `ViewerSession` object, which spawns its own `Listener` thread.

### Phase 2: Protocol Handshake (The "Hello" Dance)
Once the TCP socket is open, the application-level handshake begins:

1.  **Viewer**: Sends `HELLO|client=viewer|version=1` or `version=2` (version 2 indicates Viewer supports server-authoritative STREAM_STATE).
2.  **Primary**: Responds `AUTH_CHALLENGE|v=2|salt={salt}`
    *   *Purpose*: Challenge/response auth; avoids sending plaintext password.

### Phase 3: Authentication
1.  **Viewer**: Sends `AUTH_RESPONSE|hash={hmac_sha256(password, salt)}`
2.  **Primary**: Computes expected HMAC using the locally generated password (shown on UI) and the per-session salt.
3.  **Primary**:
    *   *Success*: Sends `AUTH_OK`, `SESSION|id={uuid}`, State becomes `AUTHENTICATED`.
    *   **Always**: Ensures the Viewer receives a `STREAM_ACCEPTED` (defaults if not negotiated yet) so the Viewer can configure decoder quickly after reconnect.
    *   *Failure*: Sends `AUTH_FAIL`, closes the socket.

> Note: Legacy plaintext `AUTH|password=...` is rejected (deprecated) to avoid leaking credentials on the network.

### Phase 4: Capability Exchange
1.  **Viewer**: Sends `CAPS|maxWidth=...|maxHeight=...|maxBitrate=...`
    *   *Purpose*: Tells Primary the device screen limits (e.g., don't send 4K to a 720p screen).
2.  **Primary**: Responds `CAPS_OK`.

### Phase 5: Stream Negotiation (Set Stream)
1.  **Viewer**: Sends `SET_STREAM|width=...|height=...|fps=...|bitrate=...`
    *   *Purpose*: Requests specific stream parameters.
2.  **Primary (`StreamServer`)**:
    *   **Arbitration**: If multiple viewers exist, it picks the *lowest common denominator* (lowest resolution/bitrate) to satisfy all.
    *   **Override**: The Primary may override the requested resolution to match the encoder’s *actual* stream dimensions (common in Buffer/ByteBuffer mode where the encoder uses fixed 4:3 sizes like 720x960 or 960x720).
3.  **Primary**: Broadcasts `STREAM_ACCEPTED|epoch=...|width=...|...` to ALL Viewers.
    *   *Note*: The `epoch` field is critical. It increments every time the encoder restarts. Viewers drop any frames from "old" epochs to prevent corruption.

## 3. Session Management

### A. ViewerSession `(ViewerSession.kt)`
Represent a single connected client. It manages:
*   **Input Loop**: continuously reads lines from the socket.
*   **Output Loop**: A dedicated `Sender` thread (to avoid blocking UI or Input).
*   **State**: `CONNECTING` -> `AUTHENTICATED` -> `STREAMING` / `RECONFIGURING`; `DISCONNECTED` (initial and terminal). See [SESSION_LIFECYCLE.md](SESSION_LIFECYCLE.md) for allowed transitions.
*   **Queues**:
    *   `ControlQueue`: High priority (CSD, Configs).
    *   `FrameQueue`: Low priority (Video Frames). Drops old frames if network is slow.
*   **Auxiliary Metadata** (Server -> Viewer):
    *   `ENC_ROT|deg=...` (orientation metadata; does not imply a stream restart)
    *   `RECORDING|active=true/false`
    *   `CAMERA|front=true/false`
    *   `COMM|enabled=true/false` (two-way comm/talkback availability)

### B. Heartbeat (Primary Watchdog)
To detect dead connections (e.g., WiFi dropped without closing socket):
*   **Viewer**: Sends `PING` every few seconds.
*   **Primary**: Responds `PONG`.
*   **Timeout**: The Primary uses a tolerant heartbeat timeout (currently **60s** in `ViewerSession`) to avoid false disconnects during brief scheduling stalls and background/foreground churn on the Viewer.

### C. Viewer Watchdog
The Viewer also monitors the connection's health in `StreamClient`:
*   **Stuck State**: Detects if connected but frames aren't arriving.
    *   **No Frames Ever**: If no frames received for 12s, downgrades `AUTHENTICATED` -> `CONNECTED`.
    *   **Stalled**: If frames were flowing but stopped (>6s ago), downgrades `AUTHENTICATED` -> `CONNECTED`.
*   **Escalation**:
    *   **Probe**: Requests keyframe (every ~5s).
    *   **Recover**: Re-negotiates `CAPS` + `SET_STREAM`.
    *   **Disconnect**: Forces reconnect if stuck too long (>20-25s).

### D. Reconnection & Backoff
If a connection drops (network glitch):
1.  **Viewer**: Reconnects TCP with **Exponential Backoff**:
    *   Attempt 1: 1s
    *   Attempt 2: 2s
    *   Attempt 3: 4s
    *   Attempt 4: 8s
    *   Attempt 5+: 10s (capped)
    *   *Reset*: Backoff resets to 1s on successful `AUTH_OK`.
2.  **Viewer**: Sends `RESUME|session={old_uuid}`.
3.  **Primary**: Checks if it remembers that Session ID.
    *   *Match*: Restores state, sends `RESUME_OK`, sends immediate IDR frame.
    *   *No Match*: Sends `RESUME_FAIL` (Viewer must full handshake).

## 4. Connection States
The `StreamState` enum (see also [SESSION_LIFECYCLE.md](SESSION_LIFECYCLE.md)) guides the logic:
1.  **CONNECTING**: Socket accepted; protocol handshake (HELLO, AUTH) in progress. Not yet authenticated.
2.  **DISCONNECTED**: Initial state or closed. Terminal state.
3.  **AUTHENTICATED**: Password verified, waiting for CAPS / stream negotiation.
4.  **STREAMING**: Normal operation. Frames are flowing.
5.  **RECONFIGURING**: Encoder is restarting (changing bitrate/resolution). Video flow is paused/dropped until new Keyframe.
