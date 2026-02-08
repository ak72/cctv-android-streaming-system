# Session lifecycle (Primary and Viewer)

This document defines the formal session states and the only allowed transitions. It applies to both Primary (ViewerSession) and Viewer; the Viewer mirrors Primary stream state for UI and decode behavior.

## States

| State | Meaning |
|-------|--------|
| **CONNECTING** | Socket accepted; protocol handshake (HELLO, AUTH) in progress. Not yet authenticated. |
| **AUTHENTICATED** | Auth succeeded; viewer may send SET_STREAM / other commands. No active stream yet. |
| **STREAMING** | Stream negotiated (STREAM_ACCEPTED); frames and CSD are being sent; viewer decodes and renders. |
| **RECONFIGURING** | Encoder or config is changing; viewer should expect new CSD/epoch and reset decoder when new STREAM_ACCEPTED arrives. |
| **DISCONNECTED** | Session closed (socket closed, timeout, or explicit close). Terminal state. |

## Allowed transitions

- **CONNECTING** → **AUTHENTICATED** (auth success), **DISCONNECTED** (auth failure, socket close, timeout).
- **AUTHENTICATED** → **STREAMING** (STREAM_ACCEPTED), **RECONFIGURING** (reconfig initiated before first frame), **DISCONNECTED**.
- **STREAMING** → **RECONFIGURING** (encoder reconfigure / resolution change), **DISCONNECTED**.
- **RECONFIGURING** → **STREAMING** (new STREAM_ACCEPTED), **DISCONNECTED**.
- **DISCONNECTED** → (none; terminal).

No other transitions are valid. For example: CONNECTING must not go directly to STREAMING; RECONFIGURING must not go to AUTHENTICATED.

## Protocol STREAM_STATE codes and Viewer mapping

The server sends `STREAM_STATE|{code}|epoch=N`. Codes and Viewer behavior:

| Code | Name          | Primary sends when                      | Viewer action / ConnectionState   |
|------|---------------|-----------------------------------------|-----------------------------------|
| 1    | ACTIVE        | After first keyframe for epoch          | Decode + render (STREAMING)       |
| 2    | RECONFIGURING | Encoder restart / config change         | Pause decode; show buffering (RECOVERING) |
| 3    | PAUSED        | (Optional, reserved)                    | Keep socket alive (CONNECTED)     |
| 4    | STOPPED       | Stream intentionally ended (stopCapture)| IDLE; close connection; **no auto-reconnect** |

When Viewer receives STOPPED (4), it sets `autoReconnect = false` before disconnecting so it does not attempt to reconnect. See PROTOCOL_REFERENCE.md for the full state contract.

## Code mapping

- **Primary:** `ViewerSession.StreamState` enum (CONNECTING, DISCONNECTED, AUTHENTICATED, STREAMING, RECONFIGURING) implements this contract. See `ViewerSession.kt`.
- **Viewer:** Stream state received via protocol (STREAM_STATE codes 1–4); ConnectionState includes STREAMING, RECOVERING, IDLE (for STOPPED). See PROTOCOL_REFERENCE.md and VIEWER_ARCHITECTURE.md.
