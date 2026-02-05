package com.anurag.cctvviewer

import android.media.AudioFormat

// StreamClient timing / socket constants
internal const val DECODE_TIMEOUT_US: Long = 10_000L
internal const val BACKPRESSURE_COOLDOWN_US: Long = 1_000_000L
internal const val HEARTBEAT_PING_INTERVAL_MS: Long = 2_000L
/** If no STREAM_STATE from server for this long while we trust server authority, downgrade to RECOVERING (authority liveness validation). */
internal const val STREAM_STATE_FRESHNESS_TIMEOUT_MS: Long = 12_000L
internal const val CONNECT_TIMEOUT_MS: Int = 6_000
internal const val SOCKET_READ_TIMEOUT_MS: Int = 15_000

// Audio constants
internal const val AUDIO_SAMPLE_RATE: Int = 48_000 // Native 48kHz VoIP
internal const val AUDIO_CHANNEL_CONFIG_OUT: Int = AudioFormat.CHANNEL_OUT_MONO
internal const val AUDIO_CHANNEL_CONFIG_IN: Int = AudioFormat.CHANNEL_IN_MONO
internal const val AUDIO_FORMAT: Int = AudioFormat.ENCODING_PCM_16BIT
internal const val AUDIO_FRAME_MS: Int = 20
internal const val AUDIO_BYTES_PER_FRAME: Int = (AUDIO_SAMPLE_RATE / 1000) * AUDIO_FRAME_MS * 2

// --- Lightweight adaptive jitter buffer (Viewer video) ---
internal const val JITTER_MIN_FRAMES: Int = 2
internal const val JITTER_MAX_FRAMES: Int = 4
internal const val JITTER_BACKLOG_DROP_EXTRA_FRAMES: Int = 10
internal const val JITTER_LOG_THROTTLE_MS: Long = 2_000L

// MediaCodec crop keys: available long before SDK exposed constants (stubs @RequiresApi(33)).
internal const val KEY_CROP_LEFT: String = "crop-left"
internal const val KEY_CROP_TOP: String = "crop-top"
internal const val KEY_CROP_RIGHT: String = "crop-right"
internal const val KEY_CROP_BOTTOM: String = "crop-bottom"

