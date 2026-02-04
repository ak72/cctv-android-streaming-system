package com.anurag.cctvprimary

data class EncodedFrame(
    val data: ByteArray,
    val isKeyFrame: Boolean,
    val presentationTimeUs: Long,
    // Wall-clock time when the source frame was captured on the Primary device.
    // Used for cross-device latency measurement (Viewer uses PING/PONG clock sync).
    val captureEpochMs: Long = -1L
){
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncodedFrame) return false
        if (!data.contentEquals(other.data)) return false
        if (isKeyFrame != other.isKeyFrame) return false
        if (presentationTimeUs != other.presentationTimeUs) return false
        if (captureEpochMs != other.captureEpochMs) return false
        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + isKeyFrame.hashCode()
        result = 31 * result + presentationTimeUs.hashCode()
        result = 31 * result + captureEpochMs.hashCode()
        return result
    }
}


