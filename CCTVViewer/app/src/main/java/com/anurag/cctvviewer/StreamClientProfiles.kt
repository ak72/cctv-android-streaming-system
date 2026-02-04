package com.anurag.cctvviewer

/**
 * Internal stream profile used by StreamClient to pick initial stream quality.
 * Extracted from StreamClient.kt to keep the main file smaller.
 */
internal data class StreamProfile(
    val w: Int,
    val h: Int,
    val bitrate: Int,
    val fps: Int
)

