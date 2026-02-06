@file:Suppress("DEPRECATION")

package com.anurag.cctvprimary

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import android.os.Bundle

private const val TAG = "VIDEO_ENCODER"
private const val TIMEOUT_US = 10_000L

interface EncodedFrameListener {
    fun onEncodedFrame(frame: EncodedFrame)
}

/**
 * Sink for tee'ing encoder output to recording (MediaMuxer). Single encoder feeds both streaming and recording.
 * Called from encoder drain thread; implementations must be thread-safe for muxer writes.
 */
interface RecordingSink {
    fun onCsd(sps: ByteArray, pps: ByteArray)
    fun onEncodedFrame(frame: EncodedFrame)
}

/**
 * VideoEncoder - Supports Both Surface Input (Zero-Copy) and ByteBuffer Input (Fallback)
 *
 * Surface Input Mode:
 * - Uses MediaCodec.createInputSurface() for zero-copy hardware encoding
 * - Eliminates CPU-intensive YUV conversion (40-60% CPU reduction)
 * - Requires CameraX to feed Surface directly (not supported on all devices)
 *
 * ByteBuffer Input Mode (Fallback):
 * - Uses ImageAnalysis + YUV conversion + MediaCodec queueInputBuffer()
 * - Well-supported on all devices
 * - Provides reliable streaming when Surface input fails
 *
 * Mode Selection:
 * - Manual config override (forceBufferMode)
 * - Device-specific detection (known problematic devices)
 * - Automatic fallback (timeout-based detection)
 */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val bitrate: Int,
    private val frameRate: Int,
    private val iFrameInterval: Int, // I-frame interval in seconds (GOP). Used in both Surface and ByteBuffer modes.
    @Suppress("UNUSED_PARAMETER") private val context: Context, // Reserved for future device-specific features
    private val forceBufferMode: Boolean = false,
    @Suppress("UNUSED_PARAMETER") private val onEncodedFrame: (
        data: ByteBuffer, info: MediaCodec.BufferInfo, isKeyFrame: Boolean
    ) -> Unit // Legacy callback - replaced by setEncodedFrameListener()
) {
    // Surface input (zero-copy hardware pipeline)
    private var inputSurface: Surface? = null

    // ByteBuffer input mode (fallback)
    @Volatile
    var useSurfaceInput: Boolean = true
        private set

    @Volatile
    private var isRunning = false

    /**
     * Lifecycle safety guard.
     *
     * ImageAnalysis can call encode() while the encoder is being stopped/reconfigured.
     * If MediaCodec is in the middle of stop(), queueInputBuffer() can throw:
     * "queueInputBuffer() is valid only at Executing states; currently during stop()"
     *
     * We prevent that by:
     * - marking `stopping=true` before stopping/releasing
     * - synchronizing MediaCodec stop/release and queueInputBuffer on `codecLock`
     * - dropping frames when stopping/reconfiguring
     */
    private val codecLock = Any()

    @Volatile
    private var stopping: Boolean = false

    private var mediaCodec: MediaCodec? = null
    private var frameListener: EncodedFrameListener? = null
    private var codecConfigListener: ((sps: ByteArray, pps: ByteArray) -> Unit)? = null
    @Volatile private var recordingSink: RecordingSink? = null
    private var lastCsdSps: ByteArray? = null
    private var lastCsdPps: ByteArray? = null
    private val frameQueue = ArrayBlockingQueue<EncodedFrame>(30)

    // YUV conversion for ByteBuffer mode
    private val scratch = ByteArray(width * height * 3 / 2)
    private var loggedYuvLayout = false
    private var selectedInputColorFormat: Int =
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
    private var cachedMapKey: String? = null
    private var cachedRxMap: IntArray? = null
    private var cachedRyMap: IntArray? = null
    private var cachedRcxMap: IntArray? = null
    private var cachedRcyMap: IntArray? = null

    // FOV diagnostics (rate-limited)
    private var lastFovDbgUptimeMs: Long = 0L

    // Frame counting (shared between Surface and ByteBuffer modes)
    // Note: This counts OUTPUT frames (encoded), not input frames queued
    @Volatile
    private var encodedFrameCount = 0L
    private var startPtsUs = 0L
    private var lastEncodedNs = -1L // Tracks last used PTS to ensure monotonicity

    // Input frame counter for ByteBuffer mode (tracks queued frames)
    @Volatile
    private var inputFrameCount = 0L

    // Throttle for sync-frame requests (IDR).
    // Some OEM encoders behave poorly if MediaCodec.setParameters() is spammed.
    @Volatile private var lastSyncRequestUptimeMs: Long = 0L

    // Drain thread for output processing
    private var drainThread: Thread? = null

    @Volatile
    private var drainRunning = false
    private var csdSent = false

    // Track when encoder first got stuck (for accurate stuck duration tracking)
    @Volatile
    private var encoderStuckSinceMs: Long = -1L
    @Volatile
    private var recoveryRequestedForStuck: Boolean = false

    // Keyframe watchdog: if no keyframe for 2× GOP → request sync; if still none for 3× GOP → recovery
    @Volatile
    private var lastKeyframeOutputTimeMs: Long = -1L
    @Volatile
    private var keyframeDroughtSyncRequestedAtMs: Long = 0L

    /** Set in drain loop; invoked when recovery needed (stuck >5s or keyframe drought). */
    @Volatile
    private var onRecoveryNeeded: (() -> Unit)? = null

    // Map encoder PTS -> capture epoch time (ms) for real cross-device latency measurements.
    private val ptsToCaptureMs = HashMap<Long, Long>(256)

    fun setEncodedFrameListener(listener: EncodedFrameListener) {
        frameListener = listener
    }

    fun setCodecConfigListener(listener: (sps: ByteArray, pps: ByteArray) -> Unit) {
        codecConfigListener = listener
    }

    /**
     * Set callback for encoder recovery. When the encoder is stuck >5s or has had no keyframe for 3× GOP
     * (after requesting sync at 2× GOP), this is invoked. Owner should post to main thread and call stop/start
     * encoder so the pipeline cannot stall permanently.
     */
    fun setOnRecoveryNeeded(callback: (() -> Unit)?) {
        onRecoveryNeeded = callback
    }

    /**
     * Set sink for recording (tee). Encoder output is sent to both frameListener (streaming) and recordingSink (muxer).
     * If CSD was already sent, onCsd is invoked immediately so muxer can add video track.
     */
    fun setRecordingSink(sink: RecordingSink?) {
        recordingSink = sink
        if (sink != null) {
            val sps = lastCsdSps
            val pps = lastCsdPps
            if (sps != null && pps != null) {
                try {
                    sink.onCsd(sps, pps)
                } catch (e: Exception) {
                    Log.e(TAG, "RecordingSink.onCsd failed", e)
                }
            }
        }
    }

    companion object {
        /** Default I-frame interval in seconds (GOP). 2s is a good balance for mobile CPU/battery vs. seek and recovery. */
        const val DEFAULT_I_FRAME_INTERVAL_SEC = 2

        /**
         * Centralized decision: whether to prefer ByteBuffer input mode (skip Surface attempt).
         *
         * Uses:
         * - user override (AppSettings)
         * - capability detection (does any AVC encoder expose Surface input)
         * - runtime probe persistence (if Surface already failed on this build/firmware)
         * - small quirk list for confirmed vendor bugs
         */
        fun shouldPreferBufferMode(context: Context, forceBufferMode: Boolean): Boolean {
            if (forceBufferMode) return true
            if (!EncoderCapabilityDetector.hasAvcSurfaceInputEncoder()) return true
            if (EncoderProbeStore.wasSurfaceInputMarkedBad(context)) return true
            return false
        }
    }

    /**
     * Determine which input mode to use
     * Priority: forceBufferMode config → device detection → default Surface
     */
    private fun shouldUseBufferMode(): Boolean {
        // Priority 1: Manual config override / known quirks / capability detection / cached probe result
        if (shouldPreferBufferMode(context, forceBufferMode)) {
            Log.d(
                TAG,
                "Using Buffer mode: preferBuffer=true (force=$forceBufferMode, hasSurface=${EncoderCapabilityDetector.hasAvcSurfaceInputEncoder()}, cachedBad=${EncoderProbeStore.wasSurfaceInputMarkedBad(context)})"
            )
            return true
        }

        // Priority 3: Default to Surface mode (with automatic fallback on timeout)
        Log.d(
            TAG,
            "Using Surface mode: Will attempt Surface input first, fallback to Buffer on timeout"
        )
        return false
    }

    /**
     * Configuration strategy for encoder setup
     * Encapsulates width, height, profile, and level for fallback attempts
     */
    private data class ConfigStrategy(
        val width: Int, val height: Int, val profile: Int, val level: Int
    ) {
        val description: String
            get() = "${width}x${height}, profile=$profile, level=$level"
    }

    /**
     * Create MediaFormat for a given configuration strategy
     *
     * CRITICAL: For Surface input mode, must set COLOR_FormatSurface.
     * Without this, encoders reject the configuration because they default
     * to expecting ByteBuffer format (YUV420SemiPlanar) which conflicts
     * with createInputSurface().
     */
    private fun createFormatForStrategy(
        strategy: ConfigStrategy, bitrate: Int, frameRate: Int,
        iFrameInterval: Int
    ): MediaFormat {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, strategy.width, strategy.height
        ).apply {
            // CRITICAL: Specify Surface input format for zero-copy encoding
            // This tells the encoder we'll provide frames via Surface, not ByteBuffer
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )

            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)

            // CRITICAL: Use VBR instead of CBR for Exynos compatibility
            // Exynos chips (Samsung M30s, etc.) notoriously fail or stall with CBR mode
            // VBR provides better compatibility and quality for real-time streaming
            setInteger(
                MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            )
            setInteger(MediaFormat.KEY_PROFILE, strategy.profile)
            setInteger(MediaFormat.KEY_LEVEL, strategy.level)
            // Optional low-latency hint (vendor-specific; may have no effect on many devices)
            try {
                val key = MediaFormat::class.java.getDeclaredField("KEY_LATENCY")
                key.isAccessible = true
                (key.get(null) as? String)?.let { setInteger(it, 0) }
            } catch (_: Throwable) { /* KEY_LATENCY not in this SDK */ }
        }
        return format
    }

    fun start() {
        Log.d(TAG, "🔍 [CSD DIAGNOSTIC] VideoEncoder.start() called - isRunning=$isRunning")
        if (isRunning) {
            Log.w(TAG, "🔍 [CSD DIAGNOSTIC] Encoder already running, start() ignored")
            return
        }
        // Clear stop guard before starting.
        stopping = false

        // Determine input mode
        val useBufferMode = shouldUseBufferMode()
        useSurfaceInput = !useBufferMode
        Log.d(
            TAG,
            "🔍 [CSD DIAGNOSTIC] Input mode decision: useBufferMode=$useBufferMode, useSurfaceInput=$useSurfaceInput"
        )
        Log.d(
            TAG,
            "🟣 [FOVDBG][STREAM_START] encoderTarget=${width}x${height} bitrate=$bitrate fps=$frameRate mode=${if (useSurfaceInput) "Surface" else "ByteBuffer"}"
        )

        if (useSurfaceInput) {
            Log.d(TAG, "Starting H.264 encoder (Surface input mode)")
            try {
                startSurfaceMode()
            } catch (e: Exception) {
                Log.e(TAG, "Surface mode failed, falling back to Buffer mode", e)
                // Persist failure so next startup can skip the Surface attempt on this firmware/build.
                try {
                    EncoderProbeStore.markSurfaceInputBad(context)
                } catch (_: Throwable) {
                }
                // Runtime mode switch - restart with Buffer mode
                useSurfaceInput = false
                stopInternal() // Clean up Surface mode attempt
                startByteBufferMode()
            }
        } else {
            Log.d(TAG, "Starting H.264 encoder (ByteBuffer input mode)")
            startByteBufferMode()
        }

        Log.d(
            TAG,
            "🔍 [CSD DIAGNOSTIC] VideoEncoder.start() completed - isRunning=$isRunning, drainRunning=$drainRunning"
        )
    }

    /**
     * Start encoder in Surface input mode (zero-copy)
     */
    private fun startSurfaceMode() {
        // Calculate standard 1080p resolution if close to requested
        // Use 1080x1440 if requested width is close to 1080 (within 100 pixels)
        val standardWidth = if (kotlin.math.abs(width - 1080) <= 100) 1080 else width
        val standardHeight =
            if (standardWidth == 1080 && kotlin.math.abs(height - 1440) <= 100) 1440 else height

        // Try multiple configuration strategies with fallback
        val strategies = listOf(
            // Strategy 1: Explicit Aligned + Baseline Profile (compatibility first)
            ConfigStrategy(
                width,
                height,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                MediaCodecInfo.CodecProfileLevel.AVCLevel31
            ),
            // Strategy 2: Standard 1080p + Baseline Profile
            ConfigStrategy(
                standardWidth,
                standardHeight,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                MediaCodecInfo.CodecProfileLevel.AVCLevel31
            ),
            // Strategy 3: Surface-Derived (0x0) + Baseline Profile (safe fallback)
            ConfigStrategy(
                0,
                0,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                MediaCodecInfo.CodecProfileLevel.AVCLevel31
            ),
            // Strategy 4: High Profile (Higher quality if supported)
            ConfigStrategy(
                standardWidth,
                standardHeight,
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                MediaCodecInfo.CodecProfileLevel.AVCLevel4
            )
        )

        var configured = false
        var lastException: Exception? = null
        var successfulStrategy: ConfigStrategy? = null

        for (strategy in strategies) {
            try {
                val format = createFormatForStrategy(strategy, bitrate, frameRate, iFrameInterval)

                Log.d(TAG, "Trying encoder config: ${strategy.description}")

                mediaCodec = MediaCodec.createEncoderByType(
                    MediaFormat.MIMETYPE_VIDEO_AVC
                ).apply {
                    configure(
                        format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE
                    )

                    // Create input Surface AFTER configure() but BEFORE start()
                    // This Surface will be fed directly by CameraX for zero-copy hardware encoding
                    // #region agent log
                    DebugLog.log("primary", PrimaryDebugRunId.runId, "C", "VideoEncoder.startSurfaceMode", "surface_create_attempt", mapOf("event" to "surface_create_attempt"))
                    // #endregion
                    inputSurface = createInputSurface()
                    if (inputSurface != null) {
                        Log.d(TAG, "✓ Encoder Surface created: $inputSurface")
                    } else {
                        throw IllegalStateException("Failed to create encoder Surface")
                    }

                    start()
                }

                val configuredIFrameInterval = format.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL)
                Log.d(
                    TAG,
                    "Encoder configured successfully: ${strategy.description}, GOP=${configuredIFrameInterval}s"
                )
                configured = true
                successfulStrategy = strategy
                break // Success!

            } catch (e: MediaCodec.CodecException) {
                Log.w(
                    TAG,
                    "Encoder config failed (${strategy.description}): error=${e.errorCode}, message=${e.message}"
                )
                lastException = e
                // Clean up failed attempt
                try {
                    mediaCodec?.stop()
                    mediaCodec?.release()
                } catch (_: Exception) {
                }
                mediaCodec = null
                try {
                    inputSurface?.release()
                } catch (_: Exception) {
                }
                inputSurface = null
                continue // Try next strategy
            } catch (e: Exception) {
                Log.w(TAG, "Encoder config failed (${strategy.description}): ${e.message}")
                lastException = e
                // Clean up failed attempt
                try {
                    mediaCodec?.stop()
                    mediaCodec?.release()
                } catch (_: Exception) {
                }
                mediaCodec = null
                try {
                    inputSurface?.release()
                } catch (_: Exception) {
                }
                inputSurface = null
                continue
            }
        }

        if (!configured || mediaCodec == null) {
            val error = lastException
                ?: IllegalStateException("All encoder configuration strategies failed")
            Log.e(TAG, "Encoder start error - all 5 strategies failed", error)
            try {
                inputSurface?.release()
            } catch (_: Exception) {
            }
            inputSurface = null
            throw error
        }

        isRunning = true
        drainRunning = true

        // Start drain thread for output processing
        drainThread = Thread({ drainLoop() }, "VideoEncoder-Drain").apply {
            isDaemon = true
            start()
        }

        Log.d(
            TAG,
            "Encoder started successfully with Surface input using strategy: ${successfulStrategy?.description}"
        )
        requestSyncFrame()
    }

    /**
     * Start encoder in ByteBuffer input mode (fallback)
     */
    private fun startByteBufferMode() {
        // #region agent log
        DebugLog.log("primary", PrimaryDebugRunId.runId, "C", "VideoEncoder.startByteBufferMode", "bytebuffer_mode_used", mapOf("event" to "bytebuffer_mode_used", "w" to width, "h" to height))
        // #endregion
        Log.d(
            TAG,
            "🔍 [CSD DIAGNOSTIC] startByteBufferMode() called - width=$width, height=$height"
        )
        // MediaFormat for ByteBuffer input (no COLOR_FormatSurface)
        val format = MediaCodecConfig.createVideoFormat(
            width, height, bitrate, frameRate, iFrameInterval
        )

        // Select compatible color format for ByteBuffer input
        // Prefer NV12 (SemiPlanar) as it is the native format for most hardware encoders (Exynos, Qualcomm)
        // YuvUtils.selectInputColorFormat() handles priority: NV12 -> I420 -> Flexible
        selectedInputColorFormat = YuvUtils.selectInputColorFormat()
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, selectedInputColorFormat)
        Log.d(
            TAG,
            "🔍 [CSD DIAGNOSTIC] Encoder format configured: colorFormat=$selectedInputColorFormat"
        )

        // CRITICAL: Set KEY_MAX_INPUT_SIZE for ByteBuffer input mode
        // This tells the encoder how much data to expect per frame
        // Calculate based on ALIGNED resolution (MediaCodecConfig already aligns to 16-pixel boundaries)
        // Add generous padding to account for hardware stride/alignment requirements
        // Exynos encoders often require significantly larger buffers than exact YUV420 size
        // Use format dimensions (already aligned) instead of raw width/height
        val formatWidth = format.getInteger(MediaFormat.KEY_WIDTH)
        val formatHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
        val minInputSize =
            formatWidth * formatHeight * 3 / 2 // YUV420 (planar or semi-planar) - exact size
        // Increase padding from 64KB to 128KB for Exynos compatibility
        // Some Exynos encoders require very large stride buffers (e.g., 1024-byte alignment)
        val maxInputSize = minInputSize + 131072 // Add 128KB safety buffer for stride alignment
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSize)
        Log.d(
            TAG,
            "Set KEY_MAX_INPUT_SIZE=$maxInputSize bytes for ${width}x${height} encoder (min=$minInputSize + 128KB padding for hardware alignment)"
        )

        // CRITICAL: Verify profile and I-frame interval are set correctly in format
        val configuredProfile = format.getInteger(MediaFormat.KEY_PROFILE)
        val configuredLevel = format.getInteger(MediaFormat.KEY_LEVEL)
        val configuredIFrameInterval = format.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL)
        Log.d(
            TAG,
            "🔍 [PROFILE CHECK] MediaFormat profile=$configuredProfile (${if (configuredProfile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline) "Baseline" else "NOT Baseline"}), level=$configuredLevel, GOP=${configuredIFrameInterval}s"
        )

        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            Log.d(
                TAG,
                "Configuring encoder for ByteBuffer mode: ${width}x${height}, colorFormat=$selectedInputColorFormat, maxInputSize=$maxInputSize, profile=$configuredProfile, level=$configuredLevel"
            )
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()

            // Log the format we actually picked
            val fmtName = when (selectedInputColorFormat) {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> "NV12 (SemiPlanar)"
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> "I420 (Planar)"
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible -> "Flexible (Defaulting to I420 input)"
                else -> "Unknown ($selectedInputColorFormat)"
            }
            Log.d(
                TAG,
                "Encoder started successfully in ByteBuffer mode: colorFormat=$fmtName ($selectedInputColorFormat)"
            )

            // Note: We don't requestSyncFrame() immediately here because some encoders (e.g. Exynos)
            // auto-generate a keyframe on the first buffer, and an explicit request might confuse them.
            // We still request it if needed later or via explicit commands.
        }

        // CRITICAL: Start drain loop thread for ByteBuffer mode
        // This was missing, which is why CSD extraction never ran!
        isRunning = true
        drainRunning = true

        // Start drain thread for output processing
        drainThread = Thread({ drainLoop() }, "VideoEncoder-Drain").apply {
            isDaemon = true
            start()
        }

        Log.d(
            TAG,
            "🔍 [CSD DIAGNOSTIC] Drain thread created for ByteBuffer mode: name=${drainThread?.name}, alive=${drainThread?.isAlive}, state=${drainThread?.state}"
        )
        Log.d(
            TAG,
            "🔍 [CSD DIAGNOSTIC] Encoder state: isRunning=$isRunning, drainRunning=$drainRunning"
        )
    }

    /**
     * Get the input Surface for CameraX to feed frames directly
     * This enables zero-copy hardware encoding
     */
    fun getInputSurface(): Surface? = inputSurface

    /**
     * Adjust bitrate dynamically without encoder reset (seamless adaptation)
     *
     * IMPORTANT: Codec must be in Running state before calling setParameters()
     */
    fun adjustBitrate(newBitrate: Int) {
        val codec = mediaCodec ?: run {
            Log.w(TAG, "adjustBitrate called but codec is null")
            return
        }

        if (!isRunning) {
            Log.w(TAG, "adjustBitrate called but codec is not running")
            return
        }

        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate)
            }
            codec.setParameters(params)
            Log.d(TAG, "Bitrate adjusted to $newBitrate bps (seamless)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to adjust bitrate", e)
        }
    }

    /**
     * Drain loop - runs in background thread to process encoded frames
     */
    private fun drainLoop() {
        Log.d(
            TAG,
            "🔍 [CSD DIAGNOSTIC] Drain loop thread STARTED (thread=${Thread.currentThread().name})"
        )
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)

        val bufferInfo = MediaCodec.BufferInfo()
        val encoderStartTimeMs = System.currentTimeMillis()
        var lastNoFrameWarningMs = encoderStartTimeMs
        var lastPerformanceLogMs = encoderStartTimeMs
        var lastOutputFrameCount = 0L
        // Note: encodedFrameCount is a class member to track frames for both Surface and ByteBuffer modes

        var loopIteration = 0
        while (drainRunning) {
            val codec = mediaCodec ?: run {
                Log.w(TAG, "🔍 [CSD DIAGNOSTIC] Drain loop: mediaCodec is null, exiting loop")
                break
            }

            loopIteration++
            // Diagnostic: Log first few iterations
            if (loopIteration <= 10) {
                Log.d(
                    TAG,
                    "🔍 [CSD DIAGNOSTIC] Drain loop iteration #$loopIteration, drainRunning=$drainRunning"
                )
            }

            try {
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US) // 10ms timeout

                // Diagnostic: Log first few dequeueOutputBuffer results, then every 30th iteration
                if (loopIteration <= 10 || loopIteration % 30 == 0) {
                    val inputCount = synchronized(this@VideoEncoder) { inputFrameCount }
                    val outputCount = synchronized(this@VideoEncoder) { encodedFrameCount }
                    Log.d(
                        TAG,
                        "🔍 [CSD DIAGNOSTIC] Drain loop iteration #$loopIteration: dequeueOutputBuffer=$outputIndex, inputFrames=$inputCount, outputFrames=$outputCount, drainRunning=$drainRunning, frameQueueSize=${frameQueue.size}"
                    )
                }
                // CRITICAL: If encoder produced frames initially but then stopped, try to unstick it
                // This indicates the encoder is stuck - input buffers full but no output
                if (loopIteration % 60 == 0) {
                    val inputCount = synchronized(this@VideoEncoder) { inputFrameCount }
                    val outputCount = synchronized(this@VideoEncoder) { encodedFrameCount }
                    if (inputCount > 20 && outputCount == inputCount && outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // Track when encoder first got stuck
                        if (encoderStuckSinceMs < 0) {
                            encoderStuckSinceMs = System.currentTimeMillis()
                            Log.w(
                                TAG,
                                "🔴 [ENCODER STALL] Encoder stuck detected - tracking stuck duration from now"
                            )
                        }
                        val stuckDuration = if (encoderStuckSinceMs > 0) {
                            System.currentTimeMillis() - encoderStuckSinceMs
                        } else {
                            System.currentTimeMillis() - encoderStartTimeMs
                        }
                        Log.w(
                            TAG,
                            "⚠️ [ENCODER STALL] Encoder may be stuck: inputFrames=$inputCount, outputFrames=$outputCount (equal), dequeueOutputBuffer=$outputIndex (no output available), stuckDuration=${stuckDuration}ms. Attempting to unstick by requesting sync frame..."
                        )
                        // Try to unstick the encoder by requesting a sync frame
                        // This forces the encoder to output a keyframe, which may flush buffered frames
                        try {
                            requestSyncFrame()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to request sync frame to unstick encoder", e)
                        }

                        // CRITICAL: If encoder has been stuck for more than 5 seconds, request recovery (restart)
                        if (stuckDuration > 5000) {
                            if (!recoveryRequestedForStuck) {
                                recoveryRequestedForStuck = true
                                Log.e(
                                    TAG,
                                    "🔴 [ENCODER STALL] CRITICAL: Encoder stuck ${stuckDuration}ms (>5s). Requesting encoder recovery (restart)."
                                )
                                try {
                                    onRecoveryNeeded?.invoke()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Recovery callback failed", e)
                                }
                            }
                        }
                    } else {
                        // Encoder is not stuck - reset stuck tracking
                        if (encoderStuckSinceMs > 0) {
                            Log.d(
                                TAG,
                                "🔵 [ENCODER STALL] Encoder recovered - resetting stuck tracking"
                            )
                            encoderStuckSinceMs = -1L
                            recoveryRequestedForStuck = false
                        }
                    }
                }

                // Keyframe watchdog: if no keyframe for 2× GOP → request sync; if still none for 3× GOP → recovery
                if (loopIteration % 60 == 0 && iFrameInterval > 0) {
                    val nowMs = System.currentTimeMillis()
                    val refMs = if (lastKeyframeOutputTimeMs >= 0) lastKeyframeOutputTimeMs else encoderStartTimeMs
                    val droughtMs = nowMs - refMs
                    val gopMs = iFrameInterval * 1000L
                    if (droughtMs > 2 * gopMs) {
                        if (keyframeDroughtSyncRequestedAtMs == 0L) {
                            Log.w(TAG, "🔴 [KEYFRAME WATCHDOG] No keyframe for ${droughtMs}ms (2× GOP=${2 * gopMs}ms). Requesting sync frame.")
                            try {
                                requestSyncFrame()
                            } catch (e: Exception) {
                                Log.e(TAG, "Keyframe watchdog: requestSyncFrame failed", e)
                            }
                            keyframeDroughtSyncRequestedAtMs = nowMs
                        } else if (droughtMs > 3 * gopMs) {
                            Log.e(TAG, "🔴 [KEYFRAME WATCHDOG] Still no keyframe after 3× GOP (${droughtMs}ms). Requesting encoder recovery.")
                            keyframeDroughtSyncRequestedAtMs = 0L
                            try {
                                onRecoveryNeeded?.invoke()
                            } catch (e: Exception) {
                                Log.e(TAG, "Recovery callback failed", e)
                            }
                        }
                    }
                }

                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // Warn if no frames received after 3 seconds
                        // Note: INFO_TRY_AGAIN_LATER means no output available, so we check if encoder has produced any frames yet
                        val nowMs = System.currentTimeMillis()
                        val elapsedMs = nowMs - encoderStartTimeMs

                        // Only warn if we haven't encoded any frames yet and enough time has passed
                        if (elapsedMs > 3000L) {
                            val currentFrameCount =
                                synchronized(this@VideoEncoder) { encodedFrameCount }
                            // Only log warning if still no frames (encoder might have produced frames between checks)
                            if (currentFrameCount == 0L && nowMs - lastNoFrameWarningMs > 5000L) {
                                lastNoFrameWarningMs = nowMs
                                val mode =
                                    if (useSurfaceInput) "Surface (check if CameraX is feeding)" else "ByteBuffer (check if frames are queued)"
                                val inputQueued =
                                    synchronized(this@VideoEncoder) { inputFrameCount }
                                Log.w(
                                    TAG,
                                    "⚠️ No frames encoded after ${elapsedMs}ms in $mode mode - input frames queued: $inputQueued, output frames: $currentFrameCount"
                                )

                                // Diagnostic: Check if drain loop is even running
                                if (!useSurfaceInput) {
                                    Log.w(
                                        TAG,
                                        "🔵 [DIAGNOSTIC] ByteBuffer mode: Encoder received $inputQueued input frames but produced no output"
                                    )
                                    Log.w(
                                        TAG,
                                        "🔵 [DIAGNOSTIC] Possible causes: Encoder waiting for more input, keyframe generation delayed, or encoder configuration issue"
                                    )

                                    // Check if encoder is still running
                                    if (inputQueued == 0L) {
                                        Log.w(
                                            TAG,
                                            "🔵 [DIAGNOSTIC] NO INPUT FRAMES QUEUED - Check if ImageAnalysis is feeding frames to encoder.encode()"
                                        )
                                    } else if (inputQueued < 5L) {
                                        Log.w(
                                            TAG,
                                            "🔵 [DIAGNOSTIC] Only $inputQueued input frames - Encoder may need more frames before first output"
                                        )
                                    } else {
                                        Log.w(
                                            TAG,
                                            "🔵 [DIAGNOSTIC] $inputQueued input frames queued but no output - Encoder may be stuck"
                                        )
                                    }
                                }
                            }
                        }
                        Thread.sleep(5) // Small delay to prevent busy-waiting
                        continue
                    }

                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = codec.outputFormat
                        val spsBuf = fmt.getByteBuffer("csd-0")
                        val ppsBuf = fmt.getByteBuffer("csd-1")
                        if (spsBuf != null && ppsBuf != null) {
                            val sps = byteBufferToArray(spsBuf)
                            val pps = byteBufferToArray(ppsBuf)
                            Log.d(
                                TAG,
                                "Encoder config (csd) ready: sps=${sps.size}, pps=${pps.size}"
                            )
                            lastCsdSps = sps.copyOf()
                            lastCsdPps = pps.copyOf()
                            codecConfigListener?.invoke(sps, pps)
                            try {
                                recordingSink?.onCsd(sps, pps)
                            } catch (e: Exception) {
                                Log.e(TAG, "RecordingSink.onCsd failed", e)
                            }
                            Log.d(TAG, "CSD listener invoked (should trigger broadcastCsd)")
                        } else {
                            Log.w(
                                TAG, "Output format changed but csd buffers missing: $fmt"
                            )
                        }
                    }

                    outputIndex >= 0 -> {
                        // Lazy CSD Check: Some encoders (Exynos) might skip INFO_OUTPUT_FORMAT_CHANGED
                        // or bunding it, so we check outputFormat on the first valid buffer.
                        val currentFrameCount =
                            synchronized(this@VideoEncoder) { encodedFrameCount }
                        val currentCsdSent = csdSent

                        // DIAGNOSTIC: Log entry into outputIndex >= 0 branch
                        if (currentFrameCount < 5) {
                            Log.d(
                                TAG,
                                "🔍 [CSD DIAGNOSTIC] Entering outputIndex >= 0 branch: frameCount=$currentFrameCount, csdSent=$currentCsdSent, size=${bufferInfo.size}"
                            )
                        }

                        if (!csdSent) {
                            try {
                                val fmt = codec.outputFormat
                                val spsBuf = fmt.getByteBuffer("csd-0")
                                val ppsBuf = fmt.getByteBuffer("csd-1")
                                if (spsBuf != null && ppsBuf != null) {
                                    val sps = byteBufferToArray(spsBuf)
                                    val pps = byteBufferToArray(ppsBuf)
                                    Log.i(
                                        TAG,
                                        "🔵 [CSD RECOVERY] Found CSD in outputFormat on first frame: sps=${sps.size}, pps=${pps.size}"
                                    )

                                    // DIAGNOSTIC: Log CSD data and listener status
                                    val spsHex = sps.take(8).joinToString(" ") {
                                        String.format(
                                            "%02X", it.toInt() and 0xFF
                                        )
                                    }
                                    val ppsHex = pps.take(8).joinToString(" ") {
                                        String.format(
                                            "%02X", it.toInt() and 0xFF
                                        )
                                    }
                                    Log.d(
                                        TAG,
                                        "🔍 [CSD DIAGNOSTIC] CSD extracted - SPS (first 8 bytes): $spsHex, PPS (first 8 bytes): $ppsHex"
                                    )
                                    Log.d(
                                        TAG,
                                        "🔍 [CSD DIAGNOSTIC] codecConfigListener is ${if (codecConfigListener != null) "SET" else "NULL"}"
                                    )

                                    lastCsdSps = sps.copyOf()
                                    lastCsdPps = pps.copyOf()
                                    if (codecConfigListener != null) {
                                        Log.d(
                                            TAG,
                                            "🔍 [CSD DIAGNOSTIC] Invoking codecConfigListener with sps=${sps.size} bytes, pps=${pps.size} bytes"
                                        )
                                        codecConfigListener?.invoke(sps, pps)
                                        Log.d(
                                            TAG,
                                            "🔍 [CSD DIAGNOSTIC] codecConfigListener invoked (should trigger broadcastCsd)"
                                        )
                                    } else {
                                        Log.e(
                                            TAG,
                                            "🔴 [CSD DIAGNOSTIC] CRITICAL: codecConfigListener is NULL - CSD will NOT be broadcast!"
                                        )
                                    }
                                    try {
                                        recordingSink?.onCsd(sps, pps)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "RecordingSink.onCsd failed", e)
                                    }
                                    csdSent = true
                                    Log.d(
                                        TAG,
                                        "🔍 [CSD DIAGNOSTIC] csdSent set to true from lazy outputFormat check"
                                    )
                                } else {
                                    Log.w(
                                        TAG,
                                        "🔍 [CSD DIAGNOSTIC] Lazy outputFormat check: spsBuf=${spsBuf != null}, ppsBuf=${ppsBuf != null}"
                                    )
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to lazy-fetch CSD: ${e.message}")
                            }
                        }

                        if (bufferInfo.size > 0) {
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                Log.i(
                                    TAG,
                                    "🔵 [CSD RECOVERY] Received CODEC_CONFIG buffer (${bufferInfo.size} bytes)"
                                )

                                if (!csdSent) {
                                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                                    if (outputBuffer != null) {
                                        val data = ByteArray(bufferInfo.size)
                                        outputBuffer.position(bufferInfo.offset)
                                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                        outputBuffer.get(data)

                                        // Parse SPS/PPS from the buffer (usually glued: [00 00 00 01] SPS [00 00 00 01] PPS)
                                        // Simple NAL parser: Find second start code
                                        var sepIndex = -1
                                        for (i in 4 until data.size - 4) {
                                            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                                                sepIndex = i
                                                break
                                            }
                                        }

                                        if (sepIndex > 0) {
                                            val sps = data.copyOfRange(0, sepIndex)
                                            val pps = data.copyOfRange(sepIndex, data.size)
                                            Log.i(
                                                TAG,
                                                "🔵 [CSD RECOVERY] Extracted SPS/PPS from buffer: sps=${sps.size}, pps=${pps.size}"
                                            )
                                            lastCsdSps = sps.copyOf()
                                            lastCsdPps = pps.copyOf()
                                            codecConfigListener?.invoke(sps, pps)
                                            try {
                                                recordingSink?.onCsd(sps, pps)
                                            } catch (e: Exception) {
                                                Log.e(TAG, "RecordingSink.onCsd failed", e)
                                            }
                                            csdSent = true
                                        } else {
                                            Log.w(
                                                TAG,
                                                "Could not split CSD buffer (no second start code found). Sending whole serving as SPS."
                                            )
                                            val fallbackSps = data.copyOf()
                                            val fallbackPps = ByteArray(0)
                                            lastCsdSps = fallbackSps
                                            lastCsdPps = fallbackPps
                                            codecConfigListener?.invoke(fallbackSps, fallbackPps)
                                            try {
                                                recordingSink?.onCsd(fallbackSps, fallbackPps)
                                            } catch (e: Exception) {
                                                Log.e(TAG, "RecordingSink.onCsd failed", e)
                                            }
                                            csdSent = true
                                        }
                                    }
                                }

                                codec.releaseOutputBuffer(outputIndex, false)
                                continue
                            }

                            val outputBuffer = codec.getOutputBuffer(outputIndex) ?: run {
                                codec.releaseOutputBuffer(outputIndex, false)
                                continue
                            }

                            val data = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outputBuffer.get(data)

                            val isKeyFrame =
                                bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0

                            // Emergency CSD Recovery from Keyframe (In-Band Method)
                            // Check first 5 frames (keyframe or not) if CSD not yet sent
                            // Exynos encoders may embed CSD in-band or delay it to later frames
                            val checkFrameCount =
                                synchronized(this@VideoEncoder) { encodedFrameCount }
                            val checkCsdSent = csdSent

                            // DIAGNOSTIC: Log condition check
                            if (checkFrameCount < 5) {
                                Log.d(
                                    TAG,
                                    "🔍 [CSD DIAGNOSTIC] Checking in-band CSD extraction: frameCount=$checkFrameCount, csdSent=$checkCsdSent, size=${bufferInfo.size}, isKeyFrame=$isKeyFrame"
                                )
                            }

                            if (!checkCsdSent && checkFrameCount < 5) {
                                Log.d(
                                    TAG,
                                    "🔍 [CSD DIAGNOSTIC] Condition met! Entering CSD extraction code path for frame #${checkFrameCount + 1}"
                                )
                                val data = ByteArray(bufferInfo.size)
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                outputBuffer.get(data)
                                // Reset position for standard processing down below
                                outputBuffer.position(bufferInfo.offset)

                                // CRITICAL: Hex dump first frame for diagnosis
                                if (checkFrameCount == 0L) {
                                    val hexDumpSize = minOf(512, data.size)
                                    val hexDump = data.take(hexDumpSize).joinToString(" ") {
                                        String.format("%02X", it.toInt() and 0xFF)
                                    }
                                    Log.w(
                                        TAG,
                                        "🔍 [CSD DIAGNOSTIC] FIRST FRAME HEX DUMP (first $hexDumpSize bytes):\n$hexDump"
                                    )
                                    Log.w(
                                        TAG,
                                        "🔍 [CSD DIAGNOSTIC] Frame size=${bufferInfo.size}, isKeyFrame=$isKeyFrame, frameNum=${checkFrameCount + 1}"
                                    )
                                }

                                // Enhanced NAL Parser: Handle both 3-byte (00 00 01) and 4-byte (00 00 00 01) start codes
                                // Exynos encoders commonly use 3-byte start codes for SPS/PPS
                                val nalOffsets = ArrayList<Int>()
                                var i = 0
                                while (i < data.size - 4) {
                                    // Check for 4-byte start code: 00 00 00 01
                                    if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                                        nalOffsets.add(i + 4) // Store position AFTER start code
                                        i += 4
                                        continue
                                    }
                                    // Check for 3-byte start code: 00 00 01 (but NOT 00 00 00 01)
                                    // Must ensure previous byte is NOT 0 to avoid double-counting 4-byte codes
                                    if (i > 0 && data[i - 1] != 0.toByte() && data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                                        nalOffsets.add(i + 3) // Store position AFTER 3-byte start code
                                        i += 3
                                        continue
                                    }
                                    i++
                                }

                                if (checkFrameCount == 0L) {
                                    Log.w(
                                        TAG,
                                        "🔍 [CSD DIAGNOSTIC] Found ${nalOffsets.size} NAL start codes in first frame"
                                    )
                                }

                                if (nalOffsets.size >= 2) {
                                    // We expect at least SPS(0) and PPS(1). Frame content comes after.
                                    // nalOffsets contains positions AFTER start codes
                                    val idx0 = nalOffsets[0]
                                    val idx1 = nalOffsets[1]
                                    val idx2 = if (nalOffsets.size > 2) nalOffsets[2] else data.size

                                    // Determine start code length for idx0 (check backward)
                                    var startCode0Len = 4
                                    if (idx0 >= 4 && data[idx0 - 4] == 0.toByte() && data[idx0 - 3] == 0.toByte() && data[idx0 - 2] == 0.toByte() && data[idx0 - 1] == 1.toByte()) {
                                        startCode0Len = 4
                                    } else if (idx0 >= 3 && data[idx0 - 3] == 0.toByte() && data[idx0 - 2] == 0.toByte() && data[idx0 - 1] == 1.toByte()) {
                                        startCode0Len = 3
                                    }

                                    // NAL type is first byte after start code
                                    if (idx0 < data.size) {
                                        val type0 = data[idx0].toInt() and 0x1F
                                        val type1 =
                                            if (idx1 < data.size) data[idx1].toInt() and 0x1F else -1

                                        if (checkFrameCount == 0L) {
                                            Log.d(
                                                TAG,
                                                "🔍 [CSD DIAGNOSTIC] NAL#0 type=$type0 (idx=$idx0, startCodeLen=$startCode0Len), NAL#1 type=$type1 (idx=$idx1)"
                                            )
                                        }

                                        // SPS = 7, PPS = 8
                                        if (type0 == 7 && type1 == 8) {
                                            // Extract SPS/PPS including their start codes
                                            val spsStart = idx0 - startCode0Len
                                            val sps = data.copyOfRange(spsStart, idx1)
                                            val pps = data.copyOfRange(idx1 - startCode0Len, idx2)

                                            Log.i(
                                                TAG,
                                                "🔵 [CSD RECOVERY] SUCCESS: Extracted In-Band SPS/PPS from frame #${checkFrameCount + 1}! sps=${sps.size}, pps=${pps.size}, startCodeLen=$startCode0Len"
                                            )
                                            lastCsdSps = sps.copyOf()
                                            lastCsdPps = pps.copyOf()
                                            codecConfigListener?.invoke(sps, pps)
                                            try {
                                                recordingSink?.onCsd(sps, pps)
                                            } catch (e: Exception) {
                                                Log.e(TAG, "RecordingSink.onCsd failed", e)
                                            }
                                            csdSent = true
                                        } else if (type0 == 7 || type1 == 8) {
                                            if (checkFrameCount == 0L) {
                                                Log.w(
                                                    TAG,
                                                    "🔍 [CSD DIAGNOSTIC] Found partial NAL types: type0=$type0 (SPS=${type0 == 7}), type1=$type1 (PPS=${type1 == 8})"
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    if (checkFrameCount == 0L) {
                                        Log.w(
                                            TAG,
                                            "🔍 [CSD DIAGNOSTIC] In-Band extraction: Found ${nalOffsets.size} start codes (need >= 2 for SPS+PPS)"
                                        )
                                    }
                                }
                            } else {
                                // DIAGNOSTIC: Log why condition failed
                                if (checkFrameCount < 5) {
                                    Log.d(
                                        TAG,
                                        "🔍 [CSD DIAGNOSTIC] Condition NOT met: csdSent=$checkCsdSent (expected false), frameCount=$checkFrameCount (expected < 5)"
                                    )
                                }
                            }

                            // CRITICAL: Filter out very small frames that are likely corrupted or incomplete
                            // MediaCodec sometimes produces tiny frames (< 50 bytes) that are not valid video data
                            // These can cause decoder issues and should be filtered out
                            // CRITICAL DIAGNOSTIC: 
                            // 11-byte frames are likely valid "Skip Frames" (empty P-frames) common in static scenes on some encoders.
                            // Filtering them creates large timestamp gaps (e.g., 1s) which can stall the decoder.
                            // We will ALLOW them to pass for now to maintain stream continuity.
                            if (bufferInfo.size < 50 && !isKeyFrame) {
                                Log.d(
                                    TAG,
                                    "⚠️ [VIDEO ENCODER] Passthrough small P/B frame: size=${bufferInfo.size}, isKeyFrame=$isKeyFrame - Maintaining stream continuity"
                                )
                            }

                            // Original filter logic (commented out for diagnostic):
                            /* 
                            if (bufferInfo.size < 50 && !isKeyFrame) {
                                // ... logging ...
                                codec.releaseOutputBuffer(outputIndex, false)
                                continue
                            }
                            */

                            // Record capture time for latency measurement
                            val capMs = ptsToCaptureMs.remove(bufferInfo.presentationTimeUs) ?: -1L
                            val encodeTimeMs = System.currentTimeMillis()
                            val encodeLatencyMs = if (capMs > 0) (encodeTimeMs - capMs).coerceAtLeast(0L) else -1L

                            val frame = EncodedFrame(
                                data = data,
                                isKeyFrame = isKeyFrame,
                                presentationTimeUs = bufferInfo.presentationTimeUs,
                                captureEpochMs = capMs
                            )
                            if (isKeyFrame) {
                                lastKeyframeOutputTimeMs = System.currentTimeMillis()
                                keyframeDroughtSyncRequestedAtMs = 0L
                            }

                            // CRITICAL: Only fill internal frameQueue if someone is actually polling it
                            // The frameQueue is for legacy "pull-based" access via pollEncodedFrame()
                            // Since we use frameListener (push-based), we don't need to fill the queue
                            // unless someone is actively polling it to avoid memory waste
                            // For now, we skip filling the queue entirely since pollEncodedFrame() is unused
                            // If future code needs pull-based access, uncomment this and ensure pollEncodedFrame() is called
                            // val queueOffered = frameQueue.offer(frame)
                            // if (!queueOffered) {
                            //     Log.w(TAG, "⚠️ [VIDEO ENCODER] Internal frameQueue full (capacity=30), dropping frame: size=${bufferInfo.size}, isKeyFrame=$isKeyFrame")
                            // }

                            // Always log first few frames and keyframes for diagnostics
                            val frameCount = synchronized(this@VideoEncoder) { encodedFrameCount }
                            val shouldLogFrame = frameCount < 10 || isKeyFrame || (frameCount % 30 == 0L)
                            
                            if (frameListener == null) {
                                if (shouldLogFrame) {
                                    Log.w(
                                        TAG,
                                        "🔴 [VIDEO ENCODER] No frameListener set; encoded frame will not be streamed (size=${bufferInfo.size}, isKeyFrame=$isKeyFrame, frameCount=$frameCount)"
                                    )
                                }
                            } else {
                                val listenerStartTime = System.nanoTime()
                                // CRITICAL: Log frame size to diagnose why frames are so small (11 bytes)
                                if (bufferInfo.size < 100) {
                                    if (shouldLogFrame) {
                                        Log.w(
                                            TAG,
                                            "⚠️ [VIDEO ENCODER] WARNING: Very small frame size=${bufferInfo.size} bytes, isKeyFrame=$isKeyFrame, frameCount=$frameCount - this may be corrupted or incomplete!"
                                        )
                                    }
                                } else {
                                    if (shouldLogFrame) {
                                        Log.d(
                                            TAG,
                                            "🔵 [VIDEO ENCODER] Calling frameListener.onEncodedFrame: size=${bufferInfo.size}, isKeyFrame=$isKeyFrame, frameCount=$frameCount"
                                        )
                                    }
                                }
                                try {
                                    frameListener?.onEncodedFrame(frame)
                                    val listenerDuration =
                                        (System.nanoTime() - listenerStartTime) / 1_000_000L // Convert to ms
                                    if (listenerDuration > 10 || shouldLogFrame) {
                                        val latencyInfo = if (encodeLatencyMs >= 0) {
                                            "encodeLatency=${encodeLatencyMs}ms (capture→encode)"
                                        } else {
                                            "encodeLatency=unknown"
                                        }
                                        Log.d(
                                            TAG,
                                            "🔵 [VIDEO ENCODER] frameListener.onEncodedFrame() completed: duration=${listenerDuration}ms, size=${bufferInfo.size}, isKeyFrame=$isKeyFrame, pts=${bufferInfo.presentationTimeUs}us, $latencyInfo"
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.e(
                                        TAG,
                                        "🔴 [VIDEO ENCODER] frameListener.onEncodedFrame() threw exception",
                                        e
                                    )
                                }
                            }
                            // Tee to recording sink (muxer); independent of frameListener so record-only with shared encoder works
                            try {
                                recordingSink?.onEncodedFrame(frame)
                            } catch (e: Exception) {
                                Log.e(TAG, "RecordingSink.onEncodedFrame failed", e)
                            }

                            // frameCount already computed above for logging
                            synchronized(this@VideoEncoder) { ++encodedFrameCount }
                            if (frameCount == 1L) {
                                if (isKeyFrame) {
                                    Log.d(
                                        TAG,
                                        "✓ First keyframe encoded! size=${bufferInfo.size} bytes, pts=${bufferInfo.presentationTimeUs} us"
                                    )
                                } else {
                                    Log.d(
                                        TAG,
                                        "✓ First video frame encoded (non-key, size=${bufferInfo.size} bytes) - decoder needs keyframe to start; requestSyncFrame will force one soon"
                                    )
                                }
                            }
                            if (frameCount % 300 == 0L) {
                                Log.d(TAG, "Encoded $frameCount frames")
                            }
                            
                            // Periodic performance metrics (every 5 seconds)
                            val nowMs = System.currentTimeMillis()
                            if (nowMs - lastPerformanceLogMs >= 5000L) {
                                val elapsedSeconds = (nowMs - lastPerformanceLogMs) / 1000.0
                                val outputFramesDelta = frameCount - lastOutputFrameCount
                                val outputFps = if (elapsedSeconds > 0) outputFramesDelta / elapsedSeconds else 0.0
                                val inputCount = synchronized(this@VideoEncoder) { inputFrameCount }
                                val lag = inputCount - frameCount
                                val avgEncodeLatency = if (encodeLatencyMs >= 0) encodeLatencyMs else -1L
                                
                                Log.i(
                                    TAG,
                                    "📊 [PERFORMANCE] Encoder metrics (last ${elapsedSeconds.toInt()}s): inputFrames=$inputCount, outputFrames=$frameCount, lag=$lag frames, outputFPS=${String.format("%.1f", outputFps)}, avgEncodeLatency=${if (avgEncodeLatency >= 0) "${avgEncodeLatency}ms" else "unknown"}, isRunning=$isRunning"
                                )
                                
                                lastPerformanceLogMs = nowMs
                                lastOutputFrameCount = frameCount
                            }
                        }

                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in drain loop", e)
                break
            }
        }

        Log.d(TAG, "Drain loop exited")
    }

    /**
     * Legacy pull-based frame access (currently unused).
     *
     * NOTE: This function is currently unused. The encoder uses push-based access via frameListener.
     * If you need to use this function, uncomment the frameQueue.offer() call in drainLoop()
     * to start filling the queue again. Otherwise, the queue remains empty to save memory.
     *
     * @return The next encoded frame from the queue, or null if the queue is empty
     */
    @Suppress("unused")
    fun pollEncodedFrame(): EncodedFrame? {
        return frameQueue.poll()
    }

    fun stop() {
        stopInternal()
    }

    /**
     * Internal stop method - can be called during mode switching
     */
    private fun stopInternal() {
        if (!isRunning && mediaCodec == null) return

        Log.d(
            TAG, "Stopping encoder (mode=${if (useSurfaceInput) "Surface" else "Buffer"})"
        )
        // Mark as stopping early so encode() can drop frames safely.
        stopping = true
        isRunning = false
        drainRunning = false

        try {
            drainThread?.join(1000)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while waiting for drain thread", e)
        }

        // Ensure no encode() thread can call MediaCodec APIs while we stop/release it.
        synchronized(codecLock) {
            try {
                // #region agent log
                DebugLog.log("primary", PrimaryDebugRunId.runId, "E", "VideoEncoder.stopInternal", "codec_stop_before", mapOf("event" to "codec_stop_before", "useSurfaceInput" to useSurfaceInput))
                // #endregion
                inputSurface?.release()
                inputSurface = null

                mediaCodec?.stop()
                mediaCodec?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Encoder stop error", e)
            } finally {
                mediaCodec = null
                drainThread = null
            }
        }
    }

    fun requestSyncFrame() {
        val requestTimeMs = System.currentTimeMillis()
        val codec = mediaCodec ?: run {
            Log.w(TAG, "⚠️ [SYNC FRAME] Cannot request sync frame: encoder is null")
            return
        }
        // Throttle sync requests to avoid overloading MediaCodec on some OEM devices.
        // Keyframe requests are a recovery tool, not a per-frame control signal.
        val nowUptimeMs = android.os.SystemClock.uptimeMillis()
        val last = synchronized(this) { lastSyncRequestUptimeMs }
        if (last > 0L && (nowUptimeMs - last) < 400L) {
            Log.d(TAG, "🔵 [SYNC FRAME] Throttled sync request (sinceLast=${nowUptimeMs - last}ms)")
            return
        }
        try {
            // Request sync frame immediately. Some encoders may ignore rapid requests,
            // but requesting it ensures the next frame (or very soon after) will be a keyframe.
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec.setParameters(params)
            synchronized(this) { lastSyncRequestUptimeMs = nowUptimeMs }
            val inputCount = synchronized(this) { inputFrameCount }
            val outputCount = synchronized(this) { encodedFrameCount }
            val lag = inputCount - outputCount
            Log.d(
                TAG,
                "🔵 [SYNC FRAME] Requested sync (key) frame at ${requestTimeMs}ms - inputFrames=$inputCount, outputFrames=$outputCount, lag=$lag frames, isRunning=$isRunning, useSurfaceInput=$useSurfaceInput"
            )
        } catch (e: Exception) {
            Log.w(TAG, "🔴 [SYNC FRAME] Failed to request sync frame at ${requestTimeMs}ms", e)
        }
    }

    private fun byteBufferToArray(buf: ByteBuffer): ByteArray {
        val dup = buf.duplicate()
        dup.clear()
        val out = ByteArray(dup.remaining())
        dup.get(out)
        return out
    }

    // YUV conversion methods - now in YuvUtils (shared with CustomRecorder)

    /**
     * Encode a video frame from ImageAnalysis (ByteBuffer mode)
     * Called by CameraForegroundService when ImageAnalysis receives a frame
     *
     * CRITICAL: ImageProxy ownership - this method consumes the ImageProxy synchronously
     * and closes it after processing. Caller should NOT close it.
     */
    fun encode(image: ImageProxy, closeImage: Boolean = true) {
        fun closeIfNeeded() {
            if (closeImage) {
                try {
                    image.close()
                } catch (_: Throwable) {
                }
            }
        }
        // Log entry to confirm ImageAnalysis is working
        // Use a lightweight check to avoid spamming (e.g. first 5 frames or every 30th)
        // We use a local simple counter for entry logging to avoid synchronization lock contention at strict start

        if (!isRunning || stopping) {
            closeIfNeeded()
            return
        }

        if (useSurfaceInput) {
            closeIfNeeded()
            return
        }

        val codec = synchronized(codecLock) { mediaCodec } ?: run {
            closeIfNeeded()
            return
        }

        try {
            // Log entry (diagnostic)
            // Use Double Check with local volatile or just rely on the sync block later for the main counter
            // For now, unconditional debug log for the first few attempts is safest to prove liveness

            val currentCount = synchronized(this@VideoEncoder) { inputFrameCount }
            // #region agent log
            if (currentCount.toInt() == 0) {
                DebugLog.log("primary", PrimaryDebugRunId.runId, "D", "VideoEncoder.encode", "first_frame_bytebuffer", mapOf("event" to "first_frame_bytebuffer", "imageW" to image.width, "imageH" to image.height))
            }
            // #endregion
            if (currentCount < 50) {
                Log.d(
                    TAG,
                    "🔵 [FRAME FLOW] encode() called with image: ${image.width}x${image.height}, timestamp=${image.imageInfo.timestamp}"
                )
            }

            val rotToApply = YuvUtils.normalizeRotation(image.imageInfo.rotationDegrees)

            // FOV diagnostics (ByteBuffer stream path):
            // If cropRect is smaller than the buffer, CameraX is already cropping the sensor output (FOV reduction).
            // This helps distinguish "source-cropped" vs "render-cropped" issues.
            try {
                val nowUptime = android.os.SystemClock.uptimeMillis()
                if (nowUptime - lastFovDbgUptimeMs >= 10_000L) {
                    lastFovDbgUptimeMs = nowUptime
                    val cr = image.cropRect
                    val fullCrop =
                        (cr.left == 0 && cr.top == 0 && cr.right == image.width && cr.bottom == image.height)
                    Log.d(
                        TAG,
                        "🟣 [FOVDBG][STREAM_ENC] mode=ByteBuffer src=${image.width}x${image.height} crop=${cr.left},${cr.top},${cr.right},${cr.bottom} fullCrop=$fullCrop rotMeta=${image.imageInfo.rotationDegrees} rotApplied=$rotToApply dst=${width}x${height}"
                    )
                }
            } catch (_: Throwable) {
            }

            // FPS throttling is currently disabled.
            // Previous implementation was broken: it assumed encoding started at time 0, making the throttling
            // condition effectively dead code (nowNs is always much larger than inputQueued * minIntervalNs).
            // TODO: If frame rate limiting is needed, implement proper throttling with a start time reference:
            //   - Track encoding start time when encoder starts (e.g., `val encodingStartTimeNs = System.nanoTime()`)
            //   - Calculate expected frame time: `val expectedFrameNs = encodingStartTimeNs + (inputQueued * minIntervalNs)`
            //   - Skip frame if `nowNs < expectedFrameNs`
            // Note: CameraX/ImageAnalysis may already throttle at the source, so this may not be necessary.

            // Check if frame queue is backing up (skip frames if needed)
            // DISABLED: frameQueue is no longer filled (see drainLoop() comment)
            // We use push-based access via frameListener, so the queue remains empty
            // If pollEncodedFrame() is used in the future, re-enable this check
            // if (frameQueue.size > 20) { image.close(); return }

            // Dequeue must not race with stop/release.
            val inputIndex = synchronized(codecLock) {
                if (!isRunning || stopping || mediaCodec !== codec) {
                    -1
                } else {
                    codec.dequeueInputBuffer(TIMEOUT_US)
                }
            }
            if (inputIndex < 0) {
                val currentInputCount = synchronized(this@VideoEncoder) { inputFrameCount }
                val currentOutputCount = synchronized(this@VideoEncoder) { encodedFrameCount }
                // CRITICAL: If encoder is stuck (inputFrames == outputFrames), log every failure
                // Otherwise, throttle logging to avoid spam
                val isStuck = currentInputCount > 20 && currentInputCount == currentOutputCount
                if (currentInputCount < 5 || currentInputCount % 30 == 0L || isStuck) {
                    Log.w(
                        TAG,
                        "🔴 [FRAME FLOW] dequeueInputBuffer failed (returned $inputIndex) - encoder input buffers full or encoder in bad state. inputFrameCount=$currentInputCount, encodedFrameCount=$currentOutputCount, isStuck=$isStuck"
                    )
                    if (isStuck) {
                        // Encoder is stuck - try to unstick it by requesting sync frame
                        try {
                            requestSyncFrame()
                            Log.d(
                                TAG,
                                "🔵 [FRAME FLOW] Requested sync frame to unstick encoder (dequeueInputBuffer failing)"
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "🔴 [FRAME FLOW] Failed to request sync frame", e)
                        }
                    }
                }
                closeIfNeeded()
                return
            }

            val inputBuffer = codec.getInputBuffer(inputIndex) ?: run {
                closeIfNeeded()
                return
            }

            // CRITICAL: Check encoder state again before writing to buffer.
            // The buffer can become inaccessible if encoder is stopped/reconfigured between
            // dequeueInputBuffer() and getInputBuffer(). We must verify state before writing.
            synchronized(codecLock) {
                if (!isRunning || stopping || mediaCodec !== codec) {
                    // Encoder was stopped/reconfigured - drop frame and release buffer
                    try {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                    } catch (_: Exception) {
                        // Encoder may already be stopped - ignore
                    }
                    closeIfNeeded()
                    return
                }
            }

            // Log capacity to check if we have enough room (debug only)
            // if (inputFrameNum <= 5) { // Log.d(TAG, "Input buffer capacity: ${inputBuffer.capacity()} bytes") }

            val frameSize = try {
                inputBuffer.clear()
                when (selectedInputColorFormat) {
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> {
                        // NV12 (interleaved UV)
                        YuvUtils.yuv420ToNV12Rotated(
                            image,
                            inputBuffer,
                            rotToApply,
                            width,
                            height,
                            scratch,
                            loggedYuvLayout,
                            cachedMapKey,
                            cachedRxMap,
                            cachedRyMap,
                            cachedRcxMap,
                            cachedRcyMap
                        )
                    }

                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> {
                        // I420 (planar)
                        YuvUtils.yuv420ToI420RotatedToI420(
                            image,
                            inputBuffer,
                            rotToApply,
                            width,
                            height,
                            scratch,
                            loggedYuvLayout,
                            cachedMapKey,
                            cachedRxMap,
                            cachedRyMap,
                            cachedRcxMap,
                            cachedRcyMap
                        )
                    }

                    else -> {
                        // Fallback to I420
                        YuvUtils.yuv420ToI420RotatedToI420(
                            image,
                            inputBuffer,
                            rotToApply,
                            width,
                            height,
                            scratch,
                            loggedYuvLayout,
                            cachedMapKey,
                            cachedRxMap,
                            cachedRyMap,
                            cachedRcxMap,
                            cachedRcyMap
                        )
                    }
                }
            } catch (e: IllegalStateException) {
                // Buffer became inaccessible (encoder stopped/reconfigured during write)
                Log.w(
                    TAG,
                    "⚠️ [ENCODER] Buffer became inaccessible during YUV conversion (encoder likely stopped/reconfigured). Dropping frame.",
                    e
                )
                // Try to release the buffer gracefully
                try {
                    synchronized(codecLock) {
                        if (mediaCodec === codec) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                        }
                    }
                } catch (_: Exception) {
                    // Encoder may already be stopped - ignore
                }
                closeIfNeeded()
                return
            }

            if (frameSize <= 0) {
                Log.e(TAG, "🔴 [ERROR] YUV conversion returned invalid frameSize=$frameSize")
                codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                closeIfNeeded()
                return
            }

            // Validate frame size matches expected encoder input size
            val expectedSize = width * height * 3 / 2 // YUV420 planar or semi-planar
            if (kotlin.math.abs(frameSize - expectedSize) > expectedSize / 10) {
                Log.w(
                    TAG,
                    "⚠️ [WARNING] Frame size mismatch: got=$frameSize bytes, expected≈$expectedSize bytes (encoder: ${width}x${height})"
                )
            }

            // Calculate presentation timestamp - use monotonically increasing PTS
            // Calculate presentation timestamp - use monotonically increasing PTS
            // Important: PTS must be monotonically increasing for encoder to process frames
            // Normalize PTS to start from roughly 0 (relative time) to avoid Exynos overflow/precision issues
            val frameTimeUs = image.imageInfo.timestamp / 1000L
            val captureTimeMs = System.currentTimeMillis() // Record capture time for latency tracking
            val ptsUs = synchronized(this@VideoEncoder) {
                if (inputFrameCount == 0L) {
                    startPtsUs = frameTimeUs
                }
                // Ensure strictly increasing (add 1us if equal)
                val relPts = frameTimeUs - startPtsUs
                if (relPts <= lastEncodedNs) { // reusing lastEncodedNs as lastPtsUs placeholder
                    lastEncodedNs + 1
                } else {
                    relPts
                }
            }.also { synchronized(this@VideoEncoder) { lastEncodedNs = it } }
            
            // Store capture time for latency measurement (will be retrieved in drainLoop when frame is encoded)
            synchronized(this@VideoEncoder) {
                ptsToCaptureMs[ptsUs] = captureTimeMs
            }

            // For ByteBuffer input, keyframes are determined by the encoder based on KEY_I_FRAME_INTERVAL
            // The first frame should be a keyframe - request sync frame if this is the first input
            val inputFrameNum = synchronized(this@VideoEncoder) {
                inputFrameCount++
                inputFrameCount
            }

            // Don't use CODEC_CONFIG flag - that's for actual codec config data, not video frames
            // The encoder will generate keyframes based on KEY_I_FRAME_INTERVAL
            val flags = 0

            try {
                val queued = synchronized(codecLock) {
                    if (!isRunning || stopping || mediaCodec !== codec) {
                        false
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, frameSize, ptsUs, flags)
                        true
                    }
                }
                if (!queued) {
                    Log.w(TAG, "⚠️ [ENCODER] Dropped frame because encoder is stopping/reconfigured (inputFrame#$inputFrameNum)")
                    closeIfNeeded()
                    return
                }
                // Log only periodic success or first few
                if (inputFrameNum <= 5 || inputFrameNum % 60 == 0L) {
                    Log.d(TAG, "Queued buffer #$inputFrameNum: pts=$ptsUs us, size=$frameSize")
                }
            } catch (e: Exception) {
                Log.e(TAG, "🔴 [ERROR] Failed to queue input buffer #$inputFrameNum", e)
                closeIfNeeded()
                return
            }

            // Log first few frames for diagnostics
            val outputCount = synchronized(this@VideoEncoder) { encodedFrameCount }
            if (inputFrameNum <= 50) {
                Log.d(
                    TAG,
                    "🔵 [FRAME FLOW] Input frame #$inputFrameNum queued to encoder: size=$frameSize bytes, pts=$ptsUs us, success=true, outputFrames=$outputCount"
                )
            } else if (inputFrameNum == 60L || inputFrameNum == 120L || inputFrameNum == 180L) {
                // Periodic check to see if encoder is processing (every 60 frames after initial 50)
                val lag = inputFrameNum - outputCount
                if (lag > 10) {
                    Log.w(
                        TAG,
                        "⚠️ [FRAME FLOW] Encoder lag detected: $inputFrameNum input frames queued, but only $outputCount output frames produced (lag=$lag frames). Encoder may be falling behind or stuck."
                    )
                } else {
                    Log.d(
                        TAG,
                        "🔵 [FRAME FLOW] $inputFrameNum input frames queued, $outputCount output frames produced (lag=$lag) - encoder processing normally"
                    )
                }
            }

            // Request sync frame after first input to ensure keyframe generation
            if (inputFrameNum == 1L) {
                requestSyncFrame()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error encoding frame (ByteBuffer mode)", e)
        } finally {
            closeIfNeeded()
        }
    }

    @Suppress("unused")
    fun reconfigure(width: Int, height: Int, bitrate: Int) {
        Log.w(
            TAG, "Reconfiguring encoder - use adjustBitrate() for seamless bitrate changes"
        )
        // For resolution changes, encoder reset is still needed
        // For bitrate changes, use adjustBitrate() instead
        stop()
        // Note: Caller should create new VideoEncoder instance with new resolution
    }

    /**
     * Get the actual encoder width (may differ from requested if Buffer mode used lower resolution)
     */
    fun getWidth(): Int = width

    /**
     * Get the actual encoder height (may differ from requested if Buffer mode used lower resolution)
     */
    fun getHeight(): Int = height
}
