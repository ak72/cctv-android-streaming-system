package com.anurag.cctvprimary

import android.media.MediaCodecList
import android.media.MediaFormat

internal data class AvcEncoderCaps(
    val hasSurfaceInput: Boolean,
    val bitrateRangeBps: IntRange?
)

internal object CodecCapabilities {
    fun readAvcEncoderCaps(): AvcEncoderCaps {
        return try {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            var hasSurface = false
            var minBr: Int? = null
            var maxBr: Int? = null

            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                if (!info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }) continue
                val caps = runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrNull() ?: continue
                if (caps.colorFormats.any { it == android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface }) {
                    hasSurface = true
                }
                val vCaps = caps.videoCapabilities ?: continue
                val br = vCaps.bitrateRange
                val lo = br.lower
                val hi = br.upper
                if (minBr == null || lo < minBr) minBr = lo
                if (maxBr == null || hi > maxBr) maxBr = hi
            }
            AvcEncoderCaps(
                hasSurfaceInput = hasSurface,
                bitrateRangeBps = if (minBr != null && maxBr != null && maxBr >= minBr) (minBr..maxBr) else null
            )
        } catch (_: Throwable) {
            // Conservative defaults
            AvcEncoderCaps(hasSurfaceInput = true, bitrateRangeBps = null)
        }
    }
}

