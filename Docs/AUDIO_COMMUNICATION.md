# Audio Communication Architecture

## 1. Overview
**CCTVPrimary** supports full Two-Way Audio (Talkback), allowing the Viewer and the Camera to talk to each other.
1.  **Stream Audio (Camera -> Viewer)**: Continuous listening (Monitor).
2.  **Talkback (Viewer -> Camera)**: Push-to-Talk (Intercom).

The system uses a "Shared Audio Engine" to ensure that Streaming Audio, Video Recording Audio, and Talkback can all coexist without locking the hardware microphone.

## 2. Camera -> Viewer (Streaming Audio)

### A. Architecture
*   **Source**: `AudioSourceEngine` (Singleton).
    *   Captures raw PCM (16-bit, 48kHz, Mono).
*   **Encoder**: `StreamAudioSender` (AAC).
    *   Encodes PCM -> AAC-LC (64kbps).
    *   Wraps AAC in ADTS headers.
*   **Transport**: Muxed into the same TCP connection as video.
    *   Command: `AUDIO_FRAME|dir=down|format=aac|size=...`

### B. Audio Processing `(StreamAudioSender.kt)`
Before encoding, the raw audio is processed for better clarity:
1.  **Volume Gain**: Applies a digital boost (1.5x) to pick up quiet sounds.
2.  **Soft Limiter**: A `tanh` (hyperbolic tangent) function limits loud peaks to prevent harsh clipping/distortion when volume is boosted.
3.  **Noise Suppression**: Uses Android's hardware `NoiseSuppressor` generic effect if available.

### C. Source Selection
The `AudioSourceEngine` intelligently picks the best microphone source:
*   **Streaming Only**: Uses `VOICE_COMMUNICATION` (Optimized for VoIP, echo cancellation enabled).
*   **Recording Active**: Switches to `CAMCORDER` (Optimized for fidelity).
    *   *Dynamic Switching*: If the user hits "Record" while streaming, the Engine seamlessly restarts the mic with the higher-quality `CAMCORDER` source.

## 3. Viewer -> Camera (Talkback)

### A. Architecture
*   **Source**: Viewer Microphone.
*   **Format**: Raw PCM16LE (16-bit, 48kHz, mono) for lowest latency interaction.
*   **Transport**: TCP Upload.
    *   Command: `AUDIO_FRAME|dir=up|size=...|rate=48000|ch=1`
*   **Destination**: `CameraForegroundService` -> `AudioTrack`.

### B. Playback `(CameraForegroundService.kt)`
The Camera receives the audio chunks and plays them out the speaker.

1.  **Buffer**: `ensureTalkbackTrack()` creates a low-latency `AudioTrack`.
2.  **Noise Gate**:
    *   **Problem**: In silence, the audio often carries static/hiss.
    *   **Solution**: A digital Noise Gate calculates the RMS (Root Mean Square) energy of the incoming chunk.
    *   **Threshold**: If RMS < 500.0, the chunk is silenced (dropped). This results in dead silence when the user isn't speaking, rather than static.
3.  **Playback**: Valid chunks are written to the speaker stream (`STREAM_MUSIC`).

## 4. Conflict Management
Android restricts microphone access. Only one app/process can hold the mic.

*   **Shared Engine**: `AudioSourceEngine` is the *only* place `new AudioRecord()` is called.
*   **Multicast**: It reads once, then loops through a list of `listeners` (Stream Sender, Video Recorder) and copies the data to them.
*   **Ref Counting**:
    *   `streamingRefCount`: Increment when streaming listener registers (e.g. when Viewer session needs downstream audio).
    *   `recordingRefCount`: Increment when recording starts (local or remote; e.g. `START_RECORDING` from Viewer).
    *   If both > 0, the mic stays open. It only closes when *both* are 0.
*   **Remote recording (Viewer-triggered)**: When recording is started via `START_RECORDING` from the Viewer, the Primary calls `startStreamingAudio()` before `startRecording(true)` so that `AudioSourceEngine` is capturing before `CustomRecorder` registers as a recording listener. This ensures the saved file includes audio when the user taps Record on the Viewer.

## 5. Known Constraints
*   **Echo**: While `AcousticEchoCanceler` is requested, it is hardware-dependent. If the Camera speaker is loud and close to the Mic, the Viewer might hear their own voice (Feedback Loop).
*   **Latency**: Network dependent. Typically 200-500ms.
*   **Permission**: Requires `RECORD_AUDIO`. If denied, audio silently fails (logs error).
