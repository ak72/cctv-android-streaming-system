package com.anurag.cctvviewer

/**
 * Builds MPEG-4 AudioSpecificConfig (ASC) for AAC-LC.
 *
 * Used for MediaCodec AAC decoder initialization when receiving ADTS-framed AAC.
 */
internal fun buildAacLcAudioSpecificConfig(sampleRate: Int, channels: Int): ByteArray {
    fun sampleRateIndex(sr: Int): Int = when (sr) {
        96000 -> 0
        88200 -> 1
        64000 -> 2
        48000 -> 3
        44100 -> 4
        32000 -> 5
        24000 -> 6
        22050 -> 7
        16000 -> 8
        12000 -> 9
        11025 -> 10
        8000 -> 11
        else -> 3 // Default to 48k
    }

    val idx = sampleRateIndex(sampleRate)
    val audioObjectType = 2 // AAC LC
    val asc0 = (audioObjectType shl 3) or ((idx and 0x0F) shr 1)
    val asc1 = ((idx and 0x01) shl 7) or ((channels and 0x0F) shl 3)
    return byteArrayOf(asc0.toByte(), asc1.toByte())
}

