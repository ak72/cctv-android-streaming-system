# Protocol Reference

## Overview
The communication is **TCP-based**. It uses a line-based text protocol for control messages and a size-prefixed binary format for data.

**Delimiter**: `\n` (Newline) for text commands.

## Handshake
1.  **Client -> Server**: `HELLO|client=viewer|version=1`
    *   Identifies the client type.
2.  **Server -> Client**: `AUTH_CHALLENGE|v=2|salt={salt}`
    *   Server provides a salt for the session.
3.  **Client -> Server**: `AUTH_RESPONSE|hash={hmac_sha256(password, salt)}`
    *   Client responds with the hashed password.
4.  **Server -> Client**:
    *   `AUTH_OK` (Success)
    *   `SESSION|id={uuid}` (Session token for resume)
    *   `STREAM_ACCEPTED|...` (Sent immediately with default/active config)
    *   `AUTH_FAIL` (Wrong password, connection closed)

## Stream Negotiation
1.  **Client -> Server**: `CAPS|maxWidth={w}|maxHeight={h}|maxBitrate={bps}`
    *   Declares device limits.
2.  **Client -> Server**: `SET_STREAM|width={w}|height={h}|fps={fps}|bitrate={bps}`
    *   Requests specific quality.
3.  **Server -> Client**: `STREAM_ACCEPTED|epoch={n}|width={w}|height={h}|fps={f}|bitrate={b}`
    *   The *actual* stream parameters chosen by the server.
4.  **Server -> Client**: `CSD|epoch={n}|sps={len}|pps={len}`
    *   Followed by `{len}` bytes of SPS and `{len}` bytes of PPS binary data.

## Data Transmission

### Video Frame (Server -> Client)
Header: `FRAME|epoch={n}|key={t/f}|size={bytes}|tsUs={timestamp}|...`
Payload: `{bytes}` of raw H.264.

### Audio Frame (Bidirectional)
Header: `AUDIO_FRAME|dir={up/down}|format={aac/pcm}|size={bytes}|tsUs={timestamp}`
Payload: `{bytes}` of audio data.

## Control & Health

### Heartbeat
*   **Client**: `PING|tsMs={usage}`
*   **Server**: `PONG|tsMs={client_ts}|srvMs={server_ts}`

### Recovery
*   **Client**: `RESUME|session={uuid}`
    *   Tries to skip authentication/handshake.
*   **Server**: `RESUME_OK` or `RESUME_FAIL`.

### Adaptive Streaming
*   **Client**: `BACKPRESSURE`
    *   "I am overwhelmed, please drop frames."
*   **Client**: `ADJUST_BITRATE|bitrate={bps}`
    *   "Change encoder quality on the fly."
