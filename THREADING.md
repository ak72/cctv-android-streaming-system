# Streaming Pipeline Threading Model

This document describes the dedicated execution domains and rules used to avoid deadlocks and keep the streaming pipeline stable.

## Core rules (non-negotiable)

1. **No thread may wait on another thread that can indirectly wait on it.**  
   Avoid deadlocks: no blocking, no `.get()` on futures, no nested locks across layers.

2. **Nothing in the streaming pipeline may block longer than 5 ms.**  
   Applies to disk, locks, sockets, and codec usage. Streaming favors freshness over completeness.

3. **Everything is message-driven.**  
   Cross-layer work is done by posting commands or frames to queues; a dedicated thread consumes them. No synchronous cross-layer calls from session or accept threads into encoder/camera/recording.

## Six execution domains

Do not merge these. Each has a single responsibility.

| Domain | Responsibility | Must not touch |
|--------|----------------|----------------|
| **Camera** (CameraX / Camera2) | Capture frames, timestamp, push to encoder. | Sockets, sessions, database. |
| **Encoder** | Consume frames, produce encoded output, push to FrameBus (or StreamServer queue). | Sessions, sockets. |
| **FrameBus / sender** | Single queue (or lock-free bus); encoder publishes; one consumer fans out to sessions. | Encoder internals, camera. |
| **Session threads** | Per-viewer send (video/audio) and protocol receive. | Encoder, camera, recording. |
| **Control** | Auth, protocol, heartbeat, state transitions, **command bus consumer** (single loop on control executor; this thread never yields to other tasks on the same executor). Must never block on recording, camera rebind, or encoder from another thread. | Blocking on pipeline. |
| **Recording** | Start/stop recording and CustomRecorder work run on a **dedicated recording executor** (separate from control). Filesystem, MediaMuxer, DB. This ensures recording is never starved by the Command Bus loop. | Streaming sockets, session state. |

## FrameBus and backpressure

- **Design:** Single producer (encoder path via `StreamServer.enqueueFrame`), single consumer (sender loop on senderExecutor), bounded queue. The consumer uses `pollWithTimeout` so `stop()` can interrupt the drain cleanly (no blocking `take()`).
- **Drop policy:** **DROP_NON_KEYFRAME_ON_FULL.** When the bus is full, `publish()` returns false. The producer (StreamServer) then: if the frame is a keyframe, clears the queue and re-offers the keyframe; if non-keyframe, drops the frame. Publish failures are handled and logged at StreamServer; see `FrameBus` class KDoc and the comment above `frameBus.publish(frame)` in StreamServer.
- **Known limitation:** One slow session (e.g. bad Wi-Fi, full per-session queue) blocks the single fan-out loop and can cause uneven latency or bursty delivery across sessions.
- **Optional future improvements:** Single-producer / multi-consumer (one consumer per session), or per-session ring buffers fed from a non-blocking hub, so one slow viewer does not delay others. Left as future work.

## Socket write ordering (control vs data plane)

Control messages (STREAM_STATE, heartbeat, CSD, etc.) and video frames share the same socket, output stream, and flush. Under stress (e.g. slow network), a backlog of frame writes can delay control messages and cause heartbeat jitter or delayed STREAM_STATE updates. **No change in this pass.** Optional future improvements: prioritize control (e.g. drain control queue before frame queue in ViewerSession sender), allow control to preempt frame writes, or write-side multiplexing.

## Command Bus

Remote commands (keyframe request, start/stop recording, backpressure, zoom, etc.) are **posted** to a **Command Bus**. A single consumer runs on the **control executor** in an infinite loop (`queue.take()` → handler). Thus:

- Session and accept threads **never** call into CameraForegroundService or the encoder directly.
- The control thread runs only the Command Bus loop; it does **not** run recording start/stop. Recording start/stop are offloaded to the **recording executor** so they are never starved (the control thread never processes the executor task queue—it stays in the Command Bus loop).
- For commands that trigger recording, the control thread invokes `startRecording()` / `stopRecording()`, which **post** work to the recording executor; the actual MediaStore/File/MediaMuxer work runs on the recording thread.

See `StreamCommand.kt`, `CommandBus.kt`, `CameraForegroundService.handleStreamCommand()`, and `StreamingExecutors.recordingExecutor`.

## Golden rule

**Never call across layers synchronously.**

Example of a hidden deadlock risk (now avoided):

- ~~ViewerSession → requestKeyframe() → CameraForegroundService → `synchronized(encoderLock)` → encoder~~  
- **Correct:** ViewerSession / StreamServer → `commandBus.post(RequestKeyframe)` → control thread → `handleStreamCommand` → encoder.

## References

- `StreamingExecutors.kt`: shared executors (control, **recording**, encoding, sender, session pool). Recording has its own single-thread executor so it is not starved by the Command Bus consumer on the control executor.
- `CommandBus.kt`: single consumer on control executor, `StreamCommand` sealed class.
