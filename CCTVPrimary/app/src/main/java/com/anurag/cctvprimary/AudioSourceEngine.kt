package com.anurag.cctvprimary

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Shared Audio Source Engine
 * 
 * Manages a single AudioRecord instance and multicasts PCM data to registered listeners.
 * Uses reference counting to start/stop the mic based on active consumers (streaming or recording).
 * 
 * Thread-safe singleton pattern ensures only one AudioRecord instance exists at a time.
 */
private const val  TAG = "AudioSourceEngine"
// Diagnostic: throttled capture stats (every 2s) for audio pipeline debugging
private const val CAPTURE_DIAG_INTERVAL_MS = 2000L
class AudioSourceEngine private constructor() {
    
    companion object {
        @Volatile
        private var INSTANCE: AudioSourceEngine? = null
        
        fun getInstance(): AudioSourceEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AudioSourceEngine().also { INSTANCE = it }
            }
        }
    }

    
    
    // Audio configuration
    private val sampleRate = 48000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    
    // State
    @Volatile
    private var audioRecord: AudioRecord? = null
    
    @Volatile
    private var isRunning = false
    
    private var captureThread: Thread? = null

    // Track currently active AudioRecord source + mode (for dynamic switching)
    @Volatile private var currentAudioSource: Int = -1
    @Volatile private var currentRecordingActive: Boolean = false
    @Volatile private var currentStreamingAndRecording: Boolean = false

    // Diagnostic: throttled capture stats (every 2s) for audio pipeline debugging
    private var lastCaptureDiagMs: Long = 0L
    //private const val CAPTURE_DIAG_INTERVAL_MS = 2000L

    /**
     * Convert MediaRecorder.AudioSource int to a readable label for logs.
     * This helps diagnose device-specific source behavior (e.g., UNPROCESSED being too quiet).
     */
    private fun audioSourceLabel(src: Int): String {
        return try {
            when (src) {
                MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"
                MediaRecorder.AudioSource.MIC -> "MIC"
                MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
                MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
                MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
                MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
                else -> "UNKNOWN($src)"
            }
        } catch (_: Throwable) {
            "UNKNOWN($src)"
        }
    }
    
    // Reference counting
    @Volatile
    private var streamingRefCount = 0
    
    @Volatile
    private var recordingRefCount = 0
    
    // Listeners (thread-safe)
    //
    // CRITICAL: CopyOnWriteArrayList allows duplicates.
    // If the same listener is registered twice (e.g., ref-count bugs or repeated register calls),
    // it will receive PCM twice, which effectively doubles audio duration and breaks A/V sync and muxing.
    // We treat this list as a SET (no duplicates) by guarding with contains() before add().
    private val listeners = CopyOnWriteArrayList<AudioListener>()
    
    // Context for permission checks (set by CameraForegroundService)
    // Use WeakReference to avoid memory leak (static INSTANCE holding Context reference)
    @Volatile
    private var contextRef: java.lang.ref.WeakReference<android.content.Context>? = null
    
    /**
     * Interface for audio consumers
     */
    interface AudioListener {
        fun onAudioData(pcm: ByteArray, sampleRate: Int, channels: Int)
    }
    
    /**
     * Set context for permission checks (called by CameraForegroundService)
     * Uses WeakReference to prevent memory leak from static INSTANCE
     */
    fun setContext(ctx: android.content.Context) {
        // Use ApplicationContext to avoid Activity context leak
        val appContext = ctx.applicationContext
        contextRef = java.lang.ref.WeakReference(appContext)
    }
    
    /**
     * Register a listener for streaming audio
     */
    fun registerStreamingListener(listener: AudioListener) {
        synchronized(this) {
            try {
                val alreadyRegistered = listeners.contains(listener)
                if (alreadyRegistered) {
                    // Fallback: don't double-register; just increment refcount for lifecycle tracking
                    streamingRefCount++
                    Log.w(TAG, "Streaming listener already registered - preventing duplicate delivery (streamingRefCount=$streamingRefCount)")
                    startCaptureIfNeeded()
                    return
                }

                listeners.add(listener)
                streamingRefCount++
                Log.d(TAG, "Streaming listener registered, refCount=$streamingRefCount, totalListeners=${listeners.size}")
                startCaptureIfNeeded()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register streaming listener", e)
            }
        }
    }
    
    /**
     * Unregister a listener for streaming audio
     */
    fun unregisterStreamingListener(listener: AudioListener) {
        synchronized(this) {
            try {
                val removed = listeners.remove(listener)
                streamingRefCount--
                if (streamingRefCount < 0) streamingRefCount = 0
                Log.d(
                    TAG,
                    "Streaming listener unregistered (removed=$removed), refCount=$streamingRefCount, totalListeners=${listeners.size}"
                )
                stopCaptureIfNeeded()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister streaming listener", e)
            }
        }
    }
    
    /**
     * Register a listener for recording audio
     */
    fun registerRecordingListener(listener: AudioListener) {
        synchronized(this) {
            try {
                val alreadyRegistered = listeners.contains(listener)
                if (alreadyRegistered) {
                    // Fallback: don't double-register; just increment refcount for lifecycle tracking
                    recordingRefCount++
                    Log.w(TAG, "Recording listener already registered - preventing duplicate delivery (recordingRefCount=$recordingRefCount)")
                    // If we're already capturing with a streaming-optimized source, upgrade to recording-optimized source.
                    maybeRestartForModeChangeLocked()
                    startCaptureIfNeeded()
                    return
                }

                listeners.add(listener)
                recordingRefCount++
                Log.d(TAG, "Recording listener registered, refCount=$recordingRefCount, totalListeners=${listeners.size}")
                // If we were previously capturing for streaming only, upgrade to recording-optimized source.
                maybeRestartForModeChangeLocked()
                startCaptureIfNeeded()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register recording listener", e)
            }
        }
    }
    
    /**
     * Unregister a listener for recording audio
     */
    fun unregisterRecordingListener(listener: AudioListener) {
        synchronized(this) {
            try {
                val removed = listeners.remove(listener)
                recordingRefCount--
                if (recordingRefCount < 0) recordingRefCount = 0
                Log.d(
                    TAG,
                    "Recording listener unregistered (removed=$removed), refCount=$recordingRefCount, totalListeners=${listeners.size}"
                )
                stopCaptureIfNeeded()
                // If recording just ended but streaming continues, switch back to streaming-optimized source.
                maybeRestartForModeChangeLocked()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister recording listener", e)
            }
        }
    }

    /**
     * Restart capture if our current AudioRecord source doesn't match the current mode.
     *
     * Modes: streaming-only (VOICE_COMMUNICATION+AEC), streaming+recording (VOICE_COMMUNICATION+AEC),
     * recording-only (CAMCORDER, no AEC). Restart when switching between recording-only and streaming+recording.
     */
    private fun maybeRestartForModeChangeLocked() {
        try {
            val shouldBeRecordingActive = recordingRefCount > 0
            val shouldBeStreamingAndRecording = streamingRefCount > 0 && recordingRefCount > 0
            if (!isRunning) {
                currentRecordingActive = shouldBeRecordingActive
                currentStreamingAndRecording = shouldBeStreamingAndRecording
                return
            }

            val modeChanged = currentRecordingActive != shouldBeRecordingActive ||
                currentStreamingAndRecording != shouldBeStreamingAndRecording
            if (modeChanged) {
                Log.w(
                    TAG,
                    "Audio capture mode change detected. Restarting AudioRecord. " +
                        "recordingActive=$currentRecordingActive->$shouldBeRecordingActive, " +
                        "streamingAndRecording=$currentStreamingAndRecording->$shouldBeStreamingAndRecording, " +
                        "currentSource=$currentAudioSource"
                )
                forceRestartCaptureLocked()
                currentRecordingActive = shouldBeRecordingActive
                currentStreamingAndRecording = shouldBeStreamingAndRecording
                startCaptureIfNeeded()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart audio capture for mode change", e)
        }
    }

    private fun forceRestartCaptureLocked() {
        // Stop loop
        isRunning = false
        try { captureThread?.join(500) } catch (_: Throwable) { }
        captureThread = null

        // Stop/release recorder
        try { audioRecord?.stop() } catch (_: Throwable) { }
        try { audioRecord?.release() } catch (_: Throwable) { }
        audioRecord = null

        currentAudioSource = -1
    }
    
    /**
     * Start audio capture if needed (based on reference counts)
     */
    private fun startCaptureIfNeeded() {
        synchronized(this) {
            if (isRunning) return
            if (streamingRefCount == 0 && recordingRefCount == 0) return
            
            val ctx = contextRef?.get()
            if (ctx == null) {
                Log.w(TAG, "Context not set, cannot start audio capture")
                return
            }
            
            // Check permission
            if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "RECORD_AUDIO permission not granted")
                return
            }
            
            try {
                val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                if (minBuf == AudioRecord.ERROR_BAD_VALUE || minBuf == AudioRecord.ERROR) {
                    Log.e(TAG, "Invalid buffer size: $minBuf")
                    return
                }
                
                // Use larger buffer for better quality (reduces dropouts)
                // 20ms at 48kHz mono 16-bit = 1920 bytes, use 3x for stability
                val bufferSize = minBuf.coerceAtLeast(1920 * 3)

                // CRITICAL: Audio source selection affects volume/processing a lot.
                // - When both streaming and recording: use VOICE_COMMUNICATION so AEC cancels PTT playback (avoids echo in recording).
                // - When recording-only: use CAMCORDER for better video recording volume.
                // - When streaming-only: use VOICE_COMMUNICATION for VoIP.
                val isRecordingActive = recordingRefCount > 0
                val isStreamingAndRecording = streamingRefCount > 0 && recordingRefCount > 0
                val preferredSources: IntArray = if (isStreamingAndRecording) {
                    // Streaming + recording: prefer VOICE_COMMUNICATION (AEC cancels Talkback/PTT echo)
                    intArrayOf(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        MediaRecorder.AudioSource.MIC
                    )
                } else if (isRecordingActive) {
                    // Recording-only: CAMCORDER for best video recording volume (no PTT echo)
                    intArrayOf(
                        MediaRecorder.AudioSource.CAMCORDER,
                        MediaRecorder.AudioSource.MIC,
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        MediaRecorder.AudioSource.UNPROCESSED
                    )
                } else {
                    intArrayOf(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        MediaRecorder.AudioSource.MIC
                    )
                }

                var created: AudioRecord? = null
                var chosenSource: Int? = null
                for (src in preferredSources) {
                    try {
                        val ar = AudioRecord(src, sampleRate, channelConfig, audioFormat, bufferSize)
                        // Basic sanity check: avoid uninitialized instances
                        if (ar.state != AudioRecord.STATE_INITIALIZED) {
                            try { ar.release() } catch (_: Throwable) { }
                            continue
                        }
                        created = ar
                        chosenSource = src
                        break
                    } catch (e: Exception) {
                        // Try next source
                        Log.w(TAG, "AudioRecord init failed for source=$src, trying next", e)
                    }
                }

                if (created == null || chosenSource == null) {
                    Log.e(TAG, "Failed to create AudioRecord with any source (recordingActive=$isRecordingActive, streamingAndRecording=$isStreamingAndRecording)")
                    return
                }

                audioRecord = created
                currentAudioSource = chosenSource
                currentRecordingActive = isRecordingActive
                currentStreamingAndRecording = isStreamingAndRecording
                Log.d(
                    TAG,
                    "AudioRecord created: source=$chosenSource/${audioSourceLabel(chosenSource)} " +
                        "(recordingActive=$isRecordingActive, streamingAndRecording=$isStreamingAndRecording), bufferSize=$bufferSize"
                )

                // Diagnostics: UNPROCESSED is notoriously low-gain on many devices.
                // We keep it as last resort; if it still gets selected, recording will rely on
                // CustomRecorder's software gain/limiter as a fallback.
                if (isRecordingActive && chosenSource == MediaRecorder.AudioSource.UNPROCESSED) {
                    Log.w(
                        TAG,
                        "Recording is using UNPROCESSED source; volume may be low on this device. " +
                            "Fallback software gain will be applied in recorder."
                    )
                }
                
                val recorder = audioRecord ?: return

                // Enable AEC/AGC/NS when streaming is active (including streaming+recording) so PTT/Talkback
                // playback is cancelled. When recording-only, effects stay off (CAMCORDER path).
                val useEffects = streamingRefCount > 0
                if (useEffects && android.media.audiofx.NoiseSuppressor.isAvailable()) {
                    try {
                        android.media.audiofx.NoiseSuppressor.create(recorder.audioSessionId).enabled = true
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed to enable NoiseSuppressor", e)
                    }
                }

                if (useEffects && android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                    try {
                        android.media.audiofx.AcousticEchoCanceler.create(recorder.audioSessionId).enabled = true
                        Log.d(TAG, "[AUDIO_DIAG] AcousticEchoCanceler enabled (streaming/streaming+recording)")
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed to enable AcousticEchoCanceler", e)
                    }
                } else if (!useEffects) {
                    Log.d(TAG, "[AUDIO_DIAG] AcousticEchoCanceler NOT enabled (recording-only, source=${audioSourceLabel(chosenSource)})")
                }

                if (useEffects && android.media.audiofx.AutomaticGainControl.isAvailable()) {
                    try {
                        android.media.audiofx.AutomaticGainControl.create(recorder.audioSessionId).enabled = true
                        Log.d(TAG, "[AUDIO_DIAG] AutomaticGainControl enabled (streaming/streaming+recording)")
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed to enable AutomaticGainControl", e)
                    }
                } else if (!useEffects) {
                    Log.d(TAG, "[AUDIO_DIAG] AutomaticGainControl NOT enabled (recording-only)")
                }
                
                recorder.startRecording()
                isRunning = true
                
                captureThread = Thread({ captureLoop() }, "AudioSourceEngine-Capture").apply {
                    isDaemon = true
                    start()
                }
                
                Log.d(TAG, "Audio capture started (streaming=$streamingRefCount, recording=$recordingRefCount)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start audio capture", e)
                try {
                    audioRecord?.release()
                } catch (_: Throwable) {}
                audioRecord = null
                isRunning = false
            }
        }
    }
    
    /**
     * Stop audio capture if no longer needed
     */
    private fun stopCaptureIfNeeded() {
        synchronized(this) {
            if (!isRunning) return
            if (streamingRefCount > 0 || recordingRefCount > 0) return
            
            isRunning = false
            
            try {
                captureThread?.join(500)
            } catch (e: InterruptedException) {
                Log.w(TAG, "Interrupted while waiting for capture thread", e)
            }
            
            try {
                audioRecord?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping AudioRecord", e)
            }
            
            try {
                audioRecord?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing AudioRecord", e)
            }
            
            audioRecord = null
            captureThread = null
            
            Log.d(TAG, "Audio capture stopped")
        }
    }
    
    /**
     * Main capture loop - reads PCM data and multicasts to listeners
     */
    private fun captureLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        
        // 20ms chunks at 48kHz = 1920 bytes (16-bit mono)
        // 48,000 samples/sec * 0.02 sec = 960 samples; 960 samples * 2 bytes = 1920 bytes.
        val buffer = ByteArray(1920)
        
        while (isRunning) {
            val recorder = audioRecord ?: break
            
            try {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val data = buffer.copyOfRange(0, read)
                    // #region agent log
                    val nowMs = android.os.SystemClock.uptimeMillis()
                    if (nowMs - lastCaptureDiagMs >= CAPTURE_DIAG_INTERVAL_MS) {
                        lastCaptureDiagMs = nowMs
                        var sumSq = 0.0
                        var peak = 0
                        var samples = 0
                        var idx = 0
                        while (idx + 1 < data.size) {
                            val lo = data[idx].toInt() and 0xFF
                            val hi = data[idx + 1].toInt()
                            val s = (hi shl 8) or lo
                            val v = s.toShort().toInt()
                            val av = kotlin.math.abs(v)
                            if (av > peak) peak = av
                            sumSq += (v * v).toDouble()
                            samples++
                            idx += 2
                        }
                        val rms = if (samples > 0) kotlin.math.sqrt(sumSq / samples) else 0.0
                        val src = currentAudioSource
                        Log.d(
                            TAG,
                            "[AUDIO_DIAG] capture: rms=${"%.1f".format(rms)} peak=$peak samples=$samples " +
                                "source=${audioSourceLabel(src)} recordingActive=$currentRecordingActive " +
                                "listeners=${listeners.size} streamingRef=$streamingRefCount recordingRef=$recordingRefCount"
                        )
                    }
                    // #endregion
                    // Multicast to all listeners
                    listeners.forEach { listener ->
                        try {
                            listener.onAudioData(data, sampleRate, 1)
                        } catch (e: Exception) {
                            Log.e(TAG, "Listener error", e)
                        }
                    }
                } else if (read == 0) {
                    Thread.sleep(2) // Brief pause if no data
                } else {
                    // Error or end of stream
                    Log.w(TAG, "AudioRecord.read returned $read")
                    break
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in capture loop", e)
                break
            }
        }
        
        Log.d(TAG, "Capture loop exited")
    }
    
    /**
     * Get current reference counts (for debugging)
     * @Suppress("UNUSED") - Reserved for future debugging/monitoring features
     */
    @Suppress("UNUSED")
    fun getRefCounts(): Pair<Int, Int> = Pair(streamingRefCount, recordingRefCount)
}
