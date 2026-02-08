# Foreground Service Policy (Mic / Talkback)

## Decision: Dynamic Escalation (Option B)

The service uses **dynamic escalation** to add the microphone FGS type only when mic capture is actually needed and eligible.

| State                | FGS Type             | When                                        |
|----------------------|----------------------|---------------------------------------------|
| Initial start        | `CAMERA` only        | Service starts, video capture begins        |
| Mic capture active   | `CAMERA` + `MICROPHONE` | Streaming audio or recording with audio  |

## Rationale

- **Android 14+ safety**: Claiming `FOREGROUND_SERVICE_TYPE_MICROPHONE` without permission or active mic use causes `SecurityException` and crashes. Starting camera-only avoids that.
- **Full functionality**: Escalation when mic is needed allows streaming audio, recording with audio, and talkback without policy violations.
- **Lifecycle safety**: Escalation happens only after `RECORD_AUDIO` is granted and before `AudioSourceEngine` captures.

## State Transitions

1. **Start**: `startForeground(notification, FOREGROUND_SERVICE_TYPE_CAMERA)` — no mic claimed.
2. **Escalate** (when `streamingRefCount > 0` or `recordingRefCount > 0` in `AudioSourceEngine`):
   - Require: `RECORD_AUDIO` granted.
   - Action: `startForeground(notification, CAMERA | MICROPHONE)`.
   - Log: `[FGS] type=CAMERA -> CAMERA|MICROPHONE (reason: mic_capture_started)`.
3. **No de-escalation**: Once mic type is added, it is kept until the service stops. This avoids any policy issues when briefly going idle.

## Talkback Clarification

- **Talkback** = Viewer → Primary audio playback (Primary uses `AudioTrack`, not microphone).
- Mic capture = Primary → Viewer streaming + recording (uses `AudioRecord` via `AudioSourceEngine`).
- Escalation is driven by **mic capture** (streaming/recording), not by talkback. Talkback works with camera-only FGS.

## Instrumentation

All FGS type transitions are logged with the `[FGS]` prefix for diagnostics:
- Initial: `[FGS] type=CAMERA started`
- Escalation: `[FGS] type=CAMERA -> CAMERA|MICROPHONE (reason: mic_capture_started)`
- Failure: `[FGS] escalation skipped (RECORD_AUDIO not granted)` or `[FGS] escalation failed`

## Manifest

The service declares both types so escalation is allowed:

```xml
android:foregroundServiceType="camera|microphone"
```

Permissions: `FOREGROUND_SERVICE_CAMERA`, `FOREGROUND_SERVICE_MICROPHONE`, `RECORD_AUDIO`.
