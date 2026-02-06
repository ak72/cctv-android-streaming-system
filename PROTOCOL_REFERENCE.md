# Protocol Reference

## Overview
The communication is **TCP-based**. It uses a line-based text protocol for control messages and a **header-declared binary payload** format for data.

**Delimiter**: `\n` (Newline) for text commands.

**Binary framing rule (CRITICAL)**:
- Some commands (e.g., `CSD`, `FRAME`, `AUDIO_FRAME`) are followed by a binary payload.
- The payload size is declared in the header (`size=...`, `sps=...`, `pps=...`).
- The receiver MUST read **exactly** that many bytes from the same socket stream immediately after the header line.
- If you fail to read the exact payload, the stream desynchronizes (binary bytes get interpreted as text commands).

**Single source of truth**: A consolidated list of every supported command and its fields is in [Protocol & Message Fields](#protocol--message-fields-consolidated) at the end of this document. When adding or changing messages, update that section and keep it in sync with the code.

## Protocol Version 3 (Binary Framing)

Starting with Protocol Version 3, Video Frames are sent using a **Fixed Binary Header** to improve performance and avoid text parsing overhead for high-frequency data.

**Header Format (13 bytes)**:
`[MARKER (1B)] [EPOCH (4B)] [FLAGS (4B)] [SIZE (4B)]`

*   All multi-byte fields are **Big Endian**.
*   **MARKER**: `0x00` (Distinguishes binary frame from ASCII text line).
*   **EPOCH**: The current stream epoch (int32).
*   **FLAGS**:
    *   Bit 0: Keyframe (1 = IDR/Keyframe, 0 = P-frame).
*   **SIZE**: The length of the H.264 payload that follows immediately.

**Receiver Logic**:
1.  Read 1 byte.
2.  If `0x00`: Read next 12 bytes (Epoch, Flags, Size) -> Read `Size` bytes payload.
3.  If `!= 0x00`: It is a text command (buffer until `\n`).

> **Note**: The "Common optional fields" (seq, srvMs, capMs) listed in the legacy section below are **NOT** present in the V3 binary header. They are only used in the Version 2 text-based `FRAME|...` header.


## Handshake
1.  **Client -> Server**: `HELLO|client=viewer|version=1` or `version=2`
    *   Identifies the client type. Version 2 indicates Viewer supports authoritative STREAM_STATE.
2.  **Server -> Client**: `AUTH_CHALLENGE|v=2|salt={salt}`
    *   Server provides a salt for the session.
3.  **Client -> Server**: `AUTH_RESPONSE|hash={hmac_sha256(password, salt)}`
    *   Client responds with the hashed password.
4.  **Server -> Client**:
    *   `AUTH_OK` (Success)
    *   `SESSION|id={uuid}` (Session token for resume)
    *   `PROTO|version=2` (Server supports STREAM_STATE; Viewer must obey server state when present)
    *   `STREAM_STATE|{code}|epoch={n}` (Full state snapshot after AUTH so late joiners never guess; see Connection state authority below)
    *   `STREAM_ACCEPTED|...` (Often sent immediately so the Viewer can configure a decoder quickly after reconnect)
    *   `AUTH_FAIL` (Wrong password, connection closed)

## Stream Negotiation
1.  **Client -> Server**: `CAPS|maxWidth={w}|maxHeight={h}|maxBitrate={bps}`
    *   Declares device limits.
2.  **Server -> Client**: `CAPS_OK`
2.  **Client -> Server**: `SET_STREAM|width={w}|height={h}|fps={fps}|bitrate={bps}`
    *   Requests specific quality.
3.  **Server -> Client**: `STREAM_ACCEPTED|epoch={n}|width={w}|height={h}|fps={f}|bitrate={b}`
    *   The *actual* stream parameters chosen by the server.
    *   Notes:
        *   The Primary may override requested `width/height` to match the encoder’s actual output (common in Buffer/ByteBuffer mode).
        *   Some builds include `session={uuid}` in this line (for resume/debugging).
4.  **Server -> Client**: `CSD|epoch={n}|sps={len}|pps={len}`
    *   Followed by `{len}` bytes of SPS and `{len}` bytes of PPS binary data.

## Data Transmission

### Video Frame (Server -> Client)
Header: `FRAME|epoch={n}|size={bytes}|key={t/f}|tsUs={timestamp}|...`
Payload: `{bytes}` of raw H.264.

**Common optional fields (newer builds)**:
- `seq={n}`: monotonic per-epoch sequence number (diagnostics / gap detection)
- `srvMs={ms}`: server wall-clock time when sending the frame (diagnostics)
- `capMs={ms}`: Primary capture wall-clock time (cross-device latency measurement)
- `ageMs={ms}`: capture→send age computed on Primary (diagnostics)

### Audio Frame (Bidirectional)
Header: `AUDIO_FRAME|dir={up/down}|size={bytes}|tsUs={timestamp}|rate={hz}|ch={n}|format={aac}`
Payload: `{bytes}` of audio data.

Notes:
- `dir=down`: Primary -> Viewer (stream audio)
    - Current Primary implementation sends **AAC-LC with ADTS headers included** when `format=aac`.
    - Some older builds may send raw PCM (no `format` field).
- `dir=up`: Viewer -> Primary (talkback)
    - Current Viewer implementation sends raw PCM16LE @ 48kHz mono (typically no `format` field).

## Control & Health

### Heartbeat
*   **Client**: `PING|tsMs={client_ms_since_epoch}`
*   **Server**: `PONG|tsMs={client_ts}|srvMs={server_ts}`

### Recovery
*   **Client**: `RESUME|session={uuid}`
    *   Tries to skip authentication/handshake.
*   **Server**: `RESUME_OK` or `RESUME_FAIL`.

### Connection state authority (protocol version 2)
The server is the **source of truth** for stream state. The Viewer must obey `STREAM_STATE` and must not infer state from side effects (CSD, keyframe arrival, socket open). Use numeric codes for stability and smaller packets.

*   **Server -> Client**: `STREAM_STATE|{code}|epoch={n}`
    *   `code`: 1 = ACTIVE, 2 = RECONFIGURING, 3 = PAUSED (optional), 4 = STOPPED (mandatory terminal state)
    *   `epoch`: same as STREAM_ACCEPTED/FRAME; Viewer drops frames where `frameEpoch != currentEpoch`
*   **When ACTIVE is sent**: Only **after the first keyframe is transmitted** for that epoch. Not on STREAM_ACCEPTED or encoder start (otherwise Viewer would show STREAMING and black).
*   **State snapshot**: After AUTH, server sends `PROTO|version=2` then `STREAM_STATE|2|epoch=0` (RECONFIGURING) so late joiners and reconnects never guess.

**State contract (Viewer must do):**

| Server state (code) | Viewer must do |
|---------------------|----------------|
| ACTIVE (1)          | Decode + render (STREAMING) |
| RECONFIGURING (2)   | Pause decode / show buffering (RECOVERING) |
| PAUSED (3)          | Keep socket alive (CONNECTED) |
| STOPPED (4)         | **DISCONNECTED** — close socket. Mandatory terminal state so viewer can distinguish "stream intentionally ended" from "network lost" for reconnect logic. |

Version 1 or when no STREAM_STATE received recently: Viewer may fall back to inferring state from CSD / STREAM_ACCEPTED / frame render. Always prefer server state when present.

### Rotation / Metadata (Server -> Client)
*   **Rotation**: `ENC_ROT|deg={0|90|180|270}`
*   **Recording state**: `RECORDING|active={true|false}`
*   **Camera facing**: `CAMERA|front={true|false}`
*   **Communication enabled**: `COMM|enabled={true|false}`

### Adaptive Streaming
*   **Client**: `BACKPRESSURE`
    *   "I am overwhelmed, please drop frames."
*   **Client**: `PRESSURE_CLEAR`
    *   "Pressure has cleared; resume normal flow."
*   **Client**: `ADJUST_BITRATE|bitrate={bps}`
    *   "Change encoder quality on the fly."

### Remote Commands (Client -> Server)
*   `REQ_KEYFRAME`
*   `START_RECORDING` / `STOP_RECORDING`
*   `SWITCH_CAMERA`
*   `ZOOM|ratio={float}` (e.g., `1.0`, `2.0`)

### Errors (Server -> Client)
*   `ERROR|reason=caps_required`
    * Sent when the Viewer attempted `SET_STREAM` before sending `CAPS`. The Viewer should retry deterministically by sending `CAPS` then `SET_STREAM`.
*   `STREAM_REJECTED|reason=unsupported`
    * Sent when the Viewer's `SET_STREAM` request exceeds its declared CAPS (width/height/bitrate).

---

## Protocol & Message Fields (consolidated)

This section lists **every** currently supported protocol command, direction, and fields in one place. Keep it in sync with the implementation (e.g. `ViewerSession.kt`, `StreamServer.kt`) to avoid documentation drift.

### Viewer → Primary (Client → Server)

| Command | Fields | Notes |
|--------|--------|--------|
| `HELLO` | `client=viewer`, `version=1` or `version=2` | Start handshake. Version 2 = supports STREAM_STATE. |
| `AUTH_RESPONSE` | `hash=<hmac_sha256(password,salt)>` | After AUTH_CHALLENGE. |
| `AUTH` | (legacy) | Deprecated; server responds with AUTH_FAIL. |
| `CAPS` | `maxWidth`, `maxHeight`, `maxBitrate` | Required before SET_STREAM. |
| `RESUME` | `session=<uuid>` | Skip auth; server replies RESUME_OK or RESUME_FAIL. |
| `SET_STREAM` | `width`, `height`, `fps`, `bitrate` | Request stream config; must send CAPS first. |
| `PING` | (none) or `tsMs=<client_wall_ms>` | Heartbeat. |
| `START_RECORDING` | — | Remote start recording. |
| `STOP_RECORDING` | — | Remote stop recording. |
| `REQ_KEYFRAME` | — | Request immediate keyframe. |
| `SWITCH_CAMERA` | — | Toggle front/back; ignored while recording. |
| `ZOOM` | `ratio=<float>` | e.g. 1.0, 2.0. |
| `AUDIO_FRAME` | `size=<bytes>` | Followed by binary payload (talkback PCM16LE). |
| `BACKPRESSURE` | — | Viewer overwhelmed; reduce/drop frames. |
| `PRESSURE_CLEAR` | — | Resume normal frame flow. |
| `ADJUST_BITRATE` | `bitrate=<bps>` | Request encoder bitrate change. |

### Primary → Viewer (Server → Client)

| Command | Fields | Binary payload | Notes |
|--------|--------|----------------|--------|
| `AUTH_CHALLENGE` | `v=2`, `salt=<string>` | — | After HELLO. |
| `AUTH_OK` | — | — | Auth success. |
| `AUTH_FAIL` | (optional) `reason=` | — | Auth failed; connection closed. |
| `PROTO` | `version=2` | — | Server supports STREAM_STATE; sent after AUTH_OK. |
| `SESSION` | `id=<uuid>` | — | Session token for RESUME. |
| `STREAM_STATE` | numeric `code`, `epoch=` | — | 1=ACTIVE, 2=RECONFIGURING, 3=PAUSED, 4=STOPPED (mandatory terminal). Sent after SESSION (snapshot) and when state changes. ACTIVE only after first keyframe. STOPPED when stream intentionally ended (Viewer → DISCONNECTED). |
| `CAPS_OK` | — | — | CAPS accepted. |
| `ERROR` | `reason=caps_required` | — | SET_STREAM before CAPS; retry CAPS then SET_STREAM. |
| `STREAM_REJECTED` | `reason=unsupported` | — | SET_STREAM exceeds CAPS. |
| `STREAM_ACCEPTED` | `epoch`, `width`, `height`, `bitrate`, `fps`, `session` | — | Actual stream params (may differ from request). |
| `CSD` | `epoch`, `sps=<len>`, `pps=<len>` | SPS bytes, then PPS bytes | H.264 codec config. |
| `FRAME` | `epoch`, `seq`, `size`, `key`, `tsUs`, `srvMs`, `capMs`, `ageMs` | `size` bytes H.264 | Video frame. |
| `AUDIO_FRAME` | `dir=down`, `format=aac`, `tsUs`, `size`, `rate`, `ch` | `size` bytes (AAC with ADTS) | Downstream audio. |
| `PONG` | (none) or `tsMs`, `srvMs` | — | Heartbeat reply. |
| `RESUME_OK` | — | — | Resume accepted. |
| `RESUME_FAIL` | — | — | Resume rejected. |
| `ENC_ROT` | `deg=` (0, 90, 180, 270) | — | Encoder rotation (display hint). |
| `RECORDING` | `active=` (true/false) | — | Recording state. |
| `CAMERA` | `front=` (true/false) | — | Current camera facing. |
| `COMM` | `enabled=` (true/false) | — | Talkback/communication enabled. |

All message names and field names are case-sensitive. Optional fields may be omitted in older builds; receivers should tolerate missing fields.
