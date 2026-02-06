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

## Code mapping

- **Primary:** `ViewerSession.StreamState` enum (CONNECTING, DISCONNECTED, AUTHENTICATED, STREAMING, RECONFIGURING) implements this contract. See `ViewerSession.kt`.
- **Viewer:** Stream state received via protocol (STREAM_STATE_ACTIVE, RECONFIGURING, etc.) and local connection state should align with the above for lifecycle decisions (e.g. when to reset decoder, when to show "reconnecting").
