package com.anurag.cctvprimary

import android.media.MediaCodecInfo
import android.media.MediaFormat

/**
 * Shared MediaCodec configuration utility
 * Ensures streaming and recording use the same codec settings for quality parity
 */
object MediaCodecConfig {
    
    /**
     * Create H.264 video format configuration
     * @param width Video width (will be aligned to 16)
     * @param height Video height (will be aligned to 16)
     * @param bitrate Bitrate in bps
     * @param frameRate Target frame rate
     * @param iFrameInterval I-frame interval in seconds
     * @return Configured MediaFormat
     */
    fun createVideoFormat(
        width: Int,
        height: Int,
        bitrate: Int,
        frameRate: Int,
        iFrameInterval: Int = 1
    ): MediaFormat {
        // Enforce 16-aligned resolution to prevent encoder crashes on some devices
        val alignedWidth = (width / 16) * 16
        val alignedHeight = (height / 16) * 16
        
        return MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, alignedWidth, alignedHeight
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
            // Use VBR for better compatibility (CBR is often unsupported or flaky on Android)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            // CRITICAL: Explicitly set Baseline Profile for Exynos compatibility
            // Exynos devices accept High Profile during configuration but fail to encode actual pixel data,
            // producing "empty" frames (8-11 bytes). Baseline Profile guarantees valid frame output.
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
        }
    }
    
    /**
     * Create AAC audio format configuration
     * @param sampleRate Sample rate in Hz
     * @param channels Number of channels (1 = mono, 2 = stereo)
     * @param bitrate Bitrate in bps
     * @return Configured MediaFormat
     */
    fun createAudioFormat(
        sampleRate: Int,
        channels: Int,
        bitrate: Int
    ): MediaFormat {
        return MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
    }
    
    /**
     * Align resolution to 16-pixel boundary (required by some encoders)
     */
    fun alignResolution16(width: Int, height: Int): Pair<Int, Int> {
        return Pair(
            (width / 16) * 16,  // Round down to nearest multiple of 16
            (height / 16) * 16
        )
    }
}
