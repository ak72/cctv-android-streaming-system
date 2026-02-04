package com.anurag.cctvprimary

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log

/**
 * Capability-based checks to avoid hardcoding device models.
 *
 * Used to decide if we should even attempt MediaCodec Surface input mode.
 */
internal object EncoderCapabilityDetector {
    private const val TAG = "CCTV_ENCODER_CAPS"

    /**
     * Returns true if the device exposes *any* AVC encoder that supports Surface input.
     *
     * Note: This is a necessary-but-not-sufficient condition. Some encoders configure fine but
     * still stall at runtime on certain OEM stacks — that is handled by runtime probing + persistence.
     */
    fun hasAvcSurfaceInputEncoder(): Boolean {
        return try {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            val infos = list.codecInfos
            for (info in infos) {
                if (!info.isEncoder) continue
                if (!info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }) continue
                val caps = try {
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                } catch (_: Throwable) {
                    continue
                }
                if (caps.colorFormats.any { it == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface }) {
                    return true
                }
            }
            false
        } catch (t: Throwable) {
            Log.w(TAG, "hasAvcSurfaceInputEncoder() failed; assume true (fallback to runtime)", t)
            true
        }
    }
}

