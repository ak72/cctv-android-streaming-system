package com.anurag.cctvviewer

import kotlin.math.sqrt

/**
 * Computes RMS for PCM 16-bit little-endian audio.
 *
 * Matches the StreamClient inline implementation (proper sign extension).
 */
internal fun computeRmsPcm16le(data: ByteArray, length: Int): Double {
    var sumSq = 0.0
    var samples = 0
    var i = 0
    while (i + 1 < length) {
        val low = data[i].toInt() and 0xFF
        val high = data[i + 1].toInt() and 0xFF
        var sample = (high shl 8) or low
        if (sample > 32767) sample -= 65536
        val v = sample
        sumSq += (v * v).toDouble()
        samples++
        i += 2
    }
    return if (samples == 0) 0.0 else sqrt(sumSq / samples)
}

