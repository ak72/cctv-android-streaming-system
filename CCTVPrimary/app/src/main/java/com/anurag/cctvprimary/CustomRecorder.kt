package com.anurag.cctvprimary

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Custom Recorder
 * 
 * Replaces CameraX Recorder with custom MediaCodec/MediaMuxer implementation.
 * - Video: H.264 encoding from ImageAnalysis frames (like VideoEncoder), or external (tee from VideoEncoder)
 * - Audio: AAC encoding from AudioSourceEngine PCM data
 * - Output: MP4 file via MediaMuxer
 * 
 * When [useExternalVideo] is true, no video codec is created; video is fed via [RecordingSink]
 * from the shared VideoEncoder (single graph: one encoder → stream + record). Avoids camera rebind.
 */
class CustomRecorder(
    private val width: Int,
    private val height: Int,
    private val videoBitrate: Int,
    private val frameRate: Int,
    private val audioSampleRate: Int = 48000,
    private val useExternalVideo: Boolean = false
) : AudioSourceEngine.AudioListener, RecordingSink {
    
    private val logFrom = "CustomRecorder"
    private val TIMEOUT_US = 10_000L
    
    // Video encoding
    private var videoCodec: MediaCodec? = null
    private var selectedInputColorFormat: Int = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
    
    // Audio encoding
    private var audioCodec: MediaCodec? = null
    private val audioInputQueue = ArrayDeque<ByteArray>()
    private val audioInputLock = Any()
    private var audioPtsUs: Long = 0L
    private var lastQueuedAudioInputPtsUs: Long = -1L
    private var audioFrameCount: Long = 0L

    // Recording audio gain (software AGC-like boost).
    // CRITICAL: Some devices deliver very low-gain PCM especially with UNPROCESSED source.
    // We boost in software with a limiter to improve perceived volume without clipping.
    private var recordGain: Float = 1.0f
    private val recordGainMax: Float = 8.0f
    private val recordGainSmoothing: Float = 0.10f
    private val recordTargetRms: Float = 8000f
    private var lastRecordGainLogMs: Long = 0L
    
    // Muxing
    private var mediaMuxer: MediaMuxer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false
    private val muxerLock = Any()
    
    // State
    @Volatile
    private var isRecording = false
    
    private var recordingThread: Thread? = null
    private val videoFormatReceived = AtomicBoolean(false)
    private val audioFormatReceived = AtomicBoolean(false)
    
    // Callbacks
    var onRecordingStarted: (() -> Unit)? = null
    var onRecordingStopped: ((error: Throwable?) -> Unit)? = null
    
    // Frame counting and timing
    private var encodedFrameCount = 0L  // Frames queued to encoder (input)
    private var videoOutputFrameCount = 0L  // Frames output from encoder (for logging)
    private var lastFovDbgUptimeMs: Long = 0L
    private var videoPtsUs: Long = 0L  // Deprecated timeline (kept for compatibility/logging)
    private var recordingStartTimeUs: Long = 0L  // Recording start time for PTS calculation
    // CRITICAL: Use real capture timestamps for video input PTS (prevents timeline compression and fast-forward playback)
    private var firstVideoInputTimestampUs: Long = -1L
    private var lastQueuedVideoPtsUs: Long = -1L
    // NOTE: We intentionally do NOT keep a per-frame PTS map here.
    // A MutableMap<Long, Long> would allocate/box keys & values at 30fps and can trigger GC pauses,
    // which shows up as occasional stutter (10/15fps gaps). We rely on codec output PTS and enforce
    // strict monotonicity at muxer write time instead.
    private var lastWrittenVideoPts: Long = -1L  // Last video PTS written to MediaMuxer (for monotonicity check)
    private var lastWrittenAudioPts: Long = -1L  // Last audio PTS written to MediaMuxer (for monotonicity check)
    private var firstVideoPts: Long = -1L  // First video PTS from codec (to establish baseline)
    private var firstAudioPts: Long = -1L  // First audio PTS from codec (to establish baseline)

    // Scratch buffer for YUV conversion (same as VideoEncoder)
    private val scratch = ByteArray(width * height * 3 / 2)
    private var loggedYuvLayout = false

    // Cache precomputed coordinate maps (same as VideoEncoder)
    private var cachedMapKey: String? = null
    private var cachedRxMap: IntArray? = null
    private var cachedRyMap: IntArray? = null
    private var cachedRcxMap: IntArray? = null
    private var cachedRcyMap: IntArray? = null

    // Fast-path conversion scratch buffers (avoid per-pixel ByteBuffer.get() when no rotation/scaling is needed)
    private val fastRowY: ByteArray = ByteArray(width)
    private val fastRowU: ByteArray = ByteArray((width / 2).coerceAtLeast(1))
    private val fastRowV: ByteArray = ByteArray((width / 2).coerceAtLeast(1))
    private val fastRowUV: ByteArray = ByteArray(width) // NV12: width bytes per chroma row

    // Diagnostics: track encodeFrame conversion/queue time + observed input FPS
    private var lastEncodeDiagMs: Long = 0L
    private var lastEncodeCamTsUs: Long = -1L

    // Video orientation handling
    // If non-zero (90/180/270), we encode frames in sensor orientation (no pixel rotation)
    // and rely on MP4 container orientation hint for playback rotation.
    // This avoids expensive Kotlin rotation and improves FPS/smoothness in ByteBuffer mode.
    private var muxerOrientationHintDegrees: Int = 0
    
    /**
     * Start recording using a FileDescriptor
     * @param fileDescriptor Open FileDescriptor to the output MP4 file (must be opened in "rw" mode)
     * @param withAudio Whether to include audio track
     * @return true if started successfully
     * 
     * CRITICAL: This method accepts a FileDescriptor instead of a File path to work correctly
     * with Android 10+ Scoped Storage. MediaMuxer(FileDescriptor, format) works with files
     * that already exist (created via MediaStore or SAF), whereas MediaMuxer(String path, format)
     * requires the file NOT to exist (uses O_CREAT | O_EXCL), causing EEXIST errors.
     */
    fun start(fileDescriptor: ParcelFileDescriptor, withAudio: Boolean, orientationHintDegrees: Int = 0): Boolean {
        muxerOrientationHintDegrees = orientationHintDegrees
        Log.d(
            logFrom,
            "🔴 [CUSTOM_RECORDER] start() called: fileDescriptor=${fileDescriptor.fd}, withAudio=$withAudio, isRecording=$isRecording, orientationHint=$muxerOrientationHintDegrees"
        )
        if (isRecording) {
            Log.w(logFrom, "🔴 [CUSTOM_RECORDER] start() FAILED - already recording")
            fileDescriptor.close()
            return false
        }
        
        this.fileDescriptor = fileDescriptor
        
        try {
            // Setup video codec (skip when using external video from shared VideoEncoder)
            if (!useExternalVideo) {
                Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Setting up video codec...")
                setupVideoCodec()
                Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Video codec setup completed")
            } else {
                Log.d(logFrom, "🔴 [CUSTOM_RECORDER] External video mode: skipping video codec (will receive CSD/frames from VideoEncoder)")
            }
            
            // Setup audio codec if needed
            if (withAudio) {
                Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Setting up audio codec...")
                setupAudioCodec()
                // Register as listener to AudioSourceEngine
                AudioSourceEngine.getInstance().registerRecordingListener(this)
                Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Audio codec setup completed")
            }
            
            // Setup MediaMuxer using FileDescriptor
            Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Setting up MediaMuxer with FileDescriptor: fd=${fileDescriptor.fd}")
            setupMediaMuxer(fileDescriptor)
            Log.d(logFrom, "🔴 [CUSTOM_RECORDER] MediaMuxer setup completed")
            
            // Reset state
            videoFormatReceived.set(false)
            audioFormatReceived.set(false)
            encodedFrameCount = 0L
            videoOutputFrameCount = 0L
            audioFrameCount = 0L
            videoPtsUs = 0L  // Reset video PTS for new recording
            audioPtsUs = 0L
            lastQueuedAudioInputPtsUs = -1L
            recordingStartTimeUs = System.currentTimeMillis() * 1000L  // Recording start time in microseconds
            lastWrittenVideoPts = -1L  // Reset last written video PTS
            lastWrittenAudioPts = -1L  // Reset last written audio PTS
            firstVideoPts = -1L  // Reset first video PTS baseline
            firstAudioPts = -1L  // Reset first audio PTS baseline
            firstVideoInputTimestampUs = -1L
            lastQueuedVideoPtsUs = -1L
            synchronized(audioInputLock) {
                audioInputQueue.clear()
            }
            
            // Start recording thread
            Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Starting recording thread...")
            isRecording = true
            recordingThread = Thread({ recordingLoop() }, "CustomRecorder-Thread").apply {
                isDaemon = true
                start()
            }
            
            Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Recording thread started - isRecording=$isRecording, threadAlive=${recordingThread?.isAlive}")
            Log.d(logFrom, "Recording started with FileDescriptor: fd=${fileDescriptor.fd}, withAudio=$withAudio")
            
            // CRITICAL: onRecordingStarted callback must be invoked to update UI and broadcast state
            Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Invoking onRecordingStarted callback...")
            onRecordingStarted?.invoke()
            Log.d(logFrom, "🔴 [CUSTOM_RECORDER] onRecordingStarted callback completed")
            return true
        } catch (e: Exception) {
            Log.e(logFrom, "🔴 [CUSTOM_RECORDER] start() FAILED with exception", e)
            Log.e(logFrom, "🔴 [CUSTOM_RECORDER] Exception type: ${e.javaClass.simpleName}, message: ${e.message}")
            cleanup()
            return false
        }
    }
    
    /**
     * Stop recording
     * 
     * CRITICAL: This method must properly drain all buffers before stopping MediaMuxer.
     * The sequence is:
     * 1. Set isRecording = false (stops accepting new frames)
     * 2. Signal EOS to codecs (tells encoders to finish current work)
     * 3. Wait for recording thread to fully drain all buffers (codecs will be null when done)
     * 4. Stop MediaMuxer (flushes all written data to file)
     * 5. Release codecs and MediaMuxer
     * 6. Close FileDescriptor
     * 
     * If MediaMuxer is stopped/released before buffers are drained, the file will be 0 bytes or corrupted.
     */
    fun stop() {
        if (!isRecording) {
            Log.d(logFrom, "🔴 [CUSTOM_RECORDER] stop() called but not recording - ignoring")
            return
        }
        
        Log.d(logFrom, "🔴 [CUSTOM_RECORDER] stop() called - starting stop sequence")
        isRecording = false
        
        // Unregister from AudioSourceEngine
        try {
            AudioSourceEngine.getInstance().unregisterRecordingListener(this)
            Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Unregistered from AudioSourceEngine")
        } catch (e: Exception) {
            Log.w(logFrom, "🔴 [CUSTOM_RECORDER] Error unregistering from AudioSourceEngine", e)
        }
        
        // CRITICAL FIX: Signal end of input to codecs using BUFFER_FLAG_END_OF_STREAM
        // signalEndOfInputStream() only works for Surface-based input, but we use ByteBuffer input
        // For ByteBuffer input, we must queue an empty buffer with BUFFER_FLAG_END_OF_STREAM
        
        // Signal EOS to video codec (ByteBuffer input); skip when using external video (no codec)
        // CRITICAL: Codec must be in executing state to accept input buffers
        // If codec is already stopped/released, skip EOS signaling
        try {
            val codec = if (useExternalVideo) null else videoCodec
            if (codec != null) {
                try {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val eosPtsUs = (lastQueuedVideoPtsUs.takeIf { it >= 0L } ?: 0L) + 1L
                        codec.queueInputBuffer(
                            inputIndex,
                            0, // offset
                            0, // size (0 for empty buffer EOS)
                            eosPtsUs, // presentationTimeUs (must be monotonic on some encoders)
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        Log.d(logFrom, "🔴 [CUSTOM_RECORDER] EOS signaled to video codec via BUFFER_FLAG_END_OF_STREAM")
                    } else {
                        Log.w(logFrom, "🔴 [CUSTOM_RECORDER] WARNING: Could not get input buffer for video EOS (inputIndex=$inputIndex)")
                    }
                } catch (e: IllegalStateException) {
                    // Codec is already stopped/released - this is OK if recording was already stopped
                    Log.w(logFrom, "🔴 [CUSTOM_RECORDER] Video codec not in accepting state for EOS (already stopped?) - skipping", e)
                }
            }
        } catch (e: Exception) {
            Log.e(logFrom, "🔴 [CUSTOM_RECORDER] Error signaling EOS to video codec", e)
        }
        
        // Signal EOS to audio codec (ByteBuffer input)
        try {
            val codec = audioCodec
            if (codec != null) {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    // Some audio encoders (e.g. C2SoftAacEnc) will complain if EOS PTS goes backwards to 0.
                    // Keep EOS PTS monotonic relative to last queued audio input PTS.
                    val eosPtsUs = ((lastQueuedAudioInputPtsUs.takeIf { it >= 0L } ?: audioPtsUs).coerceAtLeast(0L)) + 1L
                    codec.queueInputBuffer(
                        inputIndex,
                        0, // offset
                        0, // size (0 for empty buffer EOS)
                        eosPtsUs, // presentationTimeUs (keep monotonic)
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    Log.d(logFrom, "🔴 [CUSTOM_RECORDER] EOS signaled to audio codec via BUFFER_FLAG_END_OF_STREAM")
                } else {
                    Log.w(logFrom, "🔴 [CUSTOM_RECORDER] WARNING: Could not get input buffer for audio EOS (inputIndex=$inputIndex)")
                }
            }
        } catch (e: Exception) {
            Log.e(logFrom, "🔴 [CUSTOM_RECORDER] Error signaling EOS to audio codec", e)
        }
        
        // CRITICAL: Wait for recording thread to fully drain all buffers
        // The thread will exit when both codecs are null (EOS processed)
        try {
            val thread = recordingThread
            if (thread != null) {
                Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Waiting for recording thread to finish draining buffers...")
                thread.join(5000) // Wait up to 5 seconds for thread to finish
                val threadFinished = !thread.isAlive
                if (!threadFinished) {
                    Log.e(logFrom, "🔴 [CUSTOM_RECORDER] WARNING: Recording thread did not finish within timeout - buffers may not be fully drained!")
                    // CRITICAL FIX: Set codecs to null FIRST, then stop/release them
                    // This ensures the loop sees null codecs and exits immediately
                    // If we stop first, there's a race where loop uses stopped codec → IllegalStateException
                    Log.w(logFrom, "🔴 [CUSTOM_RECORDER] Thread still running - nulling codecs first to exit loop, then stopping them")
                    
                    // Save references before nulling (so we can still stop/release them)
                    val videoCodecRef = videoCodec
                    val audioCodecRef = audioCodec
                    
                    // Null codecs FIRST - this makes the loop exit on next iteration check
                    videoCodec = null
                    audioCodec = null
                    
                    // Now stop/release using saved references
                    try {
                        videoCodecRef?.stop()
                        videoCodecRef?.release()
                    } catch (e: Exception) {
                        Log.w(logFrom, "Error force-stopping video codec", e)
                    }
                    
                    try {
                        audioCodecRef?.stop()
                        audioCodecRef?.release()
                    } catch (e: Exception) {
                        Log.w(logFrom, "Error force-stopping audio codec", e)
                    }
                    
                    // Wait a bit for loop to detect null codecs and exit
                    Thread.sleep(200)
                    
                    // Check again if thread finished
                    val stillAlive = thread.isAlive
                    if (stillAlive) {
                        Log.e(logFrom, "🔴 [CUSTOM_RECORDER] CRITICAL: Thread STILL running after force-stop - cannot proceed safely with cleanup")
                        Log.e(logFrom, "🔴 [CUSTOM_RECORDER] This indicates a serious issue - loop is stuck or codecs not properly nulled")
                        // We'll still proceed but log the error - the loop should exit soon
                    } else {
                        Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Thread exited after force-stop")
                    }
                } else {
                    Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Recording thread finished - all buffers drained")
                }
            } else {
                Log.w(logFrom, "🔴 [CUSTOM_RECORDER] WARNING: recordingThread is null")
            }
        } catch (e: InterruptedException) {
            Log.w(logFrom, "🔴 [CUSTOM_RECORDER] Interrupted while waiting for recording thread", e)
            Thread.currentThread().interrupt()
        }
        
        // CRITICAL: Ensure thread has actually finished before cleanup
        // cleanup() must NOT run while recordingLoop() is still accessing codecs
        val thread = recordingThread
        val threadStillRunning = thread != null && thread.isAlive
        if (threadStillRunning) {
            Log.e(logFrom, "🔴 [CUSTOM_RECORDER] CRITICAL: Thread still running - waiting additional time before cleanup")
            Log.e(logFrom, "🔴 [CUSTOM_RECORDER] Attempting final wait...")
            try {
                thread.join(1000) // Wait up to 1 more second
            } catch (e: InterruptedException) {
                Log.w(logFrom, "Interrupted during final wait", e)
            }
            
            if (thread.isAlive) {
                Log.e(logFrom, "🔴 [CUSTOM_RECORDER] CRITICAL: Thread STILL alive after final wait - cleanup() may cause IllegalStateException")
                Log.e(logFrom, "🔴 [CUSTOM_RECORDER] Codecs should already be null - proceeding with cleanup anyway")
            }
        }
        
        // Finalize and cleanup (in correct order)
        Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Finalizing recording (stopping MediaMuxer)...")
        finalizeRecording()
        
        Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Cleaning up resources (releasing codecs, MediaMuxer, closing FileDescriptor)...")
        cleanup()
        
        Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Stop sequence completed - invoking onRecordingStopped callback")
        onRecordingStopped?.invoke(null)
        Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Recording stopped successfully")
    }
    
    // ---------- RecordingSink (external video from VideoEncoder) ----------
    
    override fun onCsd(sps: ByteArray, pps: ByteArray) {
        if (!useExternalVideo) return
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(pps))
        }
        synchronized(muxerLock) {
            if (videoTrackIndex == -1 && mediaMuxer != null) {
                videoTrackIndex = mediaMuxer!!.addTrack(format)
                videoFormatReceived.set(true)
                Log.d(logFrom, "🔴 [CUSTOM_RECORDER] External video: added track from CSD, trackIndex=$videoTrackIndex")
                startMuxerIfReady()
            }
        }
    }
    
    override fun onEncodedFrame(frame: EncodedFrame) {
        if (!useExternalVideo) return
        if (frame.data.isEmpty()) return
        synchronized(muxerLock) {
            if (!muxerStarted || videoTrackIndex < 0) return
            val codecOutputPts = frame.presentationTimeUs
            if (firstVideoPts < 0) {
                firstVideoPts = codecOutputPts
            }
            val normalizedPts = (codecOutputPts - firstVideoPts).coerceAtLeast(0L)
            val minStepUs = 1L
            val finalPts = if (normalizedPts > lastWrittenVideoPts) normalizedPts else lastWrittenVideoPts + minStepUs
            lastWrittenVideoPts = finalPts
            val bufferInfo = MediaCodec.BufferInfo().apply {
                offset = 0
                size = frame.data.size
                presentationTimeUs = finalPts
                flags = if (frame.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            }
            try {
                val buffer = ByteBuffer.wrap(frame.data)
                mediaMuxer?.writeSampleData(videoTrackIndex, buffer, bufferInfo)
                videoOutputFrameCount++
            } catch (e: Exception) {
                Log.e(logFrom, "🔴 [CUSTOM_RECORDER] External video write failed", e)
                if (e is IllegalStateException) muxerStarted = false
            }
        }
    }
    
    /**
     * Encode a video frame (called from ImageAnalysis)
     */
    fun encodeFrame(image: ImageProxy, closeImage: Boolean = true) {
        fun closeIfNeeded() {
            if (closeImage) {
                try {
                    image.close()
                } catch (_: Throwable) {
                }
            }
        }
        if (!isRecording) {
            closeIfNeeded()
            return
        }
        if (useExternalVideo) {
            // Video comes from VideoEncoder via RecordingSink; ignore ImageProxy frames
            closeIfNeeded()
            return
        }
        if (encodedFrameCount % 30 == 0L) {
             Log.d(logFrom, "encodeFrame called (count=$encodedFrameCount)")
        }
        
        val codec = videoCodec ?: run {
            closeIfNeeded()
            return
        }
        
        try {
            // If muxer orientation hint is set, avoid pixel rotation entirely for performance.
            // The player will rotate video using container metadata.
            val rotMeta = image.imageInfo.rotationDegrees
            val rotToApply = if (muxerOrientationHintDegrees == 90 || muxerOrientationHintDegrees == 180 || muxerOrientationHintDegrees == 270) {
                0
            } else {
                normalizeRotation(rotMeta)
            }

            // FOV diagnostics (record path):
            // Captures what ImageAnalysis is delivering to the recorder, including cropRect.
            // If recording shows correct FOV but stream looks zoomed, compare this with VideoEncoder's [FOVDBG][STREAM_ENC].
            try {
                val nowUptime = android.os.SystemClock.uptimeMillis()
                if (nowUptime - lastFovDbgUptimeMs >= 10_000L) {
                    lastFovDbgUptimeMs = nowUptime
                    val cr = image.cropRect
                    val fullCrop =
                        (cr.left == 0 && cr.top == 0 && cr.right == image.width && cr.bottom == image.height)
                    Log.d(
                        logFrom,
                        "🟣 [FOVDBG][RECORD_ENC] src=${image.width}x${image.height} crop=${cr.left},${cr.top},${cr.right},${cr.bottom} fullCrop=$fullCrop rotMeta=$rotMeta rotApplied=$rotToApply dst=${width}x${height} hint=$muxerOrientationHintDegrees"
                    )
                }
            } catch (_: Throwable) {
            }
            
            // FPS throttling is currently disabled.
            // TODO: If frame rate limiting is needed, implement proper throttling with a start time reference:
            //   - Track recording start time (e.g., `val startTimeNs = System.nanoTime()` when recording begins)
            //   - Calculate expected frame time: `val expectedFrameNs = startTimeNs + (encodedFrameCount * minIntervalNs)`
            //   - Skip frame if `nowNs < expectedFrameNs`
            // Note: CameraX/ImageAnalysis may already throttle at the source, so this may not be necessary.

            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex < 0) {
                if (encodedFrameCount % 30 == 0L) Log.d(logFrom, "Video encoder busy (inputIndex=$inputIndex)")
                closeIfNeeded()
                return
            }
            
            val inputBuffer = codec.getInputBuffer(inputIndex) ?: run {
                Log.e(logFrom, "Video encoder input buffer null")
                closeIfNeeded()
                return
            }
            
            inputBuffer.clear()
            val tConvStartNs = System.nanoTime()
            @Suppress("DEPRECATION")
            val frameSize = when (selectedInputColorFormat) {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> {
                    // PERFORMANCE: If no rotation and exact fit, use a fast row-copy path.
                    // This is critical for smooth playback (higher effective FPS) in ByteBuffer mode.
                    if (rotToApply == 0 && image.width == width && image.height == height) {
                        fastYuv420ToNV12NoRotateExactFit(image, inputBuffer)
                    } else {
                        yuv420ToNV12Rotated(image, inputBuffer, rotToApply)
                    }
                }
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> {
                    if (rotToApply == 0 && image.width == width && image.height == height) {
                        fastYuv420ToI420NoRotateExactFit(image, inputBuffer)
                    } else {
                        yuv420ToI420RotatedToI420(image, inputBuffer, rotToApply)
                    }
                }
                else -> {
                    if (rotToApply == 0 && image.width == width && image.height == height) {
                        fastYuv420ToI420NoRotateExactFit(image, inputBuffer)
                    } else {
                        yuv420ToI420RotatedToI420(image, inputBuffer, rotToApply)
                    }
                }
            }
            val tConvEndNs = System.nanoTime()
            
            // CRITICAL FIX: Use CameraX image timestamp for video input PTS (real-time)
            // ImageProxy.imageInfo.timestamp is monotonic nanoseconds since boot.
            // Using frame-count based PTS compresses timeline when frames are dropped or delayed,
            // causing fast-forward video and muxer instability.
            val imageTimestampUs = image.imageInfo.timestamp / 1000L
            if (firstVideoInputTimestampUs < 0) {
                firstVideoInputTimestampUs = imageTimestampUs
                Log.d(logFrom, "🔴 [CUSTOM_RECORDER] First video INPUT timestampUs from CameraX: $firstVideoInputTimestampUs")
            }

            var ptsUs = (imageTimestampUs - firstVideoInputTimestampUs).coerceAtLeast(0L)
            // MediaMuxer requires strictly increasing timestamps per track; enforce monotonicity at input too.
            if (ptsUs <= lastQueuedVideoPtsUs) {
                ptsUs = lastQueuedVideoPtsUs + 1L
            }
            lastQueuedVideoPtsUs = ptsUs
            
            // Log PTS for first few frames to diagnose timing issues
            if (encodedFrameCount < 5 || encodedFrameCount % 30 == 0L) {
                Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Queueing video frame: frameCount=$encodedFrameCount, inputPts=$ptsUs, camTsUs=$imageTimestampUs")
            }

            // PERFORMANCE DIAGNOSTICS: track conversion cost + observed input cadence.
            // If effective FPS is low, playback will be choppy even if muxing is correct.
            try {
                val nowMs = android.os.SystemClock.uptimeMillis()
                if (nowMs - lastEncodeDiagMs >= 2000L) {
                    val deltaUs = if (lastEncodeCamTsUs >= 0) (imageTimestampUs - lastEncodeCamTsUs) else -1L
                    val approxFps = if (deltaUs > 0) (1_000_000.0 / deltaUs.toDouble()) else -1.0
                    val convMs = (tConvEndNs - tConvStartNs).toDouble() / 1_000_000.0
                    Log.d(
                        logFrom,
                        "🔴 [CUSTOM_RECORDER] Video perf: src=${image.width}x${image.height} rotMeta=$rotMeta rotApplied=$rotToApply hint=$muxerOrientationHintDegrees " +
                            "fmt=$selectedInputColorFormat convMs=${"%.2f".format(convMs)} " +
                            "deltaUs=$deltaUs approxFps=${"%.1f".format(approxFps)}"
                    )
                    lastEncodeDiagMs = nowMs
                }
                lastEncodeCamTsUs = imageTimestampUs
            } catch (t: Throwable) {
                Log.w(logFrom, "🔴 [CUSTOM_RECORDER] Video perf diagnostics failed (non-fatal)", t)
            }
            
            codec.queueInputBuffer(inputIndex, 0, frameSize, ptsUs, 0)
            encodedFrameCount++
        } catch (e: Exception) {
            Log.e(logFrom, "Error encoding frame", e)
        } finally {
            closeIfNeeded()
        }
    }
    
    /**
     * AudioListener implementation - receive PCM data from AudioSourceEngine
     */
    override fun onAudioData(pcm: ByteArray, sampleRate: Int, channels: Int) {
        if (!isRecording || audioCodec == null) return

        // CRITICAL: Apply recording-only gain/limiter to improve low-volume mic capture.
        // Fallback: if anything unexpected happens, enqueue raw PCM to avoid breaking recording.
        val processed: ByteArray = try {
            if (channels != 1 || sampleRate != audioSampleRate) {
                // Unexpected format; don't modify.
                pcm
            } else {
                applyRecordingGainAndLimiter(pcm)
            }
        } catch (t: Throwable) {
            Log.w(logFrom, "🔴 [CUSTOM_RECORDER] Audio gain processing failed - using raw PCM", t)
            pcm
        }

        // Queue PCM data for encoding
        synchronized(audioInputLock) {
            audioInputQueue.offer(processed.copyOf())
        }
    }

    /**
     * Apply software gain with limiter to 16-bit little-endian mono PCM.
     *
     * - Computes RMS/peak for the chunk
     * - Chooses a target gain toward recordTargetRms (smoothed to avoid pumping)
     * - Limits gain to avoid clipping
     * - Applies gain per sample with clamp to [-32768, 32767]
     */
    private fun applyRecordingGainAndLimiter(input: ByteArray): ByteArray {
        if (input.isEmpty()) return input

        // Compute RMS + peak
        var sumSq = 0.0
        var peak = 0
        var samples = 0
        var i = 0
        while (i + 1 < input.size) {
            val lo = input[i].toInt() and 0xFF
            val hi = input[i + 1].toInt()
            val s = (hi shl 8) or lo
            val v = s.toShort().toInt()
            val av = kotlin.math.abs(v)
            if (av > peak) peak = av
            sumSq += (v * v).toDouble()
            samples++
            i += 2
        }
        if (samples == 0) return input

        val rms = kotlin.math.sqrt(sumSq / samples.toDouble()).toFloat()
        if (rms <= 1f) return input

        // Desired gain toward target RMS
        var desiredGain = (recordTargetRms / rms).coerceIn(1.0f, recordGainMax)

        // Prevent clipping (based on peak)
        if (peak > 0) {
            val maxAllowed = 32767f / peak.toFloat()
            if (desiredGain > maxAllowed) desiredGain = maxAllowed.coerceAtLeast(1.0f)
        }

        // Smooth gain to avoid pumping artifacts
        recordGain = (recordGain * (1.0f - recordGainSmoothing)) + (desiredGain * recordGainSmoothing)

        // Log occasionally for diagnostics
        val nowMs = android.os.SystemClock.uptimeMillis()
        if (nowMs - lastRecordGainLogMs > 2000L) {
            lastRecordGainLogMs = nowMs
            Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Audio gain: rms=$rms, peak=$peak, desiredGain=$desiredGain, appliedGain=$recordGain")
        }

        val out = ByteArray(input.size)
        i = 0
        while (i + 1 < input.size) {
            val lo = input[i].toInt() and 0xFF
            val hi = input[i + 1].toInt()
            val s = (hi shl 8) or lo
            val v = s.toShort().toInt()
            val scaled = (v.toFloat() * recordGain).toInt().coerceIn(-32768, 32767)
            out[i] = (scaled and 0xFF).toByte()
            out[i + 1] = ((scaled shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }
    
    /**
     * Setup video codec
     */
    private fun setupVideoCodec() {
        selectedInputColorFormat = selectInputColorFormat()
        
        // CRITICAL FIX: Use I-frame interval of 1 second (30 frames at 30 FPS) for recording
        // This ensures keyframes are generated frequently for better seeking and playback
        // Lower I-frame interval (1 second) helps with frame output consistency
        val iFrameInterval = 1  // 1 second = 30 frames at 30 FPS
        val format = MediaCodecConfig.createVideoFormat(
            width, height, videoBitrate, frameRate, iFrameInterval
        ).apply {
            // CustomRecorder uses ByteBuffer input (ImageAnalysis), so we need color format
            setInteger(MediaFormat.KEY_COLOR_FORMAT, selectedInputColorFormat)
            
            // NOTE: KEY_LOW_LATENCY is for decoders, not encoders
            // Encoders naturally buffer frames for encoding efficiency - this is expected behavior
            // The PTS correction we apply ensures correct playback timing despite frame batching
        }
        
        Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Video codec format: ${width}x${height}, bitrate=$videoBitrate, fps=$frameRate, iFrameInterval=$iFrameInterval")
        
        videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        
        Log.d(logFrom, "Video codec configured: ${width}x${height}, bitrate=$videoBitrate, fps=$frameRate")
    }
    
    /**
     * Setup audio codec
     */
    private fun setupAudioCodec() {
        // CRITICAL FIX: Increase audio bitrate for better quality and volume
        // 128 kbps is low for recording - increase to 192 kbps for better clarity
        // Also ensure proper audio encoding profile for better quality
        val audioBitrate = 192000 // 192 kbps for better quality (was 128000)
        val format = MediaCodecConfig.createAudioFormat(
            audioSampleRate, 1, audioBitrate
        )
        
        // CRITICAL: Ensure audio encoding profile is set for better quality
        // Profile 2 (AAC-LC) is standard, but we can try to use HE-AAC if supported
        // For now, stick with AAC-LC (profile 2) which is widely supported
        
        audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        
        Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Audio codec configured: sampleRate=$audioSampleRate, bitrate=$audioBitrate (improved quality)")
    }
    
    /**
     * Setup MediaMuxer using FileDescriptor
     * 
     * CRITICAL FIX: Use MediaMuxer(FileDescriptor, format) constructor instead of 
     * MediaMuxer(String path, format) to avoid EEXIST errors with Scoped Storage.
     * 
     * The path-based constructor uses O_CREAT | O_EXCL flags, which fail if the file
     * already exists. The FileDescriptor constructor works with pre-existing files
     * created via MediaStore or SAF, which is the standard approach for Android 10+.
     * 
     * @param fileDescriptor Open FileDescriptor to the output file (must be opened in "rw" mode)
     */
    private fun setupMediaMuxer(fileDescriptor: ParcelFileDescriptor) {
        try {
            // CRITICAL FIX: Use FileDescriptor constructor (API 26+) instead of path constructor
            // This works with files that already exist (created via MediaStore/SAF)
            // Note: minSdk is 26, so API 26+ is guaranteed
            mediaMuxer = MediaMuxer(fileDescriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            // PERFORMANCE/SMOOTHNESS: Prefer MP4 orientation hint over rotating pixels in Kotlin.
            // Must be set before muxer.start().
            val hint = muxerOrientationHintDegrees
            if (hint == 90 || hint == 180 || hint == 270) {
                try {
                    mediaMuxer?.setOrientationHint(hint)
                    Log.d(logFrom, "🔴 [CUSTOM_RECORDER] MediaMuxer orientation hint set: $hint")
                } catch (t: Throwable) {
                    Log.w(logFrom, "🔴 [CUSTOM_RECORDER] Failed to set MediaMuxer orientation hint=$hint (non-fatal)", t)
                }
            } else {
                Log.d(logFrom, "🔴 [CUSTOM_RECORDER] MediaMuxer orientation hint not set (hint=$hint)")
            }
            Log.d(logFrom, "🔴 [CUSTOM_RECORDER] MediaMuxer created successfully using FileDescriptor: fd=${fileDescriptor.fd}")
        } catch (e: Exception) {
            Log.e(logFrom, "🔴 [CUSTOM_RECORDER] setupMediaMuxer FAILED", e)
            // Close file descriptor on failure
            try {
                fileDescriptor.close()
            } catch (closeEx: Exception) {
                Log.w(logFrom, "Error closing FileDescriptor after MediaMuxer setup failure", closeEx)
            }
            throw e
        }
    }
    
    /**
     * Main recording loop - processes video and audio frames, muxes to file
     */
    private fun recordingLoop() {
        // THREAD_PRIORITY_VIDEO requires API 28+, use fallback for API 26-27
        val priority = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            android.os.Process.THREAD_PRIORITY_VIDEO
        } else {
            // Fallback to default priority for API 26-27
            android.os.Process.THREAD_PRIORITY_DEFAULT
        }
        android.os.Process.setThreadPriority(priority)
        
        val bufferInfo = MediaCodec.BufferInfo()
        
        Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Recording loop started: isRecording=$isRecording, videoCodec=${videoCodec != null}, audioCodec=${audioCodec != null}")
        
        while (isRecording || videoCodec != null || audioCodec != null) {
            try {
                // CRITICAL FIX: Early exit check to prevent deadlock
                // If stop() was called (isRecording=false) and codecs are already null (from cleanup),
                // exit immediately to avoid deadlock where join() waits for loop, but loop waits for codecs
                if (!isRecording && videoCodec == null && audioCodec == null) {
                    Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Early exit: isRecording=false and both codecs are null")
                    break
                }
                
                // Process video frames
                processVideoFrames(bufferInfo)
                
                // Process audio frames
                if (audioCodec != null) {
                    processAudioFrames(bufferInfo)
                }
                
                // Small delay to prevent busy-waiting
                Thread.sleep(5)
            } catch (e: Exception) {
                Log.e(logFrom, "🔴 [CUSTOM_RECORDER] Error in recording loop", e)
                Log.e(logFrom, "🔴 [CUSTOM_RECORDER] Error details: ${e.javaClass.simpleName}: ${e.message}", e)
                // Continue processing to drain buffers even on error
                // However, if codecs are null due to concurrent cleanup, the loop will exit on next iteration
            }
        }
        
        Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Recording loop exited: isRecording=$isRecording, videoCodec=${videoCodec != null}, audioCodec=${audioCodec != null}")
    }
    
    /**
     * Process video frames from encoder
     * 
     * CRITICAL: Must handle concurrent cleanup() that may stop/release codec while processing
     */
    private fun processVideoFrames(bufferInfo: MediaCodec.BufferInfo) {
        // Get codec reference - check if still valid
        val codec = videoCodec ?: return
        
        try {
            while (true) {
                // CRITICAL: Re-check codec on each iteration - must be done BEFORE any codec operations
                // If we null videoCodec while loop is running, this check will catch it immediately
                val currentCodec = videoCodec
                if (currentCodec == null || currentCodec !== codec) {
                    // Codec was nulled/replaced by cleanup() or stop() - exit immediately
                    break
                }
                
                // CRITICAL: Wrap ALL codec operations in try-catch for IllegalStateException
                // When stop() force-releases the codec, the next call to dequeueOutputBuffer will throw
                // Catching it allows the thread to exit gracefully instead of crashing
                val outputIndex: Int = try {
                    currentCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                } catch (_: IllegalStateException) {
                    // Codec was stopped/released while we were using it - exit gracefully
                    Log.w(logFrom, "🔴 [CUSTOM_RECORDER] Video codec illegal state (likely released) - exiting processVideoFrames")
                    return // Exit the function, which breaks the loop
                }
                
                when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = currentCodec.outputFormat
                    // CRITICAL: Log format dimensions to diagnose thumbnail/small video issue
                    val formatWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                    val formatHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
                    
                    // Get crop values with API level compatibility:
                    // - KEY_CROP_* constants require API 33+
                    // - getInteger(key, defaultValue) requires API 29+
                    // For API 33+: Use getInteger(KEY_CROP_*, defaultValue) - both available
                    // For API 29-32: getInteger(key, defaultValue) works, but KEY_CROP_* constants don't exist - default to full frame
                    // For API 26-28: getInteger(key, defaultValue) doesn't exist - default to full frame
                    val cropLeft: Int
                    val cropTop: Int
                    val cropRight: Int
                    val cropBottom: Int
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        // API 33+: KEY_CROP_* constants and getInteger(key, defaultValue) are both available
                        @Suppress("UnsafeOptInUsageError")
                        cropLeft = format.getInteger(MediaFormat.KEY_CROP_LEFT, 0)
                        @Suppress("UnsafeOptInUsageError")
                        cropTop = format.getInteger(MediaFormat.KEY_CROP_TOP, 0)
                        @Suppress("UnsafeOptInUsageError")
                        cropRight = format.getInteger(MediaFormat.KEY_CROP_RIGHT, formatWidth - 1)
                        @Suppress("UnsafeOptInUsageError")
                        cropBottom = format.getInteger(MediaFormat.KEY_CROP_BOTTOM, formatHeight - 1)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // API 29-32: getInteger(key, defaultValue) is available, but KEY_CROP_* constants don't exist
                        // Use string literals to avoid compile-time constant errors, getInteger will return defaultValue
                        cropLeft = try {
                            format.getInteger("crop-left", 0)
                        } catch (_: Exception) {
                            0
                        }
                        cropTop = try {
                            format.getInteger("crop-top", 0)
                        } catch (_: Exception) {
                            0
                        }
                        cropRight = try {
                            format.getInteger("crop-right", formatWidth - 1)
                        } catch (_: Exception) {
                            formatWidth - 1
                        }
                        cropBottom = try {
                            format.getInteger("crop-bottom", formatHeight - 1)
                        } catch (_: Exception) {
                            formatHeight - 1
                        }
                    } else {
                        // API 26-28: getInteger(key, defaultValue) doesn't exist, default to full frame
                        // Crop info not available on these API levels anyway
                        cropLeft = 0
                        cropTop = 0
                        cropRight = formatWidth - 1
                        cropBottom = formatHeight - 1
                    }
                    
                    // CRITICAL FIX: Normalize format to ensure correct dimensions for MediaMuxer
                    // The codec output format might have crop values that make video appear small
                    // We must ensure width/height in the format match our actual recording dimensions
                    // MediaMuxer uses KEY_WIDTH/KEY_HEIGHT from the format, not crop values
                    val effectiveWidth = cropRight - cropLeft + 1
                    val effectiveHeight = cropBottom - cropTop + 1
                    Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Video format received: formatWidth=$formatWidth, formatHeight=$formatHeight")
                    Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Video crop: left=$cropLeft, top=$cropTop, right=$cropRight, bottom=$cropBottom")
                    Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Effective dimensions: effectiveWidth=$effectiveWidth, effectiveHeight=$effectiveHeight")
                    Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Expected dimensions: width=$width, height=$height")
                    Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Video format: $format")
                    
                    // CRITICAL: Create normalized format with correct dimensions
                    // Use our recording width/height (not format's width/height which might be wrong)
                    // This ensures MediaMuxer uses the correct dimensions
                    // Note: MediaFormat.keySet()/getValue() require API 29+, so we manually copy essential keys
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_VIDEO_AVC
                    val normalizedFormat = MediaFormat.createVideoFormat(
                        mime,
                        width,  // Use our recording width, not format's width
                        height  // Use our recording height, not format's height
                    ).apply {
                        // Copy essential format keys (manually for API 26+ compatibility)
                        try {
                            if (format.containsKey(MediaFormat.KEY_COLOR_FORMAT)) {
                                setInteger(MediaFormat.KEY_COLOR_FORMAT, format.getInteger(MediaFormat.KEY_COLOR_FORMAT))
                            }
                        } catch (_: Exception) {}
                        
                        try {
                            if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                                setInteger(MediaFormat.KEY_BIT_RATE, format.getInteger(MediaFormat.KEY_BIT_RATE))
                            }
                        } catch (_: Exception) {}
                        
                        // CRITICAL: Ensure frame rate is set correctly in normalized format
                        // MediaMuxer uses this to calculate duration and playback timing
                        setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                        
                        try {
                            if (format.containsKey(MediaFormat.KEY_I_FRAME_INTERVAL)) {
                                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, format.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL))
                            }
                        } catch (_: Exception) {}
                        
                        // Override width/height with our recording dimensions (CRITICAL for correct video size)
                        setInteger(MediaFormat.KEY_WIDTH, width)
                        setInteger(MediaFormat.KEY_HEIGHT, height)

                        // CRITICAL FIX: Copy codec specific data (SPS/PPS) into the normalized format.
                        // MPEG4Writer will fail with "Missing codec specific data" (and MediaMuxer.stop err:-1007)
                        // if csd-0/csd-1 are missing from the track format.
                        try {
                            if (format.containsKey("csd-0")) {
                                val csd0 = format.getByteBuffer("csd-0")
                                if (csd0 != null) {
                                    val dup = csd0.duplicate()
                                    dup.position(0)
                                    setByteBuffer("csd-0", dup)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(logFrom, "🔴 [CUSTOM_RECORDER] Failed to copy video csd-0 into normalized format", e)
                        }
                        try {
                            if (format.containsKey("csd-1")) {
                                val csd1 = format.getByteBuffer("csd-1")
                                if (csd1 != null) {
                                    val dup = csd1.duplicate()
                                    dup.position(0)
                                    setByteBuffer("csd-1", dup)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(logFrom, "🔴 [CUSTOM_RECORDER] Failed to copy video csd-1 into normalized format", e)
                        }
                        
                        // CRITICAL FIX: Reset crop keys to full frame to prevent small/thumbnail video
                        // Crop keys can cause MediaMuxer/players to display video at reduced size
                        // Set crop to full frame: left=0, top=0, right=width-1, bottom=height-1
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            // API 33+: Use KEY_CROP_* constants
                            @Suppress("UnsafeOptInUsageError")
                            setInteger(MediaFormat.KEY_CROP_LEFT, 0)
                            @Suppress("UnsafeOptInUsageError")
                            setInteger(MediaFormat.KEY_CROP_TOP, 0)
                            @Suppress("UnsafeOptInUsageError")
                            setInteger(MediaFormat.KEY_CROP_RIGHT, width - 1)
                            @Suppress("UnsafeOptInUsageError")
                            setInteger(MediaFormat.KEY_CROP_BOTTOM, height - 1)
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            // API 29-32: Use string literals for crop keys
                            try {
                                setInteger("crop-left", 0)
                                setInteger("crop-top", 0)
                                setInteger("crop-right", width - 1)
                                setInteger("crop-bottom", height - 1)
                            } catch (_: Exception) {
                                // Crop keys might not be supported, but that's okay
                            }
                        }
                        // API 26-28: Crop keys not available, but width/height should be sufficient
                    }
                    
                    // Log crop values for verification (using same API compatibility logic)
                    val finalCropLeft: Int
                    val finalCropTop: Int
                    val finalCropRight: Int
                    val finalCropBottom: Int
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        // API 33+: Use KEY_CROP_* constants with getInteger(key, defaultValue)
                        @Suppress("UnsafeOptInUsageError")
                        finalCropLeft = normalizedFormat.getInteger(MediaFormat.KEY_CROP_LEFT, 0)
                        @Suppress("UnsafeOptInUsageError")
                        finalCropTop = normalizedFormat.getInteger(MediaFormat.KEY_CROP_TOP, 0)
                        @Suppress("UnsafeOptInUsageError")
                        finalCropRight = normalizedFormat.getInteger(MediaFormat.KEY_CROP_RIGHT, width - 1)
                        @Suppress("UnsafeOptInUsageError")
                        finalCropBottom = normalizedFormat.getInteger(MediaFormat.KEY_CROP_BOTTOM, height - 1)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // API 29-32: Use string literals with getInteger(key, defaultValue)
                        finalCropLeft = try { normalizedFormat.getInteger("crop-left", 0) } catch (_: Exception) { 0 }
                        finalCropTop = try { normalizedFormat.getInteger("crop-top", 0) } catch (_: Exception) { 0 }
                        finalCropRight = try { normalizedFormat.getInteger("crop-right", width - 1) } catch (_: Exception) { width - 1 }
                        finalCropBottom = try { normalizedFormat.getInteger("crop-bottom", height - 1) } catch (_: Exception) { height - 1 }
                    } else {
                        // API 26-28: Crop keys not available, use defaults we set
                        finalCropLeft = 0
                        finalCropTop = 0
                        finalCropRight = width - 1
                        finalCropBottom = height - 1
                    }
                    
                    Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Normalized format: width=${normalizedFormat.getInteger(MediaFormat.KEY_WIDTH)}, height=${normalizedFormat.getInteger(MediaFormat.KEY_HEIGHT)}")
                    Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Normalized crop: left=$finalCropLeft, top=$finalCropTop, right=$finalCropRight, bottom=$finalCropBottom")
                    val hasCsd0 = try { normalizedFormat.containsKey("csd-0") } catch (_: Throwable) { false }
                    val hasCsd1 = try { normalizedFormat.containsKey("csd-1") } catch (_: Throwable) { false }
                    Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Normalized CSD: csd-0=$hasCsd0, csd-1=$hasCsd1")
                    
                    synchronized(muxerLock) {
                        if (videoTrackIndex == -1) {
                            videoTrackIndex = mediaMuxer?.addTrack(normalizedFormat) ?: -1
                            videoFormatReceived.set(true)
                            Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Video track added to MediaMuxer: trackIndex=$videoTrackIndex, width=$width, height=$height")
                            startMuxerIfReady()
                        }
                    }
                }
                outputIndex >= 0 -> {
                    // CRITICAL FIX: Skip codec config buffers - they should NOT be written as frames
                    // Writing codec config buffers as frames causes incorrect duration and fast-forward playback
                    val isCodecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (isCodecConfig) {
                        // Codec config buffer - release it but don't write to MediaMuxer
                        // These are SPS/PPS headers that are already in the track format
                        Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Skipping codec config buffer (SPS/PPS) - size=${bufferInfo.size}")
                        currentCodec.releaseOutputBuffer(outputIndex, false)
                        continue
                    }
                    
                    // CRITICAL: Log every video output buffer to diagnose why frames aren't written
                    if (bufferInfo.size > 0) {
                        synchronized(muxerLock) {
                            val shouldWrite = muxerStarted && videoTrackIndex >= 0
                            if (encodedFrameCount % 30 == 0L || !shouldWrite || encodedFrameCount < 5) {
                                Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Video output buffer: size=${bufferInfo.size}, pts=${bufferInfo.presentationTimeUs}, muxerStarted=$muxerStarted, videoTrackIndex=$videoTrackIndex, shouldWrite=$shouldWrite")
                            }
                        }
                    }
                    
                    // CRITICAL: Check muxerStarted BEFORE processing to avoid wasted work
                    val shouldProcessVideo = synchronized(muxerLock) {
                        muxerStarted && bufferInfo.size > 0
                    }
                    
                    if (shouldProcessVideo) {
                        val buffer = currentCodec.getOutputBuffer(outputIndex)
                        if (buffer != null) {
                            buffer.position(bufferInfo.offset)
                            buffer.limit(bufferInfo.offset + bufferInfo.size)
                            
                            synchronized(muxerLock) {
                                if (muxerStarted && videoTrackIndex >= 0) {
                                    // CRITICAL FIX: Use codec PTS normalized to start at 0.
                                    // Using "frames written * frameDuration" compresses time if encoder drops frames,
                                    // which causes fast-forward video (shorter duration than audio).
                                    // We normalize the codec PTS to a 0-based timeline and only enforce strict monotonicity.
                                    val codecOutputPts = bufferInfo.presentationTimeUs
                                    val minStepUs = 1L // minimal step to keep strictly increasing timestamps
                                    
                                    // Establish baseline from first frame
                                    if (firstVideoPts < 0) {
                                        firstVideoPts = codecOutputPts
                                        Log.d(logFrom, "🔴 [CUSTOM_RECORDER] First video PTS from codec: $firstVideoPts")
                                    }
                                    
                                    // Normalize to start at 0 (avoid negative)
                                    val normalizedPts = (codecOutputPts - firstVideoPts).coerceAtLeast(0L)
                                    
                                    // CRITICAL: Ensure PTS is STRICTLY increasing (not just >=)
                                    // MediaMuxer requires strictly increasing timestamps per track
                                    // If PTS is equal to or less than previous, MediaMuxer will fail silently
                                    val finalPts = if (normalizedPts > lastWrittenVideoPts) {
                                        normalizedPts
                                    } else {
                                        // If PTS is not strictly increasing, bump by 1us
                                        val adjustedPts = lastWrittenVideoPts + minStepUs
                                        if (videoOutputFrameCount < 5 || videoOutputFrameCount % 30 == 0L) {
                                            Log.w(
                                                logFrom,
                                                "🔴 [CUSTOM_RECORDER] Video PTS not strictly increasing: normalizedPts=$normalizedPts <= lastWrittenPts=$lastWrittenVideoPts, adjusting to $adjustedPts"
                                            )
                                        }
                                        adjustedPts
                                    }
                                    
                                    // CRITICAL: Preserve key frame flag from codec output
                                    // MediaMuxer requires key frame markers for proper MP4 structure
                                    // The BUFFER_FLAG_KEY_FRAME flag indicates I-frames (key frames)
                                    val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                    
                                    // Update bufferInfo with corrected PTS (preserving flags)
                                    bufferInfo.presentationTimeUs = finalPts
                                    // Note: bufferInfo.flags is preserved - don't modify it
                                    lastWrittenVideoPts = finalPts
                                    
                                    // Log PTS correction for diagnosis
                                    if (videoOutputFrameCount < 5 || videoOutputFrameCount % 30 == 0L) {
                                        Log.d(
                                            logFrom,
                                            "🔴 [CUSTOM_RECORDER] Video PTS normalize: codecPts=$codecOutputPts, normalizedPts=$normalizedPts, finalPts=$finalPts, outputFrameCount=$videoOutputFrameCount, isKeyFrame=$isKeyFrame"
                                        )
                                    }
                                    
                                    // CRITICAL: Check muxerStarted before writing to prevent writes after auto-stop
                                    val shouldWriteVideo = synchronized(muxerLock) {
                                        if (!muxerStarted) {
                                            if (videoOutputFrameCount % 30 == 0L) {
                                                Log.w(logFrom, "🔴 [CUSTOM_RECORDER] Video sample NOT written: MediaMuxer already stopped (auto-stopped?)")
                                            }
                                            false
                                        } else {
                                            true
                                        }
                                    }
                                    
                                    if (shouldWriteVideo) {
                                        try {
                                            // Write with corrected PTS to ensure proper playback timing
                                            mediaMuxer?.writeSampleData(videoTrackIndex, buffer, bufferInfo)
                                            videoOutputFrameCount++
                                            
                                            // CRITICAL: Log successful writes periodically to verify MediaMuxer is still active
                                            if (videoOutputFrameCount % 30 == 0L || videoOutputFrameCount < 5) {
                                                Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Video sample written successfully: frameCount=$videoOutputFrameCount, pts=$finalPts, isKeyFrame=$isKeyFrame")
                                            }
                                        } catch (e: IllegalStateException) {
                                            // MediaMuxer may have auto-stopped due to invalid data/PTS
                                            // Mark as stopped to prevent further writes and stop() calls
                                            Log.e(logFrom, "🔴 [CUSTOM_RECORDER] CRITICAL: MediaMuxer IllegalStateException while writing video sample - muxer may have auto-stopped", e)
                                            Log.e(logFrom, "🔴 [CUSTOM_RECORDER] Video write error details: trackIndex=$videoTrackIndex, size=${bufferInfo.size}, pts=$finalPts, flags=${bufferInfo.flags}")
                                            synchronized(muxerLock) {
                                                muxerStarted = false  // Mark as stopped to prevent further writes
                                            }
                                        } catch (e: Exception) {
                                            Log.e(logFrom, "🔴 [CUSTOM_RECORDER] CRITICAL: Error writing video sample to MediaMuxer", e)
                                        }
                                    }
                                } else {
                                    if (encodedFrameCount % 30 == 0L) {
                                        Log.w(logFrom, "🔴 [CUSTOM_RECORDER] Video sample NOT written: muxerStarted=$muxerStarted, videoTrackIndex=$videoTrackIndex")
                                    }
                                }
                            }
                        }
                    }
                    currentCodec.releaseOutputBuffer(outputIndex, false)
                    
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.d(logFrom, "Video EOS received")
                        currentCodec.stop()
                        currentCodec.release()
                        videoCodec = null
                        break
                    }
                }
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                else -> break
            }
            }
        } catch (e: IllegalStateException) {
            // Codec was stopped/released by cleanup() while we were using it
            Log.w(logFrom, "🔴 [CUSTOM_RECORDER] Video codec state changed (likely cleanup concurrent) - exiting processVideoFrames", e)
        } catch (e: Exception) {
            Log.e(logFrom, "🔴 [CUSTOM_RECORDER] Error processing video frames", e)
        }
    }
    
    /**
     * Process audio frames from encoder
     */
    private fun processAudioFrames(bufferInfo: MediaCodec.BufferInfo) {
        val codec = audioCodec ?: return
        
        // Feed queued PCM data to encoder
        while (true) {
            val pcm: ByteArray? = synchronized(audioInputLock) {
                audioInputQueue.poll()
            }
            
            if (pcm == null) break
            
            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex < 0) {
                // No input buffer available, put PCM back
                synchronized(audioInputLock) {
                    audioInputQueue.offerFirst(pcm)
                }
                break
            }
            
            val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
            inputBuffer.clear()
            inputBuffer.put(pcm)
            
            val ptsUs = audioPtsUs
            val frameSizeBytes = pcm.size
            val frameDurationUs = (frameSizeBytes * 1_000_000L) / (audioSampleRate * 2) // 16-bit mono
            audioPtsUs += frameDurationUs
            lastQueuedAudioInputPtsUs = ptsUs
            
            codec.queueInputBuffer(inputIndex, 0, frameSizeBytes, ptsUs, 0)
            // NOTE: audioFrameCount is incremented when writing output samples, not here
            // This ensures accurate count of samples actually written to MediaMuxer
        }
        
        // Drain encoded audio
        audioDrain@ while (true) {
            // CRITICAL: Wrap dequeueOutputBuffer in try-catch for IllegalStateException
            // When stop() force-releases the codec, the next call will throw
            // Catching it allows the thread to exit gracefully instead of crashing
            val outputIndex: Int = try {
                codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            } catch (_: IllegalStateException) {
                // Codec was stopped/released while we were using it - exit gracefully
                Log.w(logFrom, "🔴 [CUSTOM_RECORDER] Audio codec illegal state (likely released) - exiting processAudioFrames")
                return // Exit the function, which breaks the loop
            }
            
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    // Log audio format for debugging
                    val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Audio format received: sampleRate=$sampleRate, channels=$channelCount")
                    Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Audio format: $format")
                    
                    synchronized(muxerLock) {
                        if (audioTrackIndex == -1) {
                            audioTrackIndex = mediaMuxer?.addTrack(format) ?: -1
                            audioFormatReceived.set(true)
                            Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Audio track added to MediaMuxer: trackIndex=$audioTrackIndex, sampleRate=$sampleRate, channels=$channelCount")
                            startMuxerIfReady()
                        }
                    }
                }
                outputIndex >= 0 -> {
                    // CRITICAL FIX: Skip codec config buffers for audio too (AAC CSD)
                    // Writing codec config buffers as samples can corrupt the container and later make muxer.stop() fail.
                    val isCodecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (isCodecConfig) {
                        Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Skipping audio codec config buffer - size=${bufferInfo.size}, flags=${bufferInfo.flags}")
                        codec.releaseOutputBuffer(outputIndex, false)
                        continue@audioDrain
                    }

                    if (bufferInfo.size > 0 && muxerStarted) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null) {
                            buffer.position(bufferInfo.offset)
                            buffer.limit(bufferInfo.offset + bufferInfo.size)
                            
                            synchronized(muxerLock) {
                                if (muxerStarted && audioTrackIndex >= 0) {
                                    // CRITICAL FIX: Use codec audio PTS normalized to start at 0.
                                    // Using a fixed AAC frame duration can drift if input PCM sizes vary or if AudioSourceEngine
                                    // delivers buffers faster/slower than real-time (it compresses/expands timeline).
                                    // Normalize codec PTS to 0-based and only enforce strict monotonicity.
                                    val codecAudioPts = bufferInfo.presentationTimeUs
                                    
                                    // Establish baseline from first audio frame
                                    if (firstAudioPts < 0) {
                                        firstAudioPts = codecAudioPts
                                        Log.d(logFrom, "🔴 [CUSTOM_RECORDER] First audio PTS from codec: $firstAudioPts")
                                    }
                                    
                                    val minStepUs = 1L // minimal step to keep strictly increasing timestamps
                                    val normalizedAudioPts = (codecAudioPts - firstAudioPts).coerceAtLeast(0L)
                                    
                                    // CRITICAL: Ensure PTS is STRICTLY increasing (not just >=)
                                    // MediaMuxer requires strictly increasing timestamps per track
                                    // If PTS is equal to or less than previous, MediaMuxer will fail silently
                                    val finalAudioPts = if (normalizedAudioPts > lastWrittenAudioPts) {
                                        normalizedAudioPts
                                    } else {
                                        // If corrected PTS is not strictly increasing, increment from last written PTS
                                        val adjustedPts = lastWrittenAudioPts + minStepUs
                                        if (audioFrameCount < 10 || audioFrameCount % 50 == 0L) {
                                            Log.w(
                                                logFrom,
                                                "🔴 [CUSTOM_RECORDER] Audio PTS not strictly increasing: normalizedPts=$normalizedAudioPts <= lastWrittenPts=$lastWrittenAudioPts, adjusting to $adjustedPts"
                                            )
                                        }
                                        adjustedPts
                                    }
                                    
                                    // Update bufferInfo with corrected PTS (preserving flags)
                                    bufferInfo.presentationTimeUs = finalAudioPts
                                    // Note: bufferInfo.flags is preserved - don't modify it
                                    lastWrittenAudioPts = finalAudioPts
                                    
                                    // Log audio PTS correction for diagnosis
                                    if (audioFrameCount % 50 == 0L || audioFrameCount < 10) {
                                        Log.d(
                                            logFrom,
                                            "🔴 [CUSTOM_RECORDER] Audio PTS normalize: codecPts=$codecAudioPts, normalizedPts=$normalizedAudioPts, finalPts=$finalAudioPts, frameCount=$audioFrameCount"
                                        )
                                    }
                                    
                                    // CRITICAL: Check muxerStarted before writing to prevent writes after auto-stop
                                    val shouldWriteAudio = synchronized(muxerLock) {
                                        if (!muxerStarted) {
                                            if (audioFrameCount % 50 == 0L) {
                                                Log.w(logFrom, "🔴 [CUSTOM_RECORDER] Audio sample NOT written: MediaMuxer already stopped (auto-stopped?)")
                                            }
                                            false
                                        } else {
                                            true
                                        }
                                    }
                                    
                                    if (shouldWriteAudio) {
                                        try {
                                            // Write with corrected PTS to ensure proper sync with video
                                            mediaMuxer?.writeSampleData(audioTrackIndex, buffer, bufferInfo)
                                            audioFrameCount++
                                            
                                            // CRITICAL: Log successful writes periodically to verify MediaMuxer is still active
                                            if (audioFrameCount % 50 == 0L || audioFrameCount < 10) {
                                                Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Audio sample written successfully: frameCount=$audioFrameCount, pts=$finalAudioPts")
                                            }
                                        } catch (e: IllegalStateException) {
                                            // MediaMuxer may have auto-stopped due to invalid data/PTS
                                            // Mark as stopped to prevent further writes and stop() calls
                                            Log.e(logFrom, "🔴 [CUSTOM_RECORDER] CRITICAL: MediaMuxer IllegalStateException while writing audio sample - muxer may have auto-stopped", e)
                                            Log.e(logFrom, "🔴 [CUSTOM_RECORDER] Audio write error details: trackIndex=$audioTrackIndex, size=${bufferInfo.size}, pts=$finalAudioPts, flags=${bufferInfo.flags}")
                                            synchronized(muxerLock) {
                                                muxerStarted = false  // Mark as stopped to prevent further writes
                                            }
                                        } catch (e: Exception) {
                                            Log.e(logFrom, "🔴 [CUSTOM_RECORDER] CRITICAL: Error writing audio sample to MediaMuxer", e)
                                            Log.e(logFrom, "🔴 [CUSTOM_RECORDER] Audio write error details: trackIndex=$audioTrackIndex, size=${bufferInfo.size}, pts=$finalAudioPts, flags=${bufferInfo.flags}")
                                        }
                                    }
                                } else {
                                    if (audioFrameCount % 50 == 0L || audioFrameCount < 10) {
                                        Log.w(logFrom, "🔴 [CUSTOM_RECORDER] Audio sample NOT written: muxerStarted=$muxerStarted, audioTrackIndex=$audioTrackIndex, frameCount=$audioFrameCount")
                                    }
                                }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.d(logFrom, "Audio EOS received")
                        codec.stop()
                        codec.release()
                        audioCodec = null
                        break
                    }
                }
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                else -> break
            }
        }
    }
    
    /**
     * Start muxer when both tracks are ready
     */
    private fun startMuxerIfReady() {
        synchronized(muxerLock) {
            if (muxerStarted) {
                Log.d(logFrom, "🔴 [CUSTOM_RECORDER] MediaMuxer already started - skipping")
                return
            }
            
            val hasVideo = videoTrackIndex >= 0
            val hasAudio = audioTrackIndex >= 0
            val audioCodecExists = audioCodec != null
            
            Log.d(logFrom, "🔴 [CUSTOM_RECORDER] startMuxerIfReady: hasVideo=$hasVideo, hasAudio=$hasAudio, audioCodecExists=$audioCodecExists")
            
            // Start muxer if we have at least video track (audio is optional)
            // Condition: hasVideo AND (hasAudio OR no audio codec needed)
            if (hasVideo && (hasAudio || !audioCodecExists)) {
                try {
                    Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Starting MediaMuxer...")
                    mediaMuxer?.start()
                    muxerStarted = true
                    Log.d(logFrom, "🔴 [CUSTOM_RECORDER] MediaMuxer started successfully (video=$hasVideo, audio=$hasAudio)")
                } catch (e: Exception) {
                    Log.e(logFrom, "🔴 [CUSTOM_RECORDER] CRITICAL: Failed to start MediaMuxer", e)
                }
            } else {
                Log.d(logFrom, "🔴 [CUSTOM_RECORDER] MediaMuxer NOT started yet - waiting for tracks (hasVideo=$hasVideo, hasAudio=$hasAudio, audioCodecExists=$audioCodecExists)")
            }
        }
    }
    
    /**
     * Finalize recording - stop MediaMuxer to flush all data to file
     * 
     * CRITICAL: This must be called AFTER the recording thread has finished draining all buffers.
     * Stopping MediaMuxer while it's still being written to will result in a 0-byte or corrupted file.
     * 
     * Note: If MediaMuxer was never started (no frames processed before stop), we still need to
     * handle cleanup gracefully. The file will be 0 bytes or invalid in this case.
     */
    private fun finalizeRecording() {
        synchronized(muxerLock) {
            // CRITICAL: Check if MediaMuxer is already stopped before attempting to stop
            // MediaMuxer may have auto-stopped due to errors during writeSampleData()
            if (!muxerStarted) {
                Log.d(logFrom, "🔴 [CUSTOM_RECORDER] MediaMuxer already marked as stopped - skipping stop() call")
                return
            }
            
            if (mediaMuxer == null) {
                Log.d(logFrom, "🔴 [CUSTOM_RECORDER] MediaMuxer is null - already cleaned up")
                muxerStarted = false
                return
            }
            
            try {
                Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Stopping MediaMuxer (flushing data to file)...")
                mediaMuxer?.stop()
                muxerStarted = false  // CRITICAL: Mark as stopped to prevent double-stop
                Log.d(logFrom, "🔴 [CUSTOM_RECORDER] MediaMuxer stopped successfully - file should now be valid")
            } catch (e: IllegalStateException) {
                // MediaMuxer might already be stopped or in invalid state
                // This can happen if MediaMuxer auto-stopped due to errors during writeSampleData()
                // or if there's a race condition
                Log.e(
                    logFrom,
                    "🔴 [CUSTOM_RECORDER] MediaMuxer.stop() IllegalStateException - muxer already stopped/invalid.\n" +
                        "Diagnostics: muxerStarted=$muxerStarted, videoTrackIndex=$videoTrackIndex, audioTrackIndex=$audioTrackIndex, " +
                        "lastWrittenVideoPts=$lastWrittenVideoPts, lastWrittenAudioPts=$lastWrittenAudioPts, " +
                        "fd=${fileDescriptor?.fd}",
                    e
                )
                muxerStarted = false  // Mark as stopped to prevent further attempts
                // Don't re-throw - continue with cleanup to prevent resource leaks
            } catch (e: Exception) {
                Log.e(logFrom, "🔴 [CUSTOM_RECORDER] CRITICAL: Error stopping MediaMuxer - file may be corrupted", e)
                muxerStarted = false  // Mark as stopped even on error to prevent further attempts
                // Don't re-throw - continue with cleanup to prevent resource leaks
                // The file may be corrupted, but we must still release resources
            }
        }
        
        // Additional check outside synchronized block for logging
        synchronized(muxerLock) {
            if (!muxerStarted && mediaMuxer != null) {
                // MediaMuxer was never started or is null - this can happen if:
                // 1. Stop was called before any frames were processed (no INFO_OUTPUT_FORMAT_CHANGED)
                // 2. Video/audio tracks were never added to muxer
                // 3. startMuxerIfReady() failed
                // 4. MediaMuxer was already stopped/released
                Log.d(logFrom, "🔴 [CUSTOM_RECORDER] MediaMuxer was not started or already stopped")
                Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Video track added: ${videoTrackIndex >= 0}, Audio track added: ${audioTrackIndex >= 0}")
            }
        }
    }
    
    /**
     * Cleanup resources
     * 
     * CRITICAL: This must be called AFTER finalizeRecording() (MediaMuxer.stop()).
     * The order is:
     * 1. Release codecs (should already be stopped/null by recording thread)
     * 2. Release MediaMuxer (must be stopped first)
     * 3. Close FileDescriptor (must be after MediaMuxer is released)
     * 
     * Closing FileDescriptor before MediaMuxer is released will cause a crash or corrupted file.
     */
    private fun cleanup() {
        Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Starting cleanup...")
        
        // Release video codec
        try {
            val codec = videoCodec
            if (codec != null) {
                Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Releasing video codec...")
                codec.stop()
                codec.release()
                Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Video codec released")
            }
        } catch (e: Exception) {
            Log.w(logFrom, "🔴 [CUSTOM_RECORDER] Error releasing video codec", e)
        }
        videoCodec = null
        
        // Release audio codec
        try {
            val codec = audioCodec
            if (codec != null) {
                Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Releasing audio codec...")
                codec.stop()
                codec.release()
                Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Audio codec released")
            }
        } catch (e: Exception) {
            Log.w(logFrom, "🔴 [CUSTOM_RECORDER] Error releasing audio codec", e)
        }
        audioCodec = null
        
        // CRITICAL: Release MediaMuxer (must be stopped first via finalizeRecording())
        try {
            val muxer = mediaMuxer
            if (muxer != null) {
                Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Releasing MediaMuxer...")
                muxer.release()
                Log.d(logFrom, "🔴 [CUSTOM_RECORDER] MediaMuxer released")
            }
        } catch (e: Exception) {
            Log.e(logFrom, "🔴 [CUSTOM_RECORDER] CRITICAL: Error releasing MediaMuxer", e)
        }
        mediaMuxer = null
        
        // CRITICAL: Close FileDescriptor AFTER MediaMuxer is fully released
        // Closing it before will cause the file to be 0 bytes or crash
        try {
            val fd = fileDescriptor
            if (fd != null) {
                Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Closing FileDescriptor: fd=${fd.fd}...")
                fd.close()
                Log.d(logFrom, "🔴 [CUSTOM_RECORDER] FileDescriptor closed successfully")
            }
        } catch (e: Exception) {
            Log.e(logFrom, "🔴 [CUSTOM_RECORDER] CRITICAL: Error closing FileDescriptor", e)
        }
        fileDescriptor = null
        
        // Reset state
        videoTrackIndex = -1
        audioTrackIndex = -1
        muxerStarted = false
        videoFormatReceived.set(false)
        audioFormatReceived.set(false)
        
        synchronized(audioInputLock) {
            audioInputQueue.clear()
        }
        
        Log.d(logFrom, "🔴 [CUSTOM_RECORDER] Cleanup completed")
    }
    
    /**
     * Select input color format (similar to VideoEncoder)
     * Note: Using deprecated REGULAR_CODECS constructor with @Suppress since parameterless constructor requires API 29+
     * and minSdk is 26, so we need to support API 26-28. The deprecated constructor is still functional.
     */
    private fun selectInputColorFormat(): Int {
        return try {
            @Suppress("DEPRECATION")
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val info = list.codecInfos.firstOrNull { it.isEncoder && it.supportedTypes.any { t -> t == MediaFormat.MIMETYPE_VIDEO_AVC } }
            if (info == null) {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            } else {
                val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val formats = caps.colorFormats.toSet()
                @Suppress("DEPRECATION")
                when {
                    formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) ->
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                    formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) ->
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                    else ->
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                }
            }
        } catch (e: Exception) {
            Log.w(logFrom, "Failed to query encoder color formats; defaulting to FLEXIBLE", e)
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        }
    }
    
    /**
     * Normalize rotation (0, 90, 180, 270)
     */
    private fun normalizeRotation(deg: Int): Int {
        val d = ((deg % 360) + 360) % 360
        return when (d) {
            90 -> 90
            180 -> 180
            270 -> 270
            else -> 0
        }
    }
    
    /**
     * YUV to NV12 conversion with rotation (Ported from VideoEncoder)
     */
    private fun yuv420ToNV12Rotated(
        image: ImageProxy,
        output: ByteBuffer,
        rotationDeg: Int
    ): Int {
        // Step 1: produce I420 into scratch (stable across devices)
        val tmp = ByteBuffer.wrap(scratch)
        tmp.clear()
        val required = yuv420ToI420RotatedToI420(image, tmp, rotationDeg)
        if (required <= 0) return 0

        val ySize = width * height
        val chromaSize = ySize / 4
        // Step 2: convert I420 -> NV12 (UV interleaved)
        output.put(scratch, 0, ySize) // Y
        var uIdx = ySize
        var vIdx = ySize + chromaSize
        repeat(chromaSize) {
            output.put(scratch[uIdx++]) // U
            output.put(scratch[vIdx++]) // V
        }
        return ySize + chromaSize * 2
    }

    /**
     * Fast-path: Convert YUV_420_888 -> NV12 when:
     * - rotation is 0 (no rotate)
     * - src matches dst dimensions exactly
     *
     * This avoids the expensive per-pixel coordinate mapping and greatly improves FPS in ByteBuffer mode.
     * Falls back to safe per-sample reads if pixelStride is not 1.
     */
    private fun fastYuv420ToNV12NoRotateExactFit(image: ImageProxy, output: ByteBuffer): Int {
        val required = width * height * 3 / 2
        if (output.capacity() < required) {
            Log.w(logFrom, "Fast NV12: input buffer too small: cap=${output.capacity()} need=$required")
            return 0
        }

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer.duplicate()
        val uBuf = uPlane.buffer.duplicate()
        val vBuf = vPlane.buffer.duplicate()

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        // Y plane
        if (yPixelStride == 1) {
            for (row in 0 until height) {
                val srcPos = row * yRowStride
                yBuf.position(srcPos)
                yBuf.get(fastRowY, 0, width)
                output.put(fastRowY, 0, width)
            }
        } else {
            // Rare case: sample each pixel
            for (row in 0 until height) {
                val base = row * yRowStride
                for (col in 0 until width) {
                    output.put(yBuf.get(base + col * yPixelStride))
                }
            }
        }

        // UV plane (NV12 = U,V interleaved)
        val chromaH = height / 2
        val chromaW = width / 2
        if (uPixelStride == 1 && vPixelStride == 1) {
            for (row in 0 until chromaH) {
                uBuf.position(row * uRowStride)
                vBuf.position(row * vRowStride)
                uBuf.get(fastRowU, 0, chromaW)
                vBuf.get(fastRowV, 0, chromaW)
                var i = 0
                var o = 0
                while (i < chromaW) {
                    fastRowUV[o++] = fastRowU[i]
                    fastRowUV[o++] = fastRowV[i]
                    i++
                }
                output.put(fastRowUV, 0, chromaW * 2)
            }
        } else {
            // Sample per chroma pixel (still much cheaper than full-res per-pixel mapping)
            for (row in 0 until chromaH) {
                val uBase = row * uRowStride
                val vBase = row * vRowStride
                for (col in 0 until chromaW) {
                    output.put(uBuf.get(uBase + col * uPixelStride))
                    output.put(vBuf.get(vBase + col * vPixelStride))
                }
            }
        }
        return required
    }

    /**
     * Fast-path: Convert YUV_420_888 -> I420 when:
     * - rotation is 0 (no rotate)
     * - src matches dst dimensions exactly
     *
     * Layout: Y (W*H) + U (W/2*H/2) + V (W/2*H/2)
     */
    private fun fastYuv420ToI420NoRotateExactFit(image: ImageProxy, output: ByteBuffer): Int {
        val required = width * height * 3 / 2
        if (output.capacity() < required) {
            Log.w(logFrom, "Fast I420: input buffer too small: cap=${output.capacity()} need=$required")
            return 0
        }

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer.duplicate()
        val uBuf = uPlane.buffer.duplicate()
        val vBuf = vPlane.buffer.duplicate()

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        // Y
        if (yPixelStride == 1) {
            for (row in 0 until height) {
                yBuf.position(row * yRowStride)
                yBuf.get(fastRowY, 0, width)
                output.put(fastRowY, 0, width)
            }
        } else {
            for (row in 0 until height) {
                val base = row * yRowStride
                for (col in 0 until width) {
                    output.put(yBuf.get(base + col * yPixelStride))
                }
            }
        }

        // U then V
        val chromaH = height / 2
        val chromaW = width / 2
        if (uPixelStride == 1) {
            for (row in 0 until chromaH) {
                uBuf.position(row * uRowStride)
                uBuf.get(fastRowU, 0, chromaW)
                output.put(fastRowU, 0, chromaW)
            }
        } else {
            for (row in 0 until chromaH) {
                val base = row * uRowStride
                for (col in 0 until chromaW) {
                    output.put(uBuf.get(base + col * uPixelStride))
                }
            }
        }

        if (vPixelStride == 1) {
            for (row in 0 until chromaH) {
                vBuf.position(row * vRowStride)
                vBuf.get(fastRowV, 0, chromaW)
                output.put(fastRowV, 0, chromaW)
            }
        } else {
            for (row in 0 until chromaH) {
                val base = row * vRowStride
                for (col in 0 until chromaW) {
                    output.put(vBuf.get(base + col * vPixelStride))
                }
            }
        }
        return required
    }
    
    /**
     * YUV to I420 conversion with rotation (Ported from VideoEncoder)
     * Handles scaling, letterboxing, and rotation correctly.
     */
    private fun yuv420ToI420RotatedToI420(
        image: ImageProxy,
        output: ByteBuffer,
        rotationDeg: Int
    ): Int {
        val srcW = image.width
        val srcH = image.height
        val dstW = width
        val dstH = height

        val rot = normalizeRotation(rotationDeg)
        val required = dstW * dstH * 3 / 2
        if (output.capacity() < required) {
            Log.w(logFrom, "Encoder input buffer too small: cap=${output.capacity()} need=$required")
            return 0
        }

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        if (!loggedYuvLayout) {
            loggedYuvLayout = true
            Log.d(
                logFrom,
                "YUV layout src=${srcW}x${srcH} rotMeta=$rotationDeg " +
                    "Y[row=$yRowStride pix=$yPixelStride] " +
                    "U[row=$uRowStride pix=$uPixelStride] " +
                    "V[row=$vRowStride pix=$vPixelStride]"
            )
        }

        // Determine the "rotated source" dimensions
        val rotW = if (rot == 90 || rot == 270) srcH else srcW
        val rotH = if (rot == 90 || rot == 270) srcW else srcH

        // "Zoom out": Fit the full rotated source into dstW x dstH without distortion
        val scale = minOf(dstW.toFloat() / rotW.toFloat(), dstH.toFloat() / rotH.toFloat())
        val fitW = (rotW * scale).toInt().coerceAtLeast(1)
        val fitH = (rotH * scale).toInt().coerceAtLeast(1)
        val padX = ((dstW - fitW) / 2).coerceAtLeast(0)
        val padY = ((dstH - fitH) / 2).coerceAtLeast(0)

        // Fast path (common case)
        if (padX == 0 && padY == 0 && fitW == dstW && fitH == dstH) {
            val mapKey = "dst=${dstW}x${dstH}|rot=$rot|src=${srcW}x${srcH}|rotSrc=${rotW}x${rotH}"
            var rxMap = cachedRxMap
            var ryMap = cachedRyMap
            var rcxMap = cachedRcxMap
            var rcyMap = cachedRcyMap
            if (cachedMapKey != mapKey || rxMap == null || ryMap == null || rcxMap == null || rcyMap == null) {
                rxMap = IntArray(dstW)
                ryMap = IntArray(dstH)
                val rxDen = (dstW - 1).coerceAtLeast(1)
                val ryDen = (dstH - 1).coerceAtLeast(1)
                for (x in 0 until dstW) {
                    rxMap[x] = (x.toLong() * (rotW - 1) / rxDen).toInt()
                }
                for (y in 0 until dstH) {
                    ryMap[y] = (y.toLong() * (rotH - 1) / ryDen).toInt()
                }

                val dstChromaW = dstW / 2
                val dstChromaH = dstH / 2
                val rotChromaW = (rotW / 2).coerceAtLeast(1)
                val rotChromaH = (rotH / 2).coerceAtLeast(1)
                rcxMap = IntArray(dstChromaW)
                rcyMap = IntArray(dstChromaH)
                val rcxDen = (dstChromaW - 1).coerceAtLeast(1)
                val rcyDen = (dstChromaH - 1).coerceAtLeast(1)
                for (x in 0 until dstChromaW) {
                    rcxMap[x] = (x.toLong() * (rotChromaW - 1) / rcxDen).toInt()
                }
                for (y in 0 until dstChromaH) {
                    rcyMap[y] = (y.toLong() * (rotChromaH - 1) / rcyDen).toInt()
                }

                cachedMapKey = mapKey
                cachedRxMap = rxMap
                cachedRyMap = ryMap
                cachedRcxMap = rcxMap
                cachedRcyMap = rcyMap
            }

            // --- Y plane (sequential) ---
            val outY = output.duplicate()
            outY.position(0)
            when (rot) {
                0 -> {
                    for (y in 0 until dstH) {
                        val ry = ryMap[y]
                        val base = ry * yRowStride
                        for (x in 0 until dstW) {
                            val rx = rxMap[x]
                            outY.put(yBuf.get(base + rx * yPixelStride))
                        }
                    }
                }
                90 -> {
                    for (y in 0 until dstH) {
                        val ry = ryMap[y]
                        for (x in 0 until dstW) {
                            val rx = rxMap[x]
                            outY.put(yBuf.get((srcH - 1 - rx) * yRowStride + ry * yPixelStride))
                        }
                    }
                }
                180 -> {
                    for (y in 0 until dstH) {
                        val ry = ryMap[y]
                        for (x in 0 until dstW) {
                            val rx = rxMap[x]
                            val sx = srcW - 1 - rx
                            val sy = srcH - 1 - ry
                            outY.put(yBuf.get(sy * yRowStride + sx * yPixelStride))
                        }
                    }
                }
                270 -> {
                    for (y in 0 until dstH) {
                        val ry = ryMap[y]
                        for (x in 0 until dstW) {
                            val rx = rxMap[x]
                            val sx = srcW - 1 - ry
                            outY.put(yBuf.get(rx * yRowStride + sx * yPixelStride))
                        }
                    }
                }
                else -> {
                    for (y in 0 until dstH) {
                        val ry = ryMap[y]
                        val base = ry * yRowStride
                        for (x in 0 until dstW) {
                            val rx = rxMap[x]
                            outY.put(yBuf.get(base + rx * yPixelStride))
                        }
                    }
                }
            }

            // --- U/V planes (sequential planar I420) ---
            val dstChromaW = dstW / 2
            val dstChromaH = dstH / 2
            val uOffset = dstW * dstH
            val vOffset = uOffset + (dstChromaW * dstChromaH)

            val srcChromaW = (srcW / 2).coerceAtLeast(1)
            val srcChromaH = (srcH / 2).coerceAtLeast(1)
            fun sampleU(cx: Int, cy: Int): Byte {
                val x = cx.coerceIn(0, srcChromaW - 1)
                val y = cy.coerceIn(0, srcChromaH - 1)
                return uBuf.get(y * uRowStride + x * uPixelStride)
            }
            fun sampleV(cx: Int, cy: Int): Byte {
                val x = cx.coerceIn(0, srcChromaW - 1)
                val y = cy.coerceIn(0, srcChromaH - 1)
                return vBuf.get(y * vRowStride + x * vPixelStride)
            }

            val outUV = output.duplicate()
            outUV.position(uOffset)
            when (rot) {
                0 -> {
                    for (cy in 0 until dstChromaH) {
                        val rcy = rcyMap[cy]
                        for (cx in 0 until dstChromaW) {
                            val rcx = rcxMap[cx]
                            outUV.put(sampleU(rcx, rcy))
                        }
                    }
                }
                90 -> {
                    for (cy in 0 until dstChromaH) {
                        val rcy = rcyMap[cy]
                        for (cx in 0 until dstChromaW) {
                            val rcx = rcxMap[cx]
                            outUV.put(sampleU(rcy, srcChromaH - 1 - rcx))
                        }
                    }
                }
                180 -> {
                    for (cy in 0 until dstChromaH) {
                        val rcy = rcyMap[cy]
                        for (cx in 0 until dstChromaW) {
                            val rcx = rcxMap[cx]
                            val scx = srcChromaW - 1 - rcx
                            val scy = srcChromaH - 1 - rcy
                            outUV.put(sampleU(scx, scy))
                        }
                    }
                }
                270 -> {
                    for (cy in 0 until dstChromaH) {
                        val rcy = rcyMap[cy]
                        for (cx in 0 until dstChromaW) {
                            val rcx = rcxMap[cx]
                            val scx = srcChromaW - 1 - rcy
                            outUV.put(sampleU(scx, rcx))
                        }
                    }
                }
                else -> {
                    for (cy in 0 until dstChromaH) {
                        val rcy = rcyMap[cy]
                        for (cx in 0 until dstChromaW) {
                            val rcx = rcxMap[cx]
                            outUV.put(sampleU(rcx, rcy))
                        }
                    }
                }
            }

            outUV.position(vOffset)
            when (rot) {
                0 -> {
                    for (cy in 0 until dstChromaH) {
                        val rcy = rcyMap[cy]
                        for (cx in 0 until dstChromaW) {
                            val rcx = rcxMap[cx]
                            outUV.put(sampleV(rcx, rcy))
                        }
                    }
                }
                90 -> {
                    for (cy in 0 until dstChromaH) {
                        val rcy = rcyMap[cy]
                        for (cx in 0 until dstChromaW) {
                            val rcx = rcxMap[cx]
                            outUV.put(sampleV(rcy, srcChromaH - 1 - rcx))
                        }
                    }
                }
                180 -> {
                    for (cy in 0 until dstChromaH) {
                        val rcy = rcyMap[cy]
                        for (cx in 0 until dstChromaW) {
                            val rcx = rcxMap[cx]
                            val scx = srcChromaW - 1 - rcx
                            val scy = srcChromaH - 1 - rcy
                            outUV.put(sampleV(scx, scy))
                        }
                    }
                }
                270 -> {
                    for (cy in 0 until dstChromaH) {
                        val rcy = rcyMap[cy]
                        for (cx in 0 until dstChromaW) {
                            val rcx = rcxMap[cx]
                            val scx = srcChromaW - 1 - rcy
                            outUV.put(sampleV(scx, rcx))
                        }
                    }
                }
                else -> {
                    for (cy in 0 until dstChromaH) {
                        val rcy = rcyMap[cy]
                        for (cx in 0 until dstChromaW) {
                            val rcx = rcxMap[cx]
                            outUV.put(sampleV(rcx, rcy))
                        }
                    }
                }
            }

            output.position(required)
            return required
        }

        fun mapRotatedToSource(rx: Int, ry: Int): Pair<Int, Int> {
            return when (rot) {
                0 -> rx to ry
                90 -> ry to (srcH - 1 - rx)
                180 -> (srcW - 1 - rx) to (srcH - 1 - ry)
                270 -> (srcW - 1 - ry) to rx
                else -> rx to ry
            }
        }

        // --- Y plane: fill black, then scale+rotate+fit into dst ---
        repeat(dstW * dstH) { output.put(0) }

        val outDupY = output.duplicate()
        for (y in 0 until fitH) {
            val ry = (y.toLong() * (rotH - 1) / (fitH - 1).coerceAtLeast(1)).toInt()
            val outY = padY + y
            val rowBase = outY * dstW
            for (x in 0 until fitW) {
                val rx = (x.toLong() * (rotW - 1) / (fitW - 1).coerceAtLeast(1)).toInt()
                val (sx, sy) = mapRotatedToSource(rx, ry)
                val outX = padX + x
                val yIdx = sy * yRowStride + sx * yPixelStride
                outDupY.put(rowBase + outX, yBuf.get(yIdx))
            }
        }

        // --- U and V planes ---
        val dstChromaW = dstW / 2
        val dstChromaH = dstH / 2
        val uOffset = dstW * dstH
        val vOffset = uOffset + (dstChromaW * dstChromaH)
        val outDup = output.duplicate()

        fun putU(idx: Int, value: Byte) {
            outDup.put(uOffset + idx, value)
        }
        fun putV(idx: Int, value: Byte) {
            outDup.put(vOffset + idx, value)
        }

        val srcChromaW = srcW / 2
        val srcChromaH = srcH / 2
        fun sampleU(srcCx: Int, srcCy: Int): Byte {
            val cx = srcCx.coerceIn(0, srcChromaW - 1)
            val cy = srcCy.coerceIn(0, srcChromaH - 1)
            return uBuf.get(cy * uRowStride + cx * uPixelStride)
        }
        fun sampleV(srcCx: Int, srcCy: Int): Byte {
            val cx = srcCx.coerceIn(0, srcChromaW - 1)
            val cy = srcCy.coerceIn(0, srcChromaH - 1)
            return vBuf.get(cy * vRowStride + cx * vPixelStride)
        }
        val rotChromaW = rotW / 2
        val rotChromaH = rotH / 2
        val fitChromaW = (fitW / 2).coerceAtLeast(1)
        val fitChromaH = (fitH / 2).coerceAtLeast(1)
        val padCx = (padX / 2).coerceAtLeast(0)
        val padCy = (padY / 2).coerceAtLeast(0)

        fun mapRotatedChromaToSource(rcx: Int, rcy: Int): Pair<Int, Int> {
            return when (rot) {
                0 -> rcx to rcy
                90 -> rcy to (srcChromaH - 1 - rcx)
                180 -> (srcChromaW - 1 - rcx) to (srcChromaH - 1 - rcy)
                270 -> (srcChromaW - 1 - rcy) to rcx
                else -> rcx to rcy
            }
        }

        // Fill chroma with 128 (neutral)
        for (i in 0 until (dstChromaW * dstChromaH)) {
            putU(i, 128.toByte())
            putV(i, 128.toByte())
        }

        for (cy in 0 until fitChromaH) {
            val rcy = (cy.toLong() * (rotChromaH - 1) / (fitChromaH - 1).coerceAtLeast(1)).toInt()
            val outCy = padCy + cy
            val rowBase = outCy * dstChromaW
            for (cx in 0 until fitChromaW) {
                val rcx = (cx.toLong() * (rotChromaW - 1) / (fitChromaW - 1).coerceAtLeast(1)).toInt()
                val (scx, scy) = mapRotatedChromaToSource(rcx, rcy)
                val outCx = padCx + cx
                val outIdx = rowBase + outCx
                putU(outIdx, sampleU(scx, scy))
                putV(outIdx, sampleV(scx, scy))
            }
        }

        output.position(required)
        return required
    }
}
