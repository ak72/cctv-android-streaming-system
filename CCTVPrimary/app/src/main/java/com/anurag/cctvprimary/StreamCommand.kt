package com.anurag.cctvprimary

/**
 * Commands executed on the control thread (Command Bus consumer).
 * Ensures no session/accept thread ever holds encoder or camera locks.
 */
sealed class StreamCommand {
    object RequestKeyframe : StreamCommand()
    object StartRecording : StreamCommand()
    object StopRecording : StreamCommand()
    data class ReconfigureStream(val config: StreamConfig) : StreamCommand()
    object Backpressure : StreamCommand()
    object PressureClear : StreamCommand()
    data class AdjustBitrate(val bitrate: Int) : StreamCommand()
    object SwitchCamera : StreamCommand()
    data class Zoom(val ratio: Float) : StreamCommand()
    /** Trigger encoder recovery (stop/start) when watchdog detects no keyframe for too long. Handled via same cooldown path as onRecoveryNeeded. */
    object RecoverEncoder : StreamCommand()
}

/** Convert remote protocol command + payload to a StreamCommand for the command bus. */
fun remoteToStreamCommand(cmd: RemoteCommand, payload: Any?): StreamCommand = when (cmd) {
    RemoteCommand.REQ_KEYFRAME -> StreamCommand.RequestKeyframe
    RemoteCommand.START_RECORDING -> StreamCommand.StartRecording
    RemoteCommand.STOP_RECORDING -> StreamCommand.StopRecording
    RemoteCommand.BACKPRESSURE -> StreamCommand.Backpressure
    RemoteCommand.PRESSURE_CLEAR -> StreamCommand.PressureClear
    RemoteCommand.ADJUST_BITRATE -> StreamCommand.AdjustBitrate((payload as? Int) ?: 0)
    RemoteCommand.SWITCH_CAMERA -> StreamCommand.SwitchCamera
    RemoteCommand.ZOOM -> StreamCommand.Zoom((payload as? Float) ?: 1.0f)
}
