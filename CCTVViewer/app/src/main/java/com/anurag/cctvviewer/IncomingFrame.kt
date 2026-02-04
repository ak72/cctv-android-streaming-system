package com.anurag.cctvviewer

/**
 * Internal decoded/received frame container used by StreamClient.
 * Extracted from StreamClient.kt to keep the main file smaller.
 */
@Suppress("ArrayInDataClass")
internal data class IncomingFrame(
    val data: ByteArray,
    val length: Int,
    val isKeyFrame: Boolean,
    val receivedTimeUs: Long,
    val sentTimeUs: Long,
    val sentServerMs: Long,
    val captureServerMs: Long,
    val ageMsAtSend: Long
) {
    fun recycle() {
        ByteArrayPool.recycle(data)
    }
}

