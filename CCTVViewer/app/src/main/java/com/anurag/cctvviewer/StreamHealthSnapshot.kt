package com.anurag.cctvviewer

/**
 * A small, immutable snapshot of stream/decoder health for UI and diagnostics.
 *
 * Intent:
 * - Provide Media3-style "single source of truth" signals (rx/render/heartbeat ages) to the UI layer
 *   WITHOUT exposing internal mutable fields directly.
 * - Keep this structure read-only; it MUST NOT be used to control the pipeline.
 */
data class StreamHealthSnapshot(
    val nowUptimeMs: Long,
    val connectionState: ConnectionState,
    val decodeRunning: Boolean,
    val surfaceReady: Boolean,
    val waitingForKeyframe: Boolean,
    val lastPongUptimeMs: Long,
    val lastFrameRxUptimeMs: Long,
    val lastFrameRenderUptimeMs: Long,
    val lastAudioDownRxUptimeMs: Long,
    val decodeQueueSize: Int,
    val rxOverloadDropCount: Long,
    val previewVisible: Boolean
)

