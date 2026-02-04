package com.anurag.cctvviewer

/**
 * Small internal packet containers extracted from StreamClient.kt.
 * These are intentionally dumb holders + pooling helpers.
 */

internal data class AacFrame(
    val data: ByteArray,
    val length: Int,
    val tsUs: Long
) {
    // ByteArray in data classes is referential by default; StreamClient relied on content equality.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AacFrame

        if (!data.contentEquals(other.data)) return false
        if (length != other.length) return false
        if (tsUs != other.tsUs) return false

        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + length
        result = 31 * result + tsUs.hashCode()
        return result
    }

    fun recycle() {
        ByteArrayPool.recycle(data)
    }
}

@Suppress("ArrayInDataClass")
internal data class AudioPacket(
    val data: ByteArray,
    val length: Int,
    val rate: Int,
    val ch: Int,
    val tsUs: Long
) {
    fun recycle() {
        ByteArrayPool.recycle(data)
    }
}

