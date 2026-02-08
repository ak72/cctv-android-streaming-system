# Framed transport (protocol version 3)

When the Viewer sends `HELLO|version=3`, the Primary responds with `PROTO|version=3` and sends **video frames** as binary instead of text line + payload. This removes boundary/parsing risk: the Viewer reads exact bytes.

## Binary video frame format

Every video frame is:

| Part     | Size   | Description |
|----------|--------|-------------|
| Marker   | 1 byte | `0x00` (distinguishes from text lines, which start with ASCII) |
| Epoch    | 4 bytes | Big-endian int. Stream epoch (must match STREAM_ACCEPTED). |
| Flags    | 4 bytes | Big-endian int. Bit 0 = keyframe (1) else 0. |
| Size     | 4 bytes | Big-endian int. Payload length in bytes. |
| Payload  | N bytes | Raw H.264 frame (N = Size). |

**Total header size:** 13 bytes (1 + 4 + 4 + 4).

## Viewer receive loop (version 3)

1. Read **1 byte**. If EOF, exit.
2. If byte == `0x00` (binary frame):
   - Read **12 bytes** (epoch, flags, size), big-endian.
   - Read **exactly** `size` bytes into the payload buffer (use `readFully` / exact read loop).
   - Decode epoch, flags (keyframe = `(flags & 1) != 0`), use payload.
3. Else (printable ASCII, start of control line):
   - Push back the 1 byte (e.g. `PushbackInputStream`), then read a line until `\n`.
   - Parse and handle control (e.g. STREAM_STATE, CSD, PONG, …).

Control messages (STREAM_STATE, CSD, SESSION, PROTO, etc.) remain **newline-terminated UTF-8 lines**. Only video frames use the binary format when version >= 3.

## Constants (Primary: ProtocolIO.kt)

- `BINARY_FRAME_MARKER = 0x00`
- `BINARY_FRAME_HEADER_SIZE = 13`

## Backward compatibility

- Viewer sends `HELLO|version=2` (or no version): Primary uses legacy text `FRAME|...|size=N` + payload.
- Primary supports `HELLO|version=3`: if a v3 client connects, Primary uses binary framed format for video; control stays line-based.

**Note:** The current Viewer implementation uses v2 only. It sends `HELLO|client=viewer|version=2` and parses text `FRAME|...|size=N` + payload. Binary framed (v3) is server-side ready; Viewer v3 parsing is not yet implemented.
