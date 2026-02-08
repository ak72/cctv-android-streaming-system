# Stream state machine reference

Concise reference for Primary and Viewer stream state. For session lifecycle and protocol details, see SESSION_LIFECYCLE.md and PROTOCOL_REFERENCE.md.

## STREAM_STATE protocol codes (server-authoritative)

| Code | Name          | When server sends                  | Viewer action / ConnectionState   |
|------|---------------|------------------------------------|-----------------------------------|
| 1    | ACTIVE        | After first keyframe for epoch     | Decode + render (STREAMING)       |
| 2    | RECONFIGURING | Encoder restart / config change    | Pause decode; show buffering (RECOVERING) |
| 3    | PAUSED        | Reserved (optional)                | Keep socket alive (CONNECTED)     |
| 4    | STOPPED       | Stream intentionally ended         | IDLE; close; no auto-reconnect    |

Format: `STREAM_STATE|{code}|epoch={n}`. Viewer must obey; never infer state from CSD/keyframe alone when STREAM_STATE is in use.

## Primary session states (ViewerSession.StreamState)

| State        | Meaning                                                  |
|--------------|----------------------------------------------------------|
| CONNECTING   | Socket accepted; handshake (HELLO, AUTH) in progress     |
| AUTHENTICATED| Auth OK; viewer may send SET_STREAM; no active stream    |
| STREAMING    | Frames and CSD flowing; viewer decodes and renders       |
| RECONFIGURING| Encoder/config changing; viewer expects new CSD/epoch    |
| DISCONNECTED | Terminal; socket closed, timeout, or explicit close      |

### Primary transitions

- CONNECTING → AUTHENTICATED | DISCONNECTED
- AUTHENTICATED → STREAMING | RECONFIGURING | DISCONNECTED
- STREAMING → RECONFIGURING | DISCONNECTED
- RECONFIGURING → STREAMING | DISCONNECTED
- DISCONNECTED → (none)

## Viewer ConnectionState

| State       | Meaning                                                 |
|-------------|---------------------------------------------------------|
| DISCONNECTED| No socket                                               |
| CONNECTING  | TCP connect / handshake in progress                     |
| CONNECTED   | Auth OK; no video yet (“No Video”)                      |
| AUTHENTICATED| Caps/stream negotiated                                 |
| STREAMING   | Server sent ACTIVE; decode + render                     |
| RECOVERING  | Server sent RECONFIGURING or watchdog (frame drought)   |
| IDLE        | Server sent STOPPED; stream ended; no auto-reconnect    |

## Rules

1. **ACTIVE only after first keyframe** — Server must not send ACTIVE on STREAM_ACCEPTED alone; Viewer would show STREAMING with black.
2. **Epoch binding** — STREAM_ACCEPTED, CSD, FRAME include `epoch=`. Viewer drops frames where `frameEpoch != currentEpoch`.
3. **STOPPED = no reconnect** — On STOPPED, Viewer sets `autoReconnect = false` before disconnecting.
4. **Fallback** — When STREAM_STATE not received (e.g. old Primary), Viewer may infer from CSD/keyframe; prefer server state when present.
