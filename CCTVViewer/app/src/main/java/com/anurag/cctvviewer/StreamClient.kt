package com.anurag.cctvviewer

import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import android.media.AudioTrack
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioAttributes
import android.media.MediaRecorder
import android.os.Build
import kotlin.math.abs
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException




class StreamClient(
    private val port: Int,
    private val password: String,
    private val onStateChanged: (ConnectionState) -> Unit,
    private val onError: (String) -> Unit,
    private val onRotationChanged: (Int) -> Unit = {},
    private val onRecordingStateChanged: (Boolean) -> Unit = {},
    private val onVideoSizeChanged: (Int, Int) -> Unit = { _, _ -> },
    private val onVideoCropChanged: (Int, Int, Int, Int) -> Unit = { _, _, _, _ -> },
    private val onFirstFrameRendered: () -> Unit = {},
    private val onCameraFacingChanged: (Boolean) -> Unit = { },
    private val onCommunicationEnabledChanged: (Boolean) -> Unit = { }
) {

    // --- Lifecycle State ---
    @Volatile
    private var appInBackground = false

    /* ===============================
       * Connection state
       * =============================== */
    @Volatile
    private var running = true
    @Volatile
    private var reconnecting = false
    private var reconnectAttemptCount = 0
    private var lastReconnectUptimeMs = 0L
    @Volatile
    private var autoReconnect = true
    @Volatile
    private var host: String = ""
    private var socket: Socket? = null
    private var out: BufferedOutputStream? = null
    private val writeLock = Any()
    private var input: BufferedInputStream? = null
    private val inputLock = Any() // Lock for synchronizing input stream access
    private val mainHandler = Handler(Looper.getMainLooper())
    private var senderExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CCTV-Client-Sender").apply { isDaemon = true }
    }
    private val senderClosed = AtomicBoolean(false)
    private var heartbeatExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CCTV-Heartbeat").apply { isDaemon = true }
    }
    private var audioRecordExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CCTV-Audio-Talk").apply { isDaemon = true }
    }
    private var reconnectExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CCTV-Reconnect").apply { isDaemon = true }
    }
    private var connectExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CCTV-Connect").apply { isDaemon = true }
    }
    @Volatile private var heartbeatRunning = false

    // --- Recording/reconfigure tolerance window ---
    // Recording start can cause brief camera/encoder/stream pauses (no frames/audio) without implying a dead socket.
    // We keep the TCP socket open during this grace window and wait for the next keyframe/CSD.
    @Volatile private var reconfigureGraceUntilUptimeMs: Long = 0L

    private fun beginReconfigureGrace(reason: String, durationMs: Long = 45_000L) {
        val now = android.os.SystemClock.uptimeMillis()
        val until = now + durationMs
        // Extend but never shorten.
        if (until > reconfigureGraceUntilUptimeMs) {
            reconfigureGraceUntilUptimeMs = until
        }
        Log.w(logFrom, "[Stream Client] 🟡 [RECONFIG GRACE] start reason=$reason durationMs=$durationMs until=${reconfigureGraceUntilUptimeMs}")
    }

    private fun inReconfigureGrace(nowUptimeMs: Long): Boolean = nowUptimeMs < reconfigureGraceUntilUptimeMs

    /* ===============================
         * Stream / decode state
         * =============================== */
    private val decodeQueue = java.util.concurrent.ArrayBlockingQueue<IncomingFrame>(30)
    private var decodeExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
            r.run()
        }.apply { name = "CCTV-Decode"; isDaemon = true }
    }

    // CRITICAL FIX: Reusable scratch buffer for efficiently draining frames when decoding is paused
    // This prevents memory allocation churn during background mode (avoids GC pressure)
    // Buffer is grown on-demand to handle largest frame sizes (typically 200-300KB for keyframes)
    private var frameDiscardScratchBuffer = ByteArray(256 * 1024) // Start with 256KB, grow if needed
    @Volatile
    private var decodeRunning = false
    private var decoder: MediaCodec? = null
    private val decoderLock = Any()
    private var sessionId: String? = null
    private var csd0: ByteArray? = null
    private var csd1: ByteArray? = null
    // Stream epoch: increments on Primary whenever stream config/encoder is restarted.
    // Viewer must drop frames/CSD from old epochs to avoid mixing frames across reconfigure/reconnect.
    @Volatile private var streamEpoch: Long = 0L
    @Volatile private var rotationDeg: Int = 0
    @Volatile private var communicationEnabled: Boolean = true
    // Render scaling mode is fixed to FIT (no crop-to-fill feature).
    @Volatile
    private var outputSurface: android.view.Surface? = null
    @Volatile
    private var surfaceReady = false
    @Volatile
    private var waitingForKeyframe = true
    @Volatile
    private var currentState: ConnectionState = ConnectionState.DISCONNECTED

    // --- Stream health tracking ---
    // Used to avoid "stuck STREAMING" when the socket stays alive but capture stops (no frames).
    // Also helps diagnose "video not visible" by distinguishing "no frames received" vs "frames decoded but not visible".
    @Volatile private var connectedUptimeMs: Long = 0L
    @Volatile private var lastPongUptimeMs: Long = 0L
    @Volatile private var lastFrameRxUptimeMs: Long = 0L
    @Volatile private var lastFrameRenderUptimeMs: Long = 0L
    @Volatile private var lastAudioDownRxUptimeMs: Long = 0L

    // --- Adaptive jitter buffer (video) ---
    // Intent:
    // - Smooth micro-stutters by maintaining a small backlog of frames in the decode queue (2–4 frames).
    // - Adapt target backlog based on observed inter-arrival variability.
    //
    // Safety:
    // - Backward compatible and local-only (no protocol changes).
    // - If anything goes wrong, we auto-disable and fall back to low-latency behavior.
    @Volatile private var jitterBufferEnabled: Boolean = true
    @Volatile private var jitterTargetFrames: Int = JITTER_MIN_FRAMES
    @Volatile private var jitterEwmaMs: Double = 0.0
    @Volatile private var lastJitterRxUptimeMs: Long = 0L
    @Volatile private var lastJitterTargetLogUptimeMs: Long = 0L
    @Volatile private var lastJitterDropLogUptimeMs: Long = 0L

    // --- Protocol robustness: per-frame sequence numbers (optional, backward-compatible) ---
    // Primary emits: FRAME|epoch=...|seq=<long>|size=...|...
    // Older Primaries won't include seq; in that case seq will be -1 and gap detection is disabled.
    @Volatile private var lastVideoSeqEpoch: Long = -1L
    @Volatile private var lastVideoSeq: Long = -1L
    @Volatile private var videoSeqGapCount: Long = 0L
    @Volatile private var lastSeqGapLogMs: Long = 0L

    // --- Handshake / startup tracking ---
    // The Viewer can get stuck in AUTHENTICATED ("Starting stream…") if the negotiation messages
    // (STREAM_ACCEPTED/CSD/first keyframe) never arrive, or arrive out of order.
    // We track milestones and run a watchdog to retry negotiation and/or downgrade UI state.
    @Volatile private var lastAuthOkUptimeMs: Long = 0L
    @Volatile private var lastStreamAcceptedUptimeMs: Long = 0L
    @Volatile private var lastCsdUptimeMs: Long = 0L
    @Volatile private var handshakeRetryCount: Int = 0
    @Volatile private var lastHandshakeKickUptimeMs: Long = 0L
    // CONNECTED ("No Video") recovery tracking:
    // If the socket stays alive (PONGs) but frames never arrive (or stop arriving), we escalate:
    // - request keyframe (probe)
    // - re-negotiate (CAPS + SET_STREAM)
    // - disconnect to force a clean reconnect (last resort)
    @Volatile private var connectedRecoveryCount: Int = 0
    @Volatile private var lastConnectedRecoveryKickUptimeMs: Long = 0L
    
    // True only when the UI has confirmed that the preview is actually visible (SurfaceView PixelCopy stable,
    // or TextureView first frame). This is stricter than "decoder produced output".
    @Volatile private var previewVisible = false

    /**
     * Inform StreamClient whether the UI has confirmed that a real preview is visible.
     *
     * Purpose:
     * - Avoid bitrate/quality auto-adjustments during initial warmup where MediaCodec output may be green/unstable.
     * - Avoid unnecessary keyframe/renegotiation loops once preview is stable.
     */
    fun setPreviewVisible(visible: Boolean) {
        if (previewVisible == visible) return
        previewVisible = visible
        Log.d(logFrom, "[Stream Client] 🔍 [VIEW] setPreviewVisible($visible) (state=$currentState)")
    }

    /**
     * Returns a best-effort snapshot of stream health.
     *
     * Stability:
     * - No exceptions should escape this method; callers may poll it on the UI thread.
     * - Values are best-effort and may be slightly stale; they are only intended for UI messaging/diagnostics.
     */
    fun getHealthSnapshot(): StreamHealthSnapshot {
        val now = android.os.SystemClock.uptimeMillis()
        val queueSize = try {
            decodeQueue.size
        } catch (_: Throwable) {
            -1
        }
        return try {
            StreamHealthSnapshot(
                nowUptimeMs = now,
                connectionState = currentState,
                decodeRunning = decodeRunning,
                surfaceReady = surfaceReady,
                waitingForKeyframe = waitingForKeyframe,
                lastPongUptimeMs = lastPongUptimeMs,
                lastFrameRxUptimeMs = lastFrameRxUptimeMs,
                lastFrameRenderUptimeMs = lastFrameRenderUptimeMs,
                lastAudioDownRxUptimeMs = lastAudioDownRxUptimeMs,
                decodeQueueSize = queueSize,
                rxOverloadDropCount = rxOverloadDropCount,
                previewVisible = previewVisible
            )
        } catch (t: Throwable) {
            Log.w(logFrom, "[STREAM CLIENT] ⚠️ [HEALTH] getHealthSnapshot() failed (non-fatal)", t)
            StreamHealthSnapshot(
                nowUptimeMs = now,
                connectionState = currentState,
                decodeRunning = decodeRunning,
                surfaceReady = surfaceReady,
                waitingForKeyframe = waitingForKeyframe,
                lastPongUptimeMs = lastPongUptimeMs,
                lastFrameRxUptimeMs = lastFrameRxUptimeMs,
                lastFrameRenderUptimeMs = lastFrameRenderUptimeMs,
                lastAudioDownRxUptimeMs = lastAudioDownRxUptimeMs,
                decodeQueueSize = queueSize,
                rxOverloadDropCount = rxOverloadDropCount,
                previewVisible = previewVisible
            )
        }
    }

    @Volatile
    private var postedFirstFrameRendered = false
    /**
     * Tracks the first time we attempted to render decoder output to a valid Surface after startup/reconfigure.
     *
     * Purpose:
     * - Some devices (and especially SurfaceView rendering) do not give us a reliable callback that a frame
     *   was actually displayed. If we wait forever, the UI can get stuck on "Starting stream".
     * - We use this as a bounded fallback timer: after we've rendered for a short time *and warmup gates
     *   are cleared*, we force-reveal the UI to avoid permanent "Starting stream".
     */
    private var firstRenderAttemptUptimeMs: Long = 0L

    // Guard to prevent green/garbled frames:
    // Do not render decoder output until we've actually queued a keyframe into the decoder.
    // Some devices output "junk" frames (or stale buffers) before the first IDR is processed.
    @Volatile
    private var queuedKeyframeSinceReset = false

    // Device-specific mitigation:
    // OnePlus Nord CE4 devices can occasionally render a solid green frame when the decoder begins output
    // immediately after a keyframe / restart. We "warm up" by dropping a few output buffers after the
    // first queued keyframe, and also by ensuring we never render output whose PTS is older than the
    // first queued keyframe PTS after a reset.
    private val isNordCe4Device: Boolean = DeviceQuirks.needsGreenFrameWarmupMitigation()

    @Volatile
    private var firstQueuedKeyframePtsUsSinceReset: Long = -1L

    @Volatile
    private var nordCe4OutputWarmupDropsRemaining: Int = 0

    // Nord CE4-specific:
    // Even after an IDR is queued, some devices briefly output "green/uninitialized" frames for a short
    // period. We therefore suppress *render=true* until a short time window has passed after the first
    // queued keyframe. This reduces the chance of showing a green flash on first connect.
    @Volatile private var nordCe4SuppressRenderUntilUptimeMs: Long = 0L

    // Nord CE4-specific:
    // "First rendered frame" can still be green. Delay the UI reveal until we have rendered a few
    // frames after warmup gates are cleared.
    @Volatile private var nordCe4RenderedFramesAfterWarmup: Int = 0

    @Volatile
    private var activeWidth = 720

    @Volatile
    private var activeHeight = 1440

    // Track whether we've informed the UI about the decoder's authoritative output size yet.
    // Without this, if the decoder output size matches the negotiated size, we might never post
    // onVideoSizeChanged(), leaving UI stuck on its default placeholder dimensions.
    @Volatile private var postedDecoderFormatSize = false

    // Track negotiated resolution separately from decoder/active resolution to avoid loops
    @Volatile private var lastAcceptedWidth = 0
    @Volatile private var lastAcceptedHeight = 0

    /**
     * Returns the last STREAM_ACCEPTED dimensions (server truth) if known.
     *
     * Contract:
     * - MediaCodec must be configured using STREAM_ACCEPTED dimensions.
     * - The Viewer may *request* a different size (e.g. 480x640), but the Primary may override and keep
     *   streaming at the encoder's actual size (e.g. 720x960). If we configure using the requested size,
     *   we can get a mismatch + reset loop.
     */
    private fun getAcceptedDimsOrNull(): Pair<Int, Int>? {
        val w = lastAcceptedWidth
        val h = lastAcceptedHeight
        return if (w > 0 && h > 0) (w to h) else null
    }

    // Track the decoder's *configured* dimensions (what we passed to MediaCodec.configure()).
    // IMPORTANT:
    // - This is NOT the same as negotiated STREAM_ACCEPTED size, and NOT necessarily the same as
    //   MediaCodec outputFormat (which may change after SPS is parsed).
    // - We use this to detect the "decoder already exists but was configured with stale/default dims"
    //   case that can produce a green/uninitialized Surface until reconnect on some devices.
    @Volatile private var decoderConfiguredWidth = 0
    @Volatile private var decoderConfiguredHeight = 0

    // Adaptive downgrade
    @Volatile private var downgraded = false
    @Volatile private var skipCount = 0

    /* ===============================
        * Backpressure
        * =============================== */
    private var lateFrameCount = 0
    private var lastBackpressureSignalUs = 0L

    @Volatile
    private var pressureActive = false

    /* ===============================
   * Stream request tracking
   * =============================== */
    private var lastRequestedConfig: StreamConfig? = null
    // Tracks what we *sent* in SET_STREAM. This may differ from STREAM_ACCEPTED (server truth).
    @Volatile private var lastSentSetStreamConfig: StreamConfig? = null
    // If the server overrides requested resolution, stop trying resolution changes (bitrate-only).
    @Volatile private var serverHonorsResolutionRequests: Boolean = true
    private val logFrom = "CCTV_VIEWER"
    private var lastKeyReqNs = 0L
    @Volatile private var lastLatencyLogUs = 0L
    @Volatile private var lastSkipNonKeyLogMs: Long = 0L
    // Socket RX-side guard: while we are waiting for the very first IDR after a reset,
    // drop all non-key frames as early as possible. This prevents stale "pre-IDR" P-frames
    // from ever reaching the decode loop (which can cause green/garbled output on some devices).
    @Volatile private var droppedNonKeyWhileWaitingCount: Int = 0
    @Volatile private var lastDropNonKeyWhileWaitingLogMs: Long = 0L
    @Volatile private var clockOffsetMs = 0.0
    @Volatile private var clockSynced = false

    // --- Decoder recovery / overload protection ---
    // Count of frames dropped at receive-time due to decode queue overload.
    @Volatile private var rxOverloadDropCount: Long = 0L

    /* Profile selection (adaptive based on device hints) */
    // For "try high first, then fall back": once we detect this device can't keep up at a given profile,
    // remember the fallback so future connects start smoother immediately (and persist via prefs).
    @Volatile private var startProfileOverride: StreamProfile? = null
    @Volatile private var profileOverrideLoader: (() -> StreamConfig?)? = null
    @Volatile private var profileOverrideSaver: ((StreamConfig?) -> Unit)? = null

    fun setStartProfileOverridePersistence(
        load: () -> StreamConfig?,
        save: (StreamConfig?) -> Unit
    ) {
        profileOverrideLoader = load
        profileOverrideSaver = save
    }

    private fun selectProfile(): StreamProfile {
        // Only use persisted override if it's a high-quality profile (not a downgrade)
        // This prevents getting stuck at 480x640 from previous sessions
        if (startProfileOverride == null) {
            profileOverrideLoader?.invoke()?.let { cfg ->
                // Only use persisted profile if it's >= 720x960 (not a downgrade)
                if (cfg.width >= 720 && cfg.height >= 960 && cfg.bitrate > 0 && cfg.fps > 0) {
                    startProfileOverride = StreamProfile(cfg.width, cfg.height, cfg.bitrate, cfg.fps)
                    Log.d(logFrom, "[Stream Client] [STREAM CLIENT] Using persisted profile: ${cfg.width}x${cfg.height}@${cfg.fps}")
                } else {
                    // Clear invalid/low-quality persisted profile
                    profileOverrideSaver?.invoke(null)
                    Log.d(logFrom, "[Stream Client] [STREAM CLIENT] Cleared low-quality persisted profile: ${cfg.width}x${cfg.height}")
                }
            }
        }
        startProfileOverride?.let { return it }

        // Capability-ish start profile selection (avoid model hardcoding):
        // - Prefer Android's Media Performance Class as the primary hint (Android 12+).
        // - Fall back to coarse CPU/memory heuristics.
        val perfClass = if (Build.VERSION.SDK_INT >= 31) Build.VERSION.MEDIA_PERFORMANCE_CLASS else 0
        val cores = Runtime.getRuntime().availableProcessors()
        val memMb = (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toInt()
        val lowTier = memMb < 1500 || cores <= 4
        val highTier = perfClass >= 31 || (memMb >= 2500 && cores >= 6)

        // Strategy:
        // - High tier: start at 1080x1440@30 5Mbps.
        // - Low tier: start at 720x960@30 3Mbps.
        // - Mid: start at 1080x1440@30 5Mbps (and let runtime adaptation/persistence handle downgrades).
        return if (highTier && !lowTier) {
            StreamProfile(1080, 1440, 5_000_000, 30)
        } else if (lowTier) {
            StreamProfile(720, 960, 3_000_000, 30)
        } else {
            StreamProfile(1080, 1440, 5_000_000, 30)
        }
    }

    /* ===============================
        * Audio
        * =============================== */
    @Volatile private var audioTrack: AudioTrack? = null
    // Viewer audio should be muted by default until user enables it.
    // Default unmuted so users hear stream audio without extra steps.
    @Volatile private var audioMuted: Boolean = false
    @Volatile private var talkActive = false
    private val minRmsGate = 200 // simple noise gate to drop low-level noise
    // Talkback gain hint (viewer mic -> primary). Updated by the AudioRecord loop; applied during send.
    // Keep conservative caps to avoid clipping/distortion in transit.
    @Volatile private var talkUpGain: Float = 1.0f
    @Volatile private var lastTalkUpLogUptimeMs: Long = 0L

    @Volatile private var lastAudioDownNs = 0L
    @Volatile private var firstAudioDownNs = 0L
    @Volatile private var noiseFloorRms = 0.0
    @Volatile private var noiseCalibrating = false
    @Volatile private var noiseCalStartNs = 0L
    @Volatile private var noiseCalSamples = 0
    
    // AAC decoder for compressed audio
    private var audioDecoder: MediaCodec? = null
    private val audioDecoderLock = Any()
    private val aacInputQueue = LinkedBlockingQueue<AacFrame>(20)
    private var audioDecoderThread: Thread? = null
    @Volatile private var audioDecoding = false
    
    // Audio playback queue and executor to offload AudioTrack.write() from network loop
    private val audioPlaybackQueue = LinkedBlockingQueue<AudioPacket>(80) // Buffer ~3 second of audio for jitter tolerance
    private var audioPlaybackExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CCTV-Audio-Playback").apply { isDaemon = true }
    }
    @Volatile private var audioPlaybackRunning = false
    @Volatile private var latestPlayedAudioTsUs: Long = 0L

    // Render FPS tracking (approx: number of output buffers released to surface)
    @Volatile private var renderWindowStartMs = 0L
    @Volatile private var renderFramesInWindow = 0
    // 0 = none, 1 = requested 720x960, 2 = requested 480x640
    @Volatile private var perfLevel = 0

    /* ===============================
        * Public API
        * =============================== */
    fun updateHost(newHost: String) {
        host = newHost.trim()
    }

    private fun mapExceptionToUserMessage(e: Throwable, phase: String): String {
        // Keep messages user-facing and actionable; do not leak low-level exception strings.
        // We still log full stacktraces at call sites.
        return when (e) {
            is SocketTimeoutException -> {
                "Connection timed out. Please check the server IP and make sure both devices are on the same network."
            }
            is UnknownHostException -> {
                "Invalid server IP address. Please verify the IP and try again."
            }
            is NoRouteToHostException -> {
                "Network unreachable. Please check Wi‑Fi/data connection and try again."
            }
            is ConnectException -> {
                "Connection refused. Please make sure the Primary app is running and the port is correct."
            }
            is SocketException -> {
                // SocketExceptions are common during background/foreground churn.
                when {
                    e.message?.contains("reset", ignoreCase = true) == true ->
                        "Connection was reset. Please try again."
                    e.message?.contains("closed", ignoreCase = true) == true ->
                        "Connection closed."
                    else ->
                        "Network error. Please check your connection and try again."
                }
            }
            is IOException -> {
                "Connection lost. Please check your network and try again."
            }
            else -> {
                "Connection failed during $phase. Please check IP/port and try again."
            }
        }
    }

    fun connect() {
        // User requested connection; allow reconnect logic again.
        autoReconnect = true

        // IMPORTANT: Reset decode/render state for a fresh connection attempt.
        // This prevents a stale "green surface" / non-starting decoder scenario if previous state leaked.
        try {
            decodeQueue.clear()
        } catch (_: Throwable) {
        }
        resetAdaptiveJitterState("connect")
        waitingForKeyframe = true
        droppedNonKeyWhileWaitingCount = 0
        lastDropNonKeyWhileWaitingLogMs = 0L
        postedFirstFrameRendered = false
        queuedKeyframeSinceReset = false
        firstQueuedKeyframePtsUsSinceReset = -1L
        nordCe4OutputWarmupDropsRemaining = 0
        
        // Ensure executors are available (they may have been shut down previously)
        synchronized(this) {
            if (connectExecutor.isShutdown) {
                connectExecutor = Executors.newSingleThreadExecutor { r ->
                    Thread(r, "CCTV-Connect").apply { isDaemon = true }
                }
            }
            if (decodeExecutor.isShutdown) {
                decodeExecutor = Executors.newSingleThreadExecutor { r ->
                    Thread {
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
                        r.run()
                    }.apply { name = "CCTV-Decode"; isDaemon = true }
                }
            }
            if (heartbeatExecutor.isShutdown) {
                heartbeatExecutor = Executors.newSingleThreadExecutor { r ->
                    Thread(r, "CCTV-Heartbeat").apply { isDaemon = true }
                }
            }
            if (audioRecordExecutor.isShutdown) {
                audioRecordExecutor = Executors.newSingleThreadExecutor { r ->
                    Thread(r, "CCTV-Audio-Talk").apply { isDaemon = true }
                }
            }
            if (reconnectExecutor.isShutdown) {
                reconnectExecutor = Executors.newSingleThreadExecutor { r ->
                    Thread(r, "CCTV-Reconnect").apply { isDaemon = true }
                }
            }
            if (senderExecutor.isShutdown) {
                senderExecutor = Executors.newSingleThreadExecutor { r ->
                    Thread(r, "CCTV-Client-Sender").apply { isDaemon = true }
                }
                senderClosed.set(false)
            }
            if (audioPlaybackExecutor.isShutdown) {
                audioPlaybackExecutor = Executors.newSingleThreadExecutor { r ->
                    Thread(r, "CCTV-Audio-Playback").apply { isDaemon = true }
                }
            }
        }
        
        postState(ConnectionState.CONNECTING)

        connectExecutor.execute {
            try {
                val targetHost = host.trim()
                if (targetHost.isEmpty()) {
                    postError("Please enter a valid server IP address before connecting.")
                    postState(ConnectionState.DISCONNECTED)
                    return@execute
                }

                val s = Socket()
                s.connect(InetSocketAddress(targetHost, port), CONNECT_TIMEOUT_MS)
                s.soTimeout = SOCKET_READ_TIMEOUT_MS
                s.tcpNoDelay = true
                // Favor latency over throughput buffering (still TCP).
                try { s.receiveBufferSize = 256 * 1024 } catch (_: Throwable) {}
                try { s.sendBufferSize = 256 * 1024 } catch (_: Throwable) {}
                socket = s
                out = BufferedOutputStream(s.getOutputStream(), 256 * 1024)
                input = BufferedInputStream(s.getInputStream())
                connectedUptimeMs = android.os.SystemClock.uptimeMillis()
                lastPongUptimeMs = connectedUptimeMs
                lastFrameRxUptimeMs = 0L
                lastFrameRenderUptimeMs = 0L
                lastAudioDownRxUptimeMs = 0L
                lastAuthOkUptimeMs = 0L
                lastStreamAcceptedUptimeMs = 0L
                lastCsdUptimeMs = 0L
                handshakeRetryCount = 0
                lastHandshakeKickUptimeMs = 0L

                startDecodeLoop()
                startHeartbeat()
                startAudioPlayback()

                send("HELLO|client=viewer|version=1")
                // AUTH is now handled via CHAP (wait for AUTH_CHALLENGE)


                listen()
            } catch (e: java.net.SocketTimeoutException) {
                Log.w(logFrom, "[Stream Client] Connection timed out", e)
                postError(mapExceptionToUserMessage(e, "connect"))
                postState(ConnectionState.DISCONNECTED)
            } catch (e: Exception) {
                Log.w(logFrom, "[Stream Client] Connection failed: ${e.javaClass.simpleName}", e)
                postError(mapExceptionToUserMessage(e, "connect"))
                postState(ConnectionState.DISCONNECTED)
            }
        }
    }

    fun disconnect() {
        // User requested disconnect; do not auto-reconnect.
        autoReconnect = false
        try { setPreviewVisible(false) } catch (_: Throwable) {}
        stopHeartbeat()
        stopDecodeLoop()
        stopAudioPlayback()
        releaseAudioTrack()
        closeSocket()
        resetAdaptiveJitterState("disconnect")
        // Note: Do NOT shutdown executors here - they may be needed for reconnection.
        // Executors are only shut down in shutdown() which is called on app disposal.

        postState(ConnectionState.DISCONNECTED)
    }

    /**
     * Internal recovery disconnect.
     *
     * IMPORTANT:
     * - Must NOT disable autoReconnect (unlike user-initiated disconnect()).
     * - Used by watchdogs and IO failures to force a clean reconnect.
     */
    private fun disconnectForRecovery(reason: String) {
        try {
            Log.w(logFrom, "[Stream Client] 🔴 [RECOVERY] disconnectForRecovery(reason=$reason, autoReconnect=$autoReconnect)")
            try { setPreviewVisible(false) } catch (_: Throwable) {}
            stopHeartbeat()
            stopDecodeLoop()
            stopAudioPlayback()
            releaseAudioTrack()
            closeSocket()
            resetAdaptiveJitterState("recover:$reason")
            postState(ConnectionState.DISCONNECTED)
        } finally {
            if (autoReconnect) {
                triggerReconnect()
            }
        }
    }

    fun onAppBackgrounded() {
        if (appInBackground) return
        appInBackground = true
        Log.d(logFrom, "[Stream Client] 🟡 [VIEWER] App backgrounded – closing socket")
        // IMPORTANT: close socket intentionally to prevent "Connection reset" on server
        // and to save battery/data while valid resources (AudioTrack/MediaCodec) are invalid.
        closeSocket()
    }

    fun onAppForegrounded() {
        if (!appInBackground) return
        appInBackground = false
        Log.d(logFrom, "[Stream Client] 🟢 [VIEWER] App foregrounded – reconnecting")
        // Reuse existing reconnect path
        connect()
    }

    // (Removed setCropToFill) — scaling is always SCALE_TO_FIT.

    fun setAudioMuted(muted: Boolean) {
        audioMuted = muted
        if (muted) {
            // Stop any residual output immediately when user mutes.
            releaseAudioTrack()
            firstAudioDownNs = 0L
            lastAudioDownNs = 0L
            noiseFloorRms = 0.0
            noiseCalibrating = false
            noiseCalStartNs = 0L
            noiseCalSamples = 0
        } else {
            // Start calibrating a noise floor when user unmutes.
            val now = System.nanoTime()
            firstAudioDownNs = 0L
            lastAudioDownNs = 0L
            noiseFloorRms = 0.0
            noiseCalibrating = true
            noiseCalStartNs = now
            noiseCalSamples = 0
        }
    }

    private fun stopDecodeLoop() {
        decodeRunning = false
        decodeQueue.clear()
        resetDecoderInternal()
        // Executor shutdown handled in disconnect()
    }

    @Volatile
    private var isShutdown = false

    fun shutdown() {
        // CRITICAL FIX: Make shutdown() idempotent to prevent issues from duplicate calls
        // This is important because DisposableEffect may call shutdown() multiple times
        // or shutdown() may be called from multiple places (e.g., lifecycle callbacks)
        if (isShutdown) {
            Log.d(logFrom, "[Stream Client] 🔵 [SHUTDOWN] shutdown() already called, skipping (idempotent)")
            return
        }
        
        synchronized(this) {
            // Double-check pattern to ensure thread safety
            if (isShutdown) {
                Log.d(logFrom, "[Stream Client] 🔵 [SHUTDOWN] shutdown() already called (double-check), skipping")
                return
            }
            isShutdown = true
        }
        
        Log.d(logFrom, "[Stream Client] 🔵 [SHUTDOWN] Shutting down StreamClient - cleaning up resources")
        running = false
        autoReconnect = false
        stopHeartbeat()
        stopDecodeLoop()
        stopAudioPlayback()
        releaseAudioDecoder()
        closeSocket()
        closeSender()
        stopTalk()
        releaseAudioTrack()
        
        // Shutdown executors only on final shutdown (app disposal)
        // CRITICAL FIX: Check if executors are already shut down to avoid warnings
        try {
            if (!decodeExecutor.isShutdown) decodeExecutor.shutdownNow()
            if (!heartbeatExecutor.isShutdown) heartbeatExecutor.shutdownNow()
            if (!audioRecordExecutor.isShutdown) audioRecordExecutor.shutdownNow()
            if (!audioPlaybackExecutor.isShutdown) audioPlaybackExecutor.shutdownNow()
            if (!reconnectExecutor.isShutdown) reconnectExecutor.shutdownNow()
            if (!connectExecutor.isShutdown) connectExecutor.shutdownNow()
            if (!senderExecutor.isShutdown) senderExecutor.shutdownNow()
            Log.d(logFrom, "[Stream Client] 🔵 [SHUTDOWN] All executors shut down successfully")
        } catch (e: Exception) {
            Log.w(logFrom, "[Stream Client] ⚠️ [SHUTDOWN] Executor shutdown error (non-fatal)", e)
        }
        
        Log.d(logFrom, "[Stream Client] 🔵 [SHUTDOWN] StreamClient shutdown completed")
    }

    /* ===============================
 * Protocol handling
 * =============================== */
    private fun clearDecodeQueue() {
        val dropList = ArrayList<IncomingFrame>()
        decodeQueue.drainTo(dropList)
        dropList.forEach { it.recycle() }
    }

    private fun listen() {
        try {
            Log.d(logFrom, "🔵 [SOCKET] Starting listen loop - waiting for messages from server")
            while (true) {
                // Check if socket is still valid before attempting to read
                val currentSocket = socket
                if (currentSocket == null || currentSocket.isClosed) {
                    Log.d(logFrom, "🔵 [SOCKET] Socket is closed, exiting listen loop")
                    break
                }
                
                val line = readLineFromSocket() ?: break
                // Check if this looks like binary data (contains non-printable characters)
                val isBinary = line.any { it.code < 32 && it != '\r' && it != '\n' && it != '\t' }
                if (isBinary && !line.startsWith("FRAME|") && !line.startsWith("CSD|") && !line.startsWith("AUDIO_FRAME|")) {
                    Log.w(logFrom, "🔴 [SOCKET] Received binary data as text line! This indicates a protocol error. Line length=${line.length}, first 50 chars (hex): ${line.take(50).map { String.format("%02X", it.code) }.joinToString(" ")}")
                    Log.w(logFrom, "🔴 [SOCKET] This usually means a previous FRAME|/CSD|/AUDIO_FRAME| handler didn't read the binary data correctly")
                    // Try to recover by skipping this corrupted line and continuing
                    continue
                }
                Log.d(logFrom, " 🔵 [SOCKET] Received line from server: ${line.take(50)}${if (line.length > 50) "..." else ""}")
                when {
                    line.startsWith("AUTH_CHALLENGE|") -> {
                        val params = parseParams(line)
                        val salt = params["salt"]
                        if (salt != null) {
                            val response = hmacSha256(password, salt)
                            send("AUTH_RESPONSE|hash=$response")
                        } else {
                            Log.w(logFrom, "[Stream Client] AUTH_CHALLENGE missing salt")
                        }
                    }
                    line == "AUTH_OK" -> {

                        Log.d(logFrom, "[Stream Client] Authenticated successfully")
                        // Handshake milestone.
                        lastAuthOkUptimeMs = android.os.SystemClock.uptimeMillis()
                        handshakeRetryCount = 0
                        lastHandshakeKickUptimeMs = 0L
                        // Reset reconnect backoff on successful authentication
                        reconnectAttemptCount = 0
                        lastReconnectUptimeMs = 0L
                        // Only post AUTHENTICATED if decoder hasn't started yet
                        // If decoder is already streaming, don't overwrite STREAMING state
                        synchronized(decoderLock) {
                            val decoderExists = decoder != null
                            if (!decoderExists) {
                                Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] AUTH_OK received, decoder doesn't exist - posting AUTHENTICATED state")
                                postState(ConnectionState.AUTHENTICATED)
                            } else {
                                Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] AUTH_OK received but decoder already exists - skipping AUTHENTICATED state (already STREAMING)")
                            }
                        }
                        sessionId?.let {
                            send("RESUME|session=$it")
                            // Ask for a fresh keyframe immediately to avoid "black/green until reconnect" on some devices.
                            requestKeyframe("auth_ok_resume")
                        } ?: run {
                            val p = selectProfile()
                            sendCapabilities(
                                maxWidth = p.w,
                                maxHeight = p.h,
                                maxBitrate = p.bitrate
                            )
                            sendSetStream(StreamConfig(p.w, p.h, p.bitrate, p.fps))
                            // Ask for a fresh keyframe immediately after starting the stream.
                            requestKeyframe("auth_ok_set_stream")
                        }
                    }
                    line == "AUTH_FAIL" -> {
                        Log.w(logFrom, "[Stream Client] Authentication failed - incorrect password")
                        // Disable auto-reconnect on auth failure so user can retry with different password
                        autoReconnect = false
                        postError("Authentication failed. Please check the password and try again.")
                        postState(ConnectionState.DISCONNECTED)
                        // Close connection gracefully (server already closed it, but we clean up)
                        closeSocket()
                        stopHeartbeat()
                        // Break out of the listen loop - don't trigger reconnect
                        return
                    }
                    line.startsWith("ERROR|") -> {
                        // Primary can send explicit protocol errors (e.g., CAPS required before SET_STREAM).
                        Log.w(logFrom, "[Stream Client] Server error: $line")
                        val p = parseParams(line)
                        val reason = p["reason"] ?: line.substringAfter("reason=").substringBefore("|").trim()
                        if (reason == "caps_required") {
                            // Retry negotiation deterministically.
                            val prof = selectProfile()
                            sendCapabilities(
                                maxWidth = prof.w,
                                maxHeight = prof.h,
                                maxBitrate = prof.bitrate
                            )
                            sendSetStream(StreamConfig(prof.w, prof.h, prof.bitrate, prof.fps))
                            requestKeyframe("server_caps_required")
                        }
                    }
                    line == "PONG" || line.startsWith("PONG|") -> {
                        val nowUptimeMs = android.os.SystemClock.uptimeMillis()
                        val sinceLastPong = if (lastPongUptimeMs > 0L) (nowUptimeMs - lastPongUptimeMs) else Long.MAX_VALUE
                        lastPongUptimeMs = nowUptimeMs
                        // CRITICAL DIAGNOSTIC: Log PONG receipt to help diagnose heartbeat issues
                        Log.d(logFrom, "[Stream Client] 🔵 [HEARTBEAT] PONG received: sinceLastPong=${sinceLastPong}ms, state=$currentState")
                        // Heartbeat ack (Primary uses this to keep the session alive).
                        // If it contains timestamps, use it to estimate server<->client clock offset.
                        if (line.startsWith("PONG|")) {
                            val p = parseParams(line)
                            val clientTs = p["tsMs"]?.toLongOrNull()
                            val srvMs = p["srvMs"]?.toLongOrNull()
                            if (clientTs != null && srvMs != null) {
                                val nowMs = System.currentTimeMillis()
                                val rttMs = (nowMs - clientTs).coerceAtLeast(0L)
                                // Estimate: server time at client receive ~= srvMs + rtt/2
                                val estSrvAtNow = srvMs + (rttMs / 2.0)
                                clockOffsetMs = estSrvAtNow - nowMs.toDouble()
                                clockSynced = true
                                Log.d(logFrom, "[Stream Client] 🔵 [HEARTBEAT] PONG with timestamps: rtt=${rttMs}ms, clockOffset=${clockOffsetMs}ms")
                            }
                        }
                    }

                    line.startsWith("SESSION|") -> {
                        sessionId = line.substringAfter("id=").trim()
                        Log.d(logFrom, "[Stream Client] Session ID assigned: $sessionId")
                    }

                    line.startsWith("RESUME_OK") -> {
                        Log.d(logFrom, "[Stream Client] Resume OK")
                    }

                    line.startsWith("RESUME_FAIL") -> {
                        Log.w(logFrom, "[Stream Client] Resume failed; renegotiating stream")
                        sessionId = null
                        val p = selectProfile()
                        sendCapabilities(
                            maxWidth = p.w,
                            maxHeight = p.h,
                            maxBitrate = p.bitrate
                        )
                        sendSetStream(StreamConfig(p.w, p.h, p.bitrate, p.fps))
                    }

                    line.startsWith("CSD|") -> {
                        lastCsdUptimeMs = android.os.SystemClock.uptimeMillis()
                        val parts = parseParams(line)
                        val msgEpoch = parts["epoch"]?.toLongOrNull() ?: 0L
                        val spsSize = parts["sps"]?.toIntOrNull() ?: 0
                        val ppsSize = parts["pps"]?.toIntOrNull() ?: 0
                        if (spsSize > 0 && ppsSize > 0) {
                            try {
                                val sps = ByteArray(spsSize)
                                val pps = ByteArray(ppsSize)
                                readFullyFromSocket(sps)
                                readFullyFromSocket(pps)
                                // Receiving CSD while video is stalled (audio/PONG may still be flowing) typically indicates
                                // a recording-triggered camera/encoder rebind. Enter a longer tolerance window so we don't
                                // disconnect just because frames are absent for tens of seconds.
                                try {
                                    val nowUptime = android.os.SystemClock.uptimeMillis()
                                    val lastActivity = maxOf(lastFrameRxUptimeMs, lastFrameRenderUptimeMs)
                                    val sinceLast = if (lastActivity > 0L) (nowUptime - lastActivity) else Long.MAX_VALUE
                                    if (sinceLast >= 2_000L || currentState == ConnectionState.RECOVERING || currentState == ConnectionState.CONNECTED) {
                                        beginReconfigureGrace("rx_csd", 120_000L)
                                    }
                                } catch (_: Throwable) {
                                }
                                // Epoch handling:
                            // - If we haven't seen STREAM_ACCEPTED yet, we still store epoch so frames can be gated.
                            // - If epoch changes, treat this as a hard reset of decode state (new config).
                            if (msgEpoch > 0L && msgEpoch != streamEpoch) {
                                Log.w(logFrom, "[Stream Client] 🔵 [EPOCH] CSD epoch changed: $streamEpoch -> $msgEpoch (forcing decoder/keyframe reset)")
                                streamEpoch = msgEpoch
                                // New epoch: clear accepted dims until STREAM_ACCEPTED arrives.
                                lastAcceptedWidth = 0
                                lastAcceptedHeight = 0
                                waitingForKeyframe = true
                                postedFirstFrameRendered = false
                                firstRenderAttemptUptimeMs = 0L
                                droppedNonKeyWhileWaitingCount = 0
                                lastDropNonKeyWhileWaitingLogMs = 0L
                                queuedKeyframeSinceReset = false
                                firstQueuedKeyframePtsUsSinceReset = -1L
                                nordCe4OutputWarmupDropsRemaining = 0
                                nordCe4SuppressRenderUntilUptimeMs = 0L
                                nordCe4RenderedFramesAfterWarmup = 0
                                clearDecodeQueue()
                                try { resetDecoderInternal() } catch (_: Throwable) {}
                            }
                            // IMPORTANT:
                            // Primary may broadcast CSD multiple times even when SPS/PPS didn't change.
                            // If we treat duplicate CSD as a reconfigure, we will keep resetting
                            // waitingForKeyframe / warmup and the UI can get stuck on "Starting stream".
                            val prevSps = csd0
                            val prevPps = csd1
                            val csdUnchanged =
                                prevSps != null &&
                                    prevPps != null &&
                                    prevSps.contentEquals(sps) &&
                                    prevPps.contentEquals(pps)
                            if (csdUnchanged && (msgEpoch == 0L || msgEpoch == streamEpoch)) {
                                Log.d(
                                    logFrom,
                                    "🔍 [CSD DIAGNOSTIC] Duplicate CSD received (unchanged SPS/PPS) - ignoring to avoid unnecessary decoder/keyframe resets"
                                )
                                // Fallback: if we are already waiting for an IDR, keep nudging the encoder.
                                if (waitingForKeyframe) {
                                    requestKeyframe("csd_duplicate_waiting_for_idr")
                                }
                                // IMPORTANT: Do NOT break the socket listen loop here.
                                // We only want to skip handling this duplicate CSD message and keep reading.
                                continue
                            }

                            csd0 = sps
                            csd1 = pps
                            Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] Received encoder config (csd): sps=$spsSize, pps=$ppsSize")
                            val spsHex = sps.take(8).joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }
                            val ppsHex = pps.take(8).joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }
                            Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] CSD received - SPS (first 8 bytes): $spsHex, PPS (first 8 bytes): $ppsHex")
                            Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] Current state: surfaceReady=$surfaceReady, lastRequestedConfig=${lastRequestedConfig?.let { "${it.width}x${it.height}" } ?: "null"}, activeWidth=$activeWidth, activeHeight=$activeHeight")
                            waitingForKeyframe = true
                            // Reset RX/decoder gating so we never render stale output after SPS/PPS changes.
                            // Without this, the decoder may continue to output buffers based on old reference state
                            // (often seen as green/uninitialized output on first connect or after reconfigure).
                            droppedNonKeyWhileWaitingCount = 0
                            lastDropNonKeyWhileWaitingLogMs = 0L
                            queuedKeyframeSinceReset = false
                            firstQueuedKeyframePtsUsSinceReset = -1L
                            nordCe4OutputWarmupDropsRemaining = 0
                            nordCe4SuppressRenderUntilUptimeMs = 0L
                            nordCe4RenderedFramesAfterWarmup = 0
                            postedFirstFrameRendered = false
                            firstRenderAttemptUptimeMs = 0L
                            clearDecodeQueue()
                            skipCount = 0
                            // Don't reset decoder immediately - let it reconfigure from new CSD
                            // Only reset if decoder doesn't exist
                            synchronized(decoderLock) {
                                if (decoder == null) {
                                    resetDecoderInternal()
                                    // After reset, try to start decoder if all conditions are met
                                    val accepted = getAcceptedDimsOrNull()
                                    if (surfaceReady && accepted != null) {
                                        Log.d(
                                            logFrom,
                                            "🔍 [CSD DIAGNOSTIC] CSD received, surface ready, STREAM_ACCEPTED dims known (${accepted.first}x${accepted.second}) - attempting decoder start"
                                        )
                                        val started = startDecoderIfReady()
                                        Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] startDecoderIfReady() after CSD: $started")
                                    } else {
                                        Log.d(
                                            logFrom,
                                            "🔍 [CSD DIAGNOSTIC] CSD received but cannot start decoder yet: surfaceReady=$surfaceReady, acceptedDims=${accepted?.let { "${it.first}x${it.second}" } ?: "null"}"
                                        )
                                    }
                                } else {
                                    // CRITICAL (Option A rotation swaps):
                                    // When SPS/PPS changes, the decoder MUST be recreated to pick up the new config.
                                    // The previous behavior ("reconfigure on next frame") is not valid for MediaCodec:
                                    // you cannot update csd buffers on an already-configured codec.
                                    //
                                    // Without this, rotations that restart the encoder (new SPS/PPS) can cause a
                                    // persistent black preview until the next full reconnect.
                                    Log.w(
                                        logFrom,
                                        "[Stream Client] 🟠 [CSD DIAGNOSTIC] CSD changed while decoder exists -> resetting decoder to apply new SPS/PPS (epoch=$msgEpoch)"
                                    )
                                    try {
                                        resetDecoderInternal()
                                    } catch (_: Throwable) {
                                    }
                                    val accepted = getAcceptedDimsOrNull()
                                    if (surfaceReady && accepted != null) {
                                        Log.d(
                                            logFrom,
                                            "🔍 [CSD DIAGNOSTIC] CSD updated, surface ready, STREAM_ACCEPTED dims known (${accepted.first}x${accepted.second}) - attempting decoder start"
                                        )
                                        val started = startDecoderIfReady(forceWidth = accepted.first, forceHeight = accepted.second)
                                        Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] startDecoderIfReady() after CSD reset: $started")
                                    }
                                }
                            }
                            // Request keyframe immediately after receiving CSD to speed up decoder start
                            requestKeyframe("csd_received")
                            } catch (e: java.net.SocketException) {
                                // Connection reset or socket closed - break out of listen loop
                                Log.w(logFrom, "[Stream Client] 🔴 [CSD] Socket error reading CSD data: ${e.message}")
                                throw e // Re-throw to exit listen loop
                            } catch (e: Exception) {
                                Log.w(logFrom, "[Stream Client] 🔴 [CSD] Error reading CSD data: ${e.message}", e)
                                // For socket errors, exit the listen loop
                                if (e is java.io.IOException) {
                                    throw e
                                }
                            }
                        } else {
                            Log.w(logFrom, "[Stream Client] Invalid CSD sizes: $line")
                        }
                    }

                    line.startsWith("ENC_ROT|") -> {
                        val parts = parseParams(line)
                        val deg = parts["deg"]?.toIntOrNull() ?: 0
                        val prevDeg = rotationDeg
                        rotationDeg = deg
                        Log.d(logFrom, "[Stream Client] Encoder rotation updated (label-only): $deg")
                        postRotation(deg)
                        // Proactively request keyframe when rotation changes to help Nord CE4
                        // and similar devices handle the transition smoothly.
                        if (prevDeg != deg) {
                            requestKeyframe("rotation_change")
                        }
                    }

                    line.startsWith("CAMERA|") -> {
                        val parts = parseParams(line)
                        val front = parts["front"]?.toBooleanStrictOrNull() ?: false
                        mainHandler.post { onCameraFacingChanged(front) }
                    }

                    line.startsWith("COMM|") -> {
                        val parts = parseParams(line)
                        val enabled = parts["enabled"]?.toBooleanStrictOrNull() ?: true
                        communicationEnabled = enabled
                        mainHandler.post { onCommunicationEnabledChanged(enabled) }
                    }

                    line.startsWith("RECORDING|") -> {
                        val parts = parseParams(line)
                        val active = parts["active"]?.toBooleanStrictOrNull() ?: false
                        Log.d(logFrom, "[Stream Client] 🔴 [RECORDING] Received RECORDING|active=$active from primary device")
                        if (active) {
                            beginReconfigureGrace("primary_recording_active", 45_000L)
                        }
                        postRecording(active)
                    }

                    line.startsWith("AUDIO_FRAME|") || line.startsWith("AUDIO_DOWN|") -> {
                        Log.d(logFrom, "🔵 [AUDIO] AUDIO_FRAME| message received: $line")
                        val params = parseParams(line)
                        val size = params["size"]?.toIntOrNull() ?: 0
                        val rate = params["rate"]?.toIntOrNull() ?: AUDIO_SAMPLE_RATE
                        val ch = params["ch"]?.toIntOrNull() ?: 1
                        val format = params["format"] // "aac" for compressed, null/absent for PCM
                        val isCompressed = format == "aac"
                        val tsUs = params["tsUs"]?.toLongOrNull() ?: (System.nanoTime() / 1000L)
                        Log.d(logFrom, " 🔵 [AUDIO] Parsed AUDIO_FRAME| params: size=$size, rate=$rate, ch=$ch, format=$format, isCompressed=$isCompressed, tsUs=$tsUs")
                        if (size > 0) {
                            var buffer: ByteArray? = null
                            try {
                                buffer = ByteArrayPool.get(size)
                                Log.d(logFrom, "🔵 [AUDIO] Reading $size bytes of audio data from socket...")
                                try {
                                    readFullyFromSocket(buffer, size)
                                    // Mark audio-down activity (helps diagnose "no audio" issues quickly).
                                    lastAudioDownRxUptimeMs = android.os.SystemClock.uptimeMillis()
                                    // Note: hex dump only shows start of buffer, may include garbage if reused. Use limit.
                                    // Log.d(logFrom, "[Stream Client] 🔵 [AUDIO] Successfully read $size bytes...")
                                    if (isCompressed) {
                                        // Decode AAC to PCM
                                        decodeAacToPcm(buffer, size, rate, ch, tsUs)
                                    } else {
                                        // Direct PCM playback
                                        playAudioDown(buffer, size, rate, ch, tsUs)
                                    }
                                    // Ownership transferred to queues, do NOT recycle here.
                                    buffer = null 
                                } catch (e: java.net.SocketException) {
                                    // Recycle buffer if read failed
                                    ByteArrayPool.recycle(buffer)
                                    buffer = null
                                    throw e
                                } catch (t: Throwable) {
                                    ByteArrayPool.recycle(buffer)
                                    buffer = null
                                    throw t
                                }
                            } catch (e: java.net.SocketException) {
                                // Connection reset or socket closed - break out of listen loop
                                throw e
                            } catch (t: Throwable) {
                                Log.w(logFrom, "[Stream Client] Audio receive error", t)
                                if (buffer != null) ByteArrayPool.recycle(buffer)
                            }
                        } else {
                            Log.w(logFrom, "🔴 [AUDIO] Invalid audio frame size: $size")
                        }
                    }

                    line.startsWith("FRAME|") -> {
                        Log.d(logFrom, "[Stream Client] [STREAM CLIENT] 🔵 [FRAME RECEIVED] ✅ FRAME| message matched and received: $line")
                        val params = parseParams(line)
                        val msgEpoch = params["epoch"]?.toLongOrNull() ?: 0L
                        val seq = params["seq"]?.toLongOrNull() ?: -1L
                        val size = params["size"]?.toIntOrNull() ?: 0
                        val isKeyFrame = params["key"]?.toBooleanStrictOrNull() ?: false
                        val sentUs = params["tsUs"]?.toLongOrNull() ?: -1L
                        val srvMs = params["srvMs"]?.toLongOrNull() ?: -1L
                        val capMs = params["capMs"]?.toLongOrNull() ?: -1L
                        val ageMs = params["ageMs"]?.toLongOrNull() ?: -1L
                        // Drop frames from old epochs to prevent mixing across reconfigure/reconnect.
                        if (streamEpoch > 0L && msgEpoch > 0L && msgEpoch != streamEpoch) {
                            // Still must drain the binary payload to keep protocol framing correct.
                            if (size > 0) {
                                val sink = ByteArrayPool.get(size)
                                readFullyFromSocket(sink)
                                ByteArrayPool.recycle(sink)
                            }
                            val nowMs = System.currentTimeMillis()
                            if (nowMs - lastDropNonKeyWhileWaitingLogMs >= 2_000L) {
                                lastDropNonKeyWhileWaitingLogMs = nowMs
                                Log.w(logFrom, "[Stream Client] ⚠️ [EPOCH] Dropping FRAME from old epoch=$msgEpoch (currentEpoch=$streamEpoch) size=$size key=$isKeyFrame")
                            }
                            continue
                        }

                        // Sequence handling (if available): detect missing/late frames instantly.
                        // This is *diagnostic-first* and safe even on lossy Wi‑Fi.
                        if (seq >= 0L && msgEpoch > 0L) {
                            // Reset tracking when epoch changes (new stream era).
                            if (lastVideoSeqEpoch != msgEpoch) {
                                lastVideoSeqEpoch = msgEpoch
                                lastVideoSeq = -1L
                                videoSeqGapCount = 0L
                                lastSeqGapLogMs = 0L
                                Log.d(logFrom, "[Stream Client] 🔵 [SEQ] Reset sequence tracking for epoch=$msgEpoch")
                            }

                            val prev = lastVideoSeq
                            if (prev >= 0L) {
                                val expected = prev + 1L
                                if (seq != expected) {
                                    val delta = seq - expected
                                    videoSeqGapCount++
                                    val nowMs = System.currentTimeMillis()
                                    if (nowMs - lastSeqGapLogMs >= 500L) {
                                        lastSeqGapLogMs = nowMs
                                        if (delta > 0L) {
                                            Log.w(
                                                logFrom,
                                                "⚠️ [SEQ GAP] Missing $delta frame(s): expected=$expected got=$seq epoch=$msgEpoch (gapCount=$videoSeqGapCount)."
                                            )
                                        } else {
                                            Log.w(
                                                logFrom,
                                                "⚠️ [SEQ REORDER] Out-of-order/late frame: expected=$expected got=$seq epoch=$msgEpoch (delta=$delta gapCount=$videoSeqGapCount)"
                                            )
                                        }
                                    }
                                    // Missing frames can increase decoder recovery time; request a keyframe (throttled internally).
                                    if (delta > 0L) {
                                        requestKeyframe("seq_gap_missing")
                                    }
                                }
                            }
                            lastVideoSeq = seq
                        }

                        Log.d(
                            logFrom,
                            "🔵 [FRAME RECEIVED] Parsed FRAME| params: seq=${if (seq >= 0L) seq else -1} size=$size, isKeyFrame=$isKeyFrame"
                        )
                        if (size <= 0) {
                            Log.w(logFrom, "[Stream Client] 🔴 [FRAME RECEIVED] Invalid frame size in header: $line")
                            continue
                        }

                        // RX overload protection:
                        // Even though our decode loop prefers "latest" frames, the socket receive path can still
                        // allocate large byte arrays under heavy motion / bursty delivery.
                        //
                        // Since this is a live preview, freshness beats completeness. If our decode queue is full,
                        // we drop non-key frames *before* allocating a new ByteArray by draining directly into the
                        // reusable scratch buffer (keeps protocol framing correct, avoids GC pressure).
                        if (decodeRunning && !isKeyFrame && decodeQueue.remainingCapacity() <= 0) {
                            // Grow scratch buffer if needed.
                            if (frameDiscardScratchBuffer.size < size) {
                                frameDiscardScratchBuffer = ByteArray(size.coerceAtLeast(frameDiscardScratchBuffer.size * 2))
                                Log.d(logFrom, "[Stream Client] 🔵 [RX OVERLOAD] Grown discard buffer to ${frameDiscardScratchBuffer.size} bytes for frame size $size")
                            }
                            try {
                                readFullyFromSocket(frameDiscardScratchBuffer, size)
                                lastFrameRxUptimeMs = android.os.SystemClock.uptimeMillis()
                                rxOverloadDropCount++
                                // Throttle logs.
                                if (rxOverloadDropCount <= 5 || rxOverloadDropCount % 50L == 0L) {
                                    Log.w(
                                        logFrom,
                                        "⚠️ [RX OVERLOAD] Dropped non-key frame due to full decode queue (dropCount=$rxOverloadDropCount, size=$size, queueSize=${decodeQueue.size})"
                                    )
                                }
                            } catch (e: java.net.SocketException) {
                                Log.w(logFrom, "[Stream Client] 🔴 [RX OVERLOAD] Socket error draining overloaded frame: ${e.message}")
                                throw e
                            } catch (e: Exception) {
                                Log.w(logFrom, "[Stream Client] 🔴 [RX OVERLOAD] Error draining overloaded frame: ${e.message}", e)
                                if (e is java.io.IOException) throw e
                            }
                            continue
                        }

                        // CRITICAL FIX: Skip expensive frame processing when decoding is paused
                        // This prevents backpressure chain: allocations → TCP window fill → primary blocks → encoder stalls
                        // We still read the binary data to keep protocol in sync, but discard it immediately using reusable buffer
                        if (!decodeRunning) {
                            // Decoding is paused (e.g., app backgrounded) - fast drain without allocations
                            // OPTIMIZATION: Use reusable scratch buffer to avoid allocation churn and GC pressure
                            // Grow buffer if needed (keyframes can be 200-300KB)
                            if (frameDiscardScratchBuffer.size < size) {
                                frameDiscardScratchBuffer = ByteArray(size.coerceAtLeast(frameDiscardScratchBuffer.size * 2))
                                Log.d(logFrom, "[Stream Client] 🔵 [FRAME DRAIN] Grown discard buffer to ${frameDiscardScratchBuffer.size} bytes for frame size $size")
                            }
                            // Fast drain: read into reusable buffer and discard immediately
                            // This keeps protocol in sync while avoiding IncomingFrame allocation and queue operations
                            try {
                                readFullyFromSocket(frameDiscardScratchBuffer, size)
                                // Log occasionally to avoid spam (only large frames or keyframes)
                                if (size > 100_000 || isKeyFrame) {
                                    Log.d(logFrom, "[Stream Client] 🔵 [FRAME DRAIN] Frame drained (decoding paused): size=$size, isKeyFrame=$isKeyFrame, decodeRunning=$decodeRunning")
                                }
                            } catch (e: java.net.SocketException) {
                                // Connection reset or socket closed - break out of listen loop
                                Log.w(logFrom, "[Stream Client] 🔴 [FRAME DRAIN] Socket error draining frame: ${e.message}")
                                throw e // Re-throw to exit listen loop
                            } catch (e: Exception) {
                                Log.w(logFrom, "[Stream Client] 🔴 [FRAME DRAIN] Error draining frame: ${e.message}", e)
                                // For socket errors, exit the listen loop
                                if (e is java.io.IOException) {
                                    throw e
                                }
                            }
                            continue
                        }

                        var buffer: ByteArray? = null
                        try {
                            buffer = ByteArrayPool.get(size)
                            readFullyFromSocket(buffer, size)
                            lastFrameRxUptimeMs = android.os.SystemClock.uptimeMillis()

                            // Restore adaptive jitter buffering (smooths bursty playback)
                            updateAdaptiveJitterTargetOnFrameRx(lastFrameRxUptimeMs)

                            // If we're paused or not in a state to receive frames, just discard.
                            // But we had to read them from socket to keep stream in sync.
                            val currentSocket = socket
                            if (!running || currentSocket == null || currentSocket.isClosed) {
                                Log.d(logFrom, "[Stream Client] Dropping frame (not running/connected)")
                                ByteArrayPool.recycle(buffer)
                                buffer = null
                                continue
                            }

                            val frame = IncomingFrame(
                                data = buffer,
                                length = size,
                                isKeyFrame = isKeyFrame,
                                receivedTimeUs = System.nanoTime() / 1000,
                                sentTimeUs = sentUs,
                                sentServerMs = srvMs,
                                captureServerMs = capMs,
                                ageMsAtSend = ageMs
                            )
                            
                            // Jitter buffer logic
                            // If buffer is full, drop oldest frame to make room for new one (and reduce latency)
                            // Ideally, we should check if new frame is KeyFrame. If so, clear queue?
                            // Simple strategy: drop oldest FRAME (not Keyframe if possible? No, ArrayQueue poll drops head)
                            if (decodeQueue.remainingCapacity() == 0) {
                                val dropped = decodeQueue.poll()
                                dropped?.recycle()
                                droppedNonKeyWhileWaitingCount++ // Diagnostic reuse
                            }
                            
                            if (!decodeQueue.offer(frame)) {
                                // Should imply queue full (handled above) but just in case
                                frame.recycle()
                            } else {
                                buffer = null // Ownership transferred to queue
                            }
                        } catch (e: java.net.SocketException) {
                            if (buffer != null) ByteArrayPool.recycle(buffer)
                            throw e
                        } catch (t: Throwable) {
                            if (buffer != null) ByteArrayPool.recycle(buffer)
                            // Log and continue? Or rethrow? 
                            // In original code, we caught and logged specific frame errors but broke on socket errors.
                            Log.w(logFrom, "[Stream Client] Frame processing error", t)
                        }
                        continue
                    }

                    line.startsWith("BITRATE_ADJUSTED|") -> {
                        val params = parseParams(line)
                        val newBitrate = params["bitrate"]?.toIntOrNull() ?: 0
                        if (newBitrate > 0) {
                            Log.d(logFrom, "[Stream Client] Bitrate adjusted to $newBitrate bps (seamless)")
                            // Update lastRequestedConfig bitrate
                            lastRequestedConfig?.let {
                                lastRequestedConfig = StreamConfig(it.width, it.height, newBitrate, it.fps)
                            }
                            // No decoder reset needed - bitrate change is seamless
                        }
                    }
                    
                    line.startsWith("STREAM_ACCEPTED|") -> {
                        lastStreamAcceptedUptimeMs = android.os.SystemClock.uptimeMillis()
                        val params = parseParams(line)
                        val msgEpoch = params["epoch"]?.toLongOrNull() ?: 0L
                        val newWidth = params["width"]?.toIntOrNull() ?: line.substringAfter("width=").substringBefore("|").toIntOrNull() ?: activeWidth
                        val newHeight = params["height"]?.toIntOrNull() ?: line.substringAfter("height=").substringBefore("|").toIntOrNull() ?: activeHeight
                        
                        Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] STREAM_ACCEPTED received: epoch=$msgEpoch ${newWidth}x${newHeight}, csd0=${csd0 != null}, csd1=${csd1 != null}, surfaceReady=$surfaceReady")
                        // STREAM_ACCEPTED while frames are stalled is another strong signal of a recording-triggered reconfigure.
                        // Enter a longer tolerance window so we do not disconnect due to temporary "no video" gaps.
                        try {
                            val nowUptime = android.os.SystemClock.uptimeMillis()
                            val lastActivity = maxOf(lastFrameRxUptimeMs, lastFrameRenderUptimeMs)
                            val sinceLast = if (lastActivity > 0L) (nowUptime - lastActivity) else Long.MAX_VALUE
                            if (sinceLast >= 2_000L || currentState == ConnectionState.RECOVERING || currentState == ConnectionState.CONNECTED) {
                                beginReconfigureGrace("rx_stream_accepted", 120_000L)
                            }
                        } catch (_: Throwable) {
                        }
                        
                        if (msgEpoch > 0L && msgEpoch != streamEpoch) {
                            Log.w(logFrom, "[Stream Client] 🔵 [EPOCH] STREAM_ACCEPTED epoch changed: $streamEpoch -> $msgEpoch (resetting decoder/keyframe gates)")
                            streamEpoch = msgEpoch
                            // New epoch: clear accepted dims; this STREAM_ACCEPTED will re-seed them at end.
                            lastAcceptedWidth = 0
                            lastAcceptedHeight = 0
                            // IMPORTANT:
                            // Do not start a decoder using stale csd0/csd1 from the previous epoch.
                            // Wait for the new epoch's CSD first, otherwise rotations (Option A) can go black.
                            csd0 = null
                            csd1 = null
                            waitingForKeyframe = true
                            postedFirstFrameRendered = false
                            firstRenderAttemptUptimeMs = 0L
                            droppedNonKeyWhileWaitingCount = 0
                            lastDropNonKeyWhileWaitingLogMs = 0L
                            queuedKeyframeSinceReset = false
                            firstQueuedKeyframePtsUsSinceReset = -1L
                            nordCe4OutputWarmupDropsRemaining = 0
                            nordCe4SuppressRenderUntilUptimeMs = 0L
                            nordCe4RenderedFramesAfterWarmup = 0
                            clearDecodeQueue()
                            try { resetDecoderInternal() } catch (_: Throwable) {}
                        } else if (msgEpoch > 0L && streamEpoch == 0L) {
                            // First epoch observed.
                            streamEpoch = msgEpoch
                        }
                        
                        if (newWidth > 0 && newHeight > 0) {
                            // CRITICAL FIX: Immediately notify UI of video size.
                            // Fixes "Zoomed/Cropped" preview on devices (M30s) where decoder doesn't emit format change events.
                            postVideoSize(newWidth, newHeight)
                            postVideoCrop(0, 0, newWidth - 1, newHeight - 1)



                            synchronized(decoderLock) {
                                // IMPORTANT:
                                // Only use STREAM_ACCEPTED dims to seed the decoder *if the decoder does not exist yet*.
                                // If the decoder already exists, activeWidth/activeHeight are treated as "decoder output
                                // authoritative" and should NOT be overwritten by negotiated sizes.
                                if (decoder == null) {
                                    val oldWidth = activeWidth
                                    val oldHeight = activeHeight
                                    activeWidth = newWidth
                                    activeHeight = newHeight
                                    Log.d(
                                        logFrom,
                                        "🔍 [CSD DIAGNOSTIC] Seeded active dimensions from STREAM_ACCEPTED (decoder null): ${oldWidth}x${oldHeight} -> ${activeWidth}x${activeHeight}"
                                    )
                                } else {
                                    Log.d(
                                        logFrom,
                                        "🔍 [CSD DIAGNOSTIC] STREAM_ACCEPTED received while decoder exists; not overwriting active dims (active=${activeWidth}x${activeHeight}, configured=${decoderConfiguredWidth}x${decoderConfiguredHeight})"
                                    )
                                }
                                
                                // After STREAM_ACCEPTED, try to start decoder if CSD and surface are ready
                                if (decoder == null && csd0 != null && csd1 != null) {
                                    if (surfaceReady) {
                                        Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] STREAM_ACCEPTED received, CSD ready, surface ready - attempting decoder start")
                                        // Force server-accepted dims so we don't accidentally configure using a requested size.
                                        val started = startDecoderIfReady(forceWidth = newWidth, forceHeight = newHeight)
                                        Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] startDecoderIfReady() after STREAM_ACCEPTED: $started")
                                    } else {
                                        Log.w(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] STREAM_ACCEPTED received but surface not ready yet - decoder will start when surface attaches (CSD and dimensions are ready)")
                                    }
                                } else {
                                    Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] STREAM_ACCEPTED received but cannot start decoder: decoder=${decoder != null}, csd0=${csd0 != null}, csd1=${csd1 != null}, surfaceReady=$surfaceReady")
                                }
                            }
                        }
                        val bitrate = params["bitrate"]?.toIntOrNull() ?: 0
                        val fps = params["fps"]?.toIntOrNull() ?: 30
                        Log.d(logFrom, "[Stream Client] Stream accepted: width=$newWidth, height=$newHeight, bitrate=$bitrate, fps=$fps")
                        
                        // Update lastRequestedConfig so FPS tracking works correctly
                        // Note: Actual stream size will be determined by decoder output format (from SPS)
                        if (newWidth > 0 && newHeight > 0) {
                            lastRequestedConfig = StreamConfig(newWidth, newHeight, bitrate, fps)
                        }

                        // Detect if server is overriding our requested resolution (common when encoder is locked).
                        try {
                            val sent = lastSentSetStreamConfig
                            if (sent != null && (sent.width != newWidth || sent.height != newHeight)) {
                                Log.w(
                                    logFrom,
                                    "⚠️ [NEGOTIATION] Server STREAM_ACCEPTED ${newWidth}x${newHeight} differs from requested SET_STREAM ${sent.width}x${sent.height}. Disabling further resolution-change requests (bitrate-only) to avoid epoch churn."
                                )
                                serverHonorsResolutionRequests = false
                            }
                        } catch (t: Throwable) {
                            Log.w(logFrom, "[Stream Client] Resolution negotiation check failed (non-fatal)", t)
                        }
                        
                        if (line.contains("session=")) {
                            sessionId = params["session"] ?: line.substringAfter("session=").substringBefore("|")
                        }
                        
                        // Don't update activeWidth/activeHeight from STREAM_ACCEPTED
                        // The decoder output format (from SPS) is authoritative
                        // Only use STREAM_ACCEPTED for lastRequestedConfig (for FPS tracking)
                        
                        // If decoder already exists and size changed significantly, reset it
                        // Use lastAcceptedWidth/Height to track what we PREVIOUSLY agreed to,
                        // so we don't loop if activeWidth (decoder) looks different.
                        val resChanged = (newWidth != lastAcceptedWidth || newHeight != lastAcceptedHeight)
                        val widthDiff = abs(newWidth - lastAcceptedWidth)
                        val heightDiff = abs(newHeight - lastAcceptedHeight)
                        val significantChange = widthDiff > 100 || heightDiff > 100
                        val isInitialAcceptance = (lastAcceptedWidth == 0 && lastAcceptedHeight == 0)
                        
                        Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] STREAM_ACCEPTED resolution check: new=${newWidth}x${newHeight}, last=${lastAcceptedWidth}x${lastAcceptedHeight}, resChanged=$resChanged, significantChange=$significantChange, isInitial=$isInitialAcceptance")
                        
                        // Check if decoder exists and if its dimensions match STREAM_ACCEPTED
                        // Reset decoder if dimensions don't match, even for initial acceptance
                        // Check BEFORE updating lastAcceptedWidth/Height to get accurate comparison
                        synchronized(decoderLock) {
                            val decoderExists = decoder != null
                            // IMPORTANT:
                            // Do NOT use lastRequestedConfig for this comparison because we set it from STREAM_ACCEPTED
                            // above, which would always "match" and prevent a needed decoder reset.
                            // We want to compare against what the decoder was *actually configured with*.
                            val decoderCurrentWidth =
                                if (decoderConfiguredWidth > 0) decoderConfiguredWidth else activeWidth
                            val decoderCurrentHeight =
                                if (decoderConfiguredHeight > 0) decoderConfiguredHeight else activeHeight
                            val decoderDimsMatch = (decoderCurrentWidth == newWidth && decoderCurrentHeight == newHeight)
                            
                            if (decoderExists && !decoderDimsMatch) {
                                // Decoder exists but dimensions don't match STREAM_ACCEPTED - reset decoder to reconfigure
                                Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] Decoder exists (${decoderCurrentWidth}x${decoderCurrentHeight}) but STREAM_ACCEPTED is ${newWidth}x${newHeight} - resetting decoder")
                                resetDecoderInternal()
                                waitingForKeyframe = true
                                clearDecodeQueue()
                                skipCount = 0
                                // Reset FPS tracking on new stream
                                lowFpsStartTime = 0L
                                lowFpsSampleCount = 0
                                requestKeyframe("stream_accepted_reset")
                                // After reset, try to start decoder with correct dimensions
                                if (csd0 != null && csd1 != null && surfaceReady) {
                                    Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] Decoder reset, attempting restart with correct dimensions (${newWidth}x${newHeight})")
                                    val started = startDecoderIfReady(forceWidth = newWidth, forceHeight = newHeight)
                                    Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] startDecoderIfReady() after reset: $started")
                                } else {
                                    Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] Decoder reset but cannot restart yet: csd0=${csd0 != null}, csd1=${csd1 != null}, surfaceReady=$surfaceReady")
                                }
                            } else if (resChanged && significantChange && !isInitialAcceptance && decoderExists) {
                                // Resolution changed significantly on an existing decoder - reset decoder to reconfigure
                                Log.d(logFrom, "[Stream Client] Resolution changed significantly ($newWidth x $newHeight), resetting decoder")
                                resetDecoderInternal()
                                waitingForKeyframe = true
                                clearDecodeQueue()
                                skipCount = 0
                                // Reset FPS tracking on new stream
                                lowFpsStartTime = 0L
                                lowFpsSampleCount = 0
                                requestKeyframe("stream_accepted_reset")
                                // After reset, try to start decoder with correct dimensions
                                if (csd0 != null && csd1 != null && surfaceReady) {
                                    Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] Decoder reset (resolution change), attempting restart with correct dimensions (${newWidth}x${newHeight})")
                                    val started = startDecoderIfReady(forceWidth = newWidth, forceHeight = newHeight)
                                    Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] startDecoderIfReady() after resolution change reset: $started")
                                }
                                postState(ConnectionState.RECOVERING)
                                Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] Posted RECOVERING state due to resolution change (decoder existed)")
                            } else {
                                // Resolution change but decoder doesn't exist yet - don't post RECOVERING
                                // Decoder will be configured with new resolution when it starts
                                if (resChanged && significantChange && !isInitialAcceptance) {
                                    Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] Resolution changed but decoder doesn't exist yet - will configure with new resolution, not posting RECOVERING")
                                } else if (isInitialAcceptance && !decoderExists) {
                                    Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] STREAM_ACCEPTED: Initial acceptance (${newWidth}x${newHeight}), decoder will start when surface/CSD ready")
                                }
                                // Same or similar resolution - just wait for keyframe if needed
                                if (waitingForKeyframe) {
                                    requestKeyframe("stream_accepted")
                                }
                            }
                        }

                        // Mark what we most recently agreed to with the server.
                        // Without this, lastAcceptedWidth/Height stay 0x0 forever and the handler thinks every
                        // STREAM_ACCEPTED is "initial", which can mask real change detection.
                        try {
                            lastAcceptedWidth = newWidth
                            lastAcceptedHeight = newHeight
                            Log.d(
                                logFrom,
                                "🔍 [CSD DIAGNOSTIC] Updated lastAccepted dims: ${lastAcceptedWidth}x${lastAcceptedHeight}"
                            )
                        } catch (t: Throwable) {
                            Log.w(logFrom, "[Stream Client] Failed to update lastAccepted dims (non-fatal)", t)
                        }

                        // Do NOT post video size here. STREAM_ACCEPTED is the negotiated/requested stream size,
                        // but the decoder output format (SPS/MediaCodec) is authoritative and may differ.
                        // Posting negotiated size repeatedly causes UI transforms to "flap" between sizes.
                    }/* line.startsWith("SESSION|") -> {
                         sessionId = line.substringAfter("id=")
                         Log.d(logFrom, "[Stream Client] Session ID assigned: $sessionId")
                     }*/


                }
            }
        } catch (e: SocketException) {
            Log.w(logFrom, "🔵 [SOCKET] SocketException in listen loop: ${e.javaClass.simpleName}", e)
            if (autoReconnect) postError(mapExceptionToUserMessage(e, "stream"))
        } catch (e: IOException) {
            Log.w(logFrom, "🔴 [SOCKET] IOException in listen loop: ${e.javaClass.simpleName}", e)
            if (autoReconnect) postError(mapExceptionToUserMessage(e, "stream"))
        } catch (e: Exception) {
            Log.w(logFrom, "🔴 [SOCKET] Unexpected exception in listen loop: ${e.javaClass.simpleName}", e)
            if (autoReconnect) postError(mapExceptionToUserMessage(e, "stream"))
        } finally {
            stopHeartbeat()
            closeSocket()
            // IMPORTANT: Always reflect socket closure in UI immediately.
            // If autoReconnect is enabled, connect() will move state to CONNECTING shortly after.
            postState(ConnectionState.DISCONNECTED)

            // Only trigger reconnect if auto-reconnect is enabled (not disabled by auth failure)
            if (autoReconnect) {
                triggerReconnect()
            }
        }
    }

    /* ===============================
       * Sending helpers
       * =============================== */
    fun send(command: String) {
        Log.d(logFrom, "[Stream Client] SEND: $command")
        val o = out ?: return
        if (senderClosed.get()) return
        senderExecutor.execute {
            try {
                val bytes = command.toByteArray(Charsets.UTF_8)
                synchronized(writeLock) {
                    o.write(bytes)
                    o.write('\n'.code)
                    o.flush()
                }
            } catch (e: Exception) {
                Log.w(logFrom, "[Stream Client] Send failed: $command", e)
                postError(mapExceptionToUserMessage(e, "send"))
                // Do NOT treat this as a user disconnect; keep auto-reconnect enabled.
                disconnectForRecovery("send_failed")
            }
        }
    }

    private fun sendSetStream(cfg: StreamConfig) {
        lastRequestedConfig = cfg
        lastSentSetStreamConfig = cfg
        send(
            "SET_STREAM|width=${cfg.width}|height=${cfg.height}|bitrate=${cfg.bitrate}|fps=${cfg.fps}"
        )
    }
    
    /**
     * Send seamless bitrate adjustment (no decoder reset)
     */
    private fun sendAdjustBitrate(bitrate: Int) {
        send("ADJUST_BITRATE|bitrate=$bitrate")
        // Update lastRequestedConfig bitrate for tracking
        lastRequestedConfig?.let {
            lastRequestedConfig = StreamConfig(it.width, it.height, bitrate, it.fps)
        }
    }

    // FPS tracking for downgrade decisions (require sustained low FPS before downgrading)
    private var lowFpsSampleCount = 0
    private var lowFpsStartTime = 0L
    private val LOW_FPS_THRESHOLD = 20.0 // Only downgrade if FPS consistently below this
    private val LOW_FPS_DURATION_MS = 5000L // Require 5 seconds of low FPS before downgrading
    private val LOW_FPS_SAMPLES_REQUIRED = 5 // Require 5 consecutive low FPS samples
    
    private fun requestPerfDowngradeIfNeeded(fps: Double) {
        if (!autoReconnect) return
        // IMPORTANT:
        // Do not start adaptive bitrate/resolution changes until we have proven actual rendering.
        // During initial connect / warmup / SurfaceView pixel-gating, render fps can be misleading and
        // causes premature ADJUST_BITRATE spam on Primary.
        // Only allow perf adaptation when the UI has confirmed the preview is visible.
        if (!previewVisible) return
        if (!postedFirstFrameRendered) return
        if (currentState != ConnectionState.STREAMING && currentState != ConnectionState.RECOVERING) return
        if (waitingForKeyframe) return
        val req = lastRequestedConfig ?: return
        
        // Track sustained low FPS (not just momentary dips)
        val now = System.currentTimeMillis()
        if (fps < LOW_FPS_THRESHOLD) {
            if (lowFpsStartTime == 0L) {
                lowFpsStartTime = now
                lowFpsSampleCount = 1
            } else {
                lowFpsSampleCount++
            }
        } else {
            // FPS recovered - reset tracking
            lowFpsStartTime = 0L
            lowFpsSampleCount = 0
        }
        
        // Only downgrade if FPS has been consistently low for required duration
        val sustainedLowFps = lowFpsStartTime > 0L && 
                              (now - lowFpsStartTime) >= LOW_FPS_DURATION_MS &&
                              lowFpsSampleCount >= LOW_FPS_SAMPLES_REQUIRED
        
        if (sustainedLowFps) {
            // Try bitrate reduction first (seamless, no decoder reset)
            if (req.bitrate > 900_000) {
                val loweredBitrate = (req.bitrate * 0.7).toInt().coerceAtLeast(900_000)
                Log.w(
                    logFrom,
                    "Sustained low FPS (${String.format("%.1f", fps)} for ${(now - lowFpsStartTime)}ms); adjusting bitrate to $loweredBitrate (seamless)"
                )
                sendAdjustBitrate(loweredBitrate)
                // Reset tracking after adjustment
                lowFpsStartTime = 0L
                lowFpsSampleCount = 0
                return
            }
            
            // Step 1: go to 720x960 @ 20fps (reduce both decode and encode load)
            // Only downgrade resolution if bitrate reduction didn't help
            if (perfLevel < 1 && (req.width > 720 || req.height > 960)) {
                if (!serverHonorsResolutionRequests) {
                    Log.w(logFrom, "[Stream Client] ⚠️ [PERF] Skipping resolution downgrade request because server overrides requested resolution; bitrate-only mode")
                    // Reset tracking to avoid repeated attempts.
                    lowFpsStartTime = 0L
                    lowFpsSampleCount = 0
                    return
                }
                perfLevel = 1
                val next = StreamConfig(720, 960, 2_000_000, 20)
                // DO NOT persist downgrade - allow retry on next connection
                startProfileOverride = StreamProfile(next.width, next.height, next.bitrate, next.fps)
                Log.w(
                    logFrom,
                    "Sustained low FPS (${String.format("%.1f", fps)} for ${(now - lowFpsStartTime)}ms); requesting ${next.width}x${next.height}@${next.fps} for smoother preview"
                )
                sendCapabilities(maxWidth = next.width, maxHeight = next.height, maxBitrate = next.bitrate)
                sendSetStream(next)
                requestKeyframe("perf_downgrade_720")
                // Reset tracking after downgrade
                lowFpsStartTime = 0L
                lowFpsSampleCount = 0
                return
            }
            // Step 2: if already at 720x960 and still struggling, go to 480x640 @ 15fps
            if (perfLevel < 2 && fps < 15.0 && req.width == 720 && req.height == 960) {
                if (!serverHonorsResolutionRequests) {
                    Log.w(logFrom, "[Stream Client] ⚠️ [PERF] Skipping 480x640 downgrade request because server overrides requested resolution; bitrate-only mode")
                    // Reset tracking to avoid repeated attempts.
                    lowFpsStartTime = 0L
                    lowFpsSampleCount = 0
                    return
                }
                perfLevel = 2
                val next = StreamConfig(480, 640, 900_000, 15)
                startProfileOverride = StreamProfile(next.width, next.height, next.bitrate, next.fps)
                Log.w(
                    logFrom,
                    "Sustained very low FPS (${String.format("%.1f", fps)} for ${(now - lowFpsStartTime)}ms); requesting ${next.width}x${next.height}@${next.fps}"
                )
                sendCapabilities(maxWidth = next.width, maxHeight = next.height, maxBitrate = next.bitrate)
                sendSetStream(next)
                requestKeyframe("perf_downgrade_480")
                // Reset tracking after downgrade
                lowFpsStartTime = 0L
                lowFpsSampleCount = 0
            }
        }
    }

    fun sendCapabilities(
        maxWidth: Int, maxHeight: Int, maxBitrate: Int
    ) {
        send(
            "CAPS|maxWidth=$maxWidth|maxHeight=$maxHeight|maxBitrate=$maxBitrate"
        )
    }

    fun startRecording() {
        Log.d(logFrom, "[Stream Client] 🔴 [RECORDING] startRecording() called - checking connection state: out=${out != null}")
        if (out == null) {
            Log.w(logFrom, "[Stream Client] 🔴 [RECORDING] startRecording() FAILED - not connected (out is null)")
            postError("Not connected.")
            return
        }
        // Recording can temporarily pause frames/audio (CameraX/encoder/stream).
        // Keep socket open and wait for keyframe/CSD instead of disconnecting.
        beginReconfigureGrace("viewer_start_recording_send", 45_000L)
        Log.d(logFrom, "[Stream Client] 🔴 [RECORDING] Sending START_RECORDING command to primary device")
        send("START_RECORDING")
    }

    fun stopRecording() {
        if (out == null) {
            postError("Not connected.")
            return
        }
        send("STOP_RECORDING")
    }

    fun switchCamera() {
        if (out == null) {
            postError("Not connected.")
            return
        }
        send("SWITCH_CAMERA")
    }

    fun sendZoom(ratio: Float) {
        if (out == null) return
        send("ZOOM|ratio=$ratio")
    }

    /**
     * Reset adaptive jitter buffer state.
     *
     * Intended behavior:
     * - Called on major lifecycle transitions (connect/disconnect, epoch change paths where we clear queues).
     * - Clears learned jitter so we don't carry stale network conditions across sessions.
     *
     * Fallback:
     * - If anything throws, we disable jitter buffering to keep the stream working.
     */
    private fun resetAdaptiveJitterState(reason: String) {
        try {
            // Re-enable on major lifecycle transitions; if the device/environment still triggers an issue,
            // updateAdaptiveJitterTargetOnFrameRx() will auto-disable again.
            jitterBufferEnabled = true
            jitterTargetFrames = JITTER_MIN_FRAMES
            jitterEwmaMs = 0.0
            lastJitterRxUptimeMs = 0L
            lastJitterTargetLogUptimeMs = 0L
            lastJitterDropLogUptimeMs = 0L
            Log.d(logFrom, "[Stream Client] 🔵 [JITTER] Reset jitter state (reason=$reason) target=$jitterTargetFrames")
        } catch (t: Throwable) {
            jitterBufferEnabled = false
            Log.w(logFrom, "[Stream Client] ⚠️ [JITTER] Reset failed; disabling jitter buffer (reason=$reason)", t)
        }
    }

    /**
     * Update jitter target backlog based on observed frame inter-arrival variability.
     *
     * This runs on the socket receive thread (low overhead):
     * - We compute inter-arrival delta using uptime (not wall clock).
     * - We compute a lightweight EWMA of jitter in milliseconds.
     * - We choose a target backlog of 2–4 frames.
     *
     * Why:
     * - On bursty Wi‑Fi, a small backlog absorbs short gaps and reduces visible micro-stutter.
     *
     * Fallback:
     * - If any exception occurs, disable jitter buffering to avoid impacting playback.
     */
    private fun updateAdaptiveJitterTargetOnFrameRx(nowUptimeMs: Long) {
        if (!jitterBufferEnabled) return
        try {
            val prev = lastJitterRxUptimeMs
            lastJitterRxUptimeMs = nowUptimeMs
            if (prev <= 0L) return

            val deltaMs = (nowUptimeMs - prev).coerceAtLeast(0L).coerceAtMost(2_000L)
            val fps = lastRequestedConfig?.fps?.takeIf { it > 0 } ?: 30
            val expectedMs = 1000.0 / fps.toDouble()
            val jitterMs = abs(deltaMs.toDouble() - expectedMs)

            // EWMA smoothing: stable yet responsive.
            jitterEwmaMs = if (jitterEwmaMs <= 0.0) jitterMs else (0.90 * jitterEwmaMs + 0.10 * jitterMs)

            val newTarget = when {
                // Heavy jitter / big gaps -> buffer more (adds small latency but smooths playback).
                jitterEwmaMs >= 25.0 || deltaMs >= 90L -> JITTER_MAX_FRAMES
                // Moderate jitter -> buffer 3 frames.
                jitterEwmaMs >= 12.0 || deltaMs >= 60L -> 3
                else -> JITTER_MIN_FRAMES
            }

            if (newTarget != jitterTargetFrames) {
                jitterTargetFrames = newTarget
                if (nowUptimeMs - lastJitterTargetLogUptimeMs >= JITTER_LOG_THROTTLE_MS) {
                    lastJitterTargetLogUptimeMs = nowUptimeMs
                    Log.d(
                        logFrom,
                        "🔵 [JITTER] Target backlog changed -> $jitterTargetFrames frames (ewma=${String.format("%.1f", jitterEwmaMs)}ms, delta=${deltaMs}ms, fps=$fps)"
                    )
                }
            }
        } catch (t: Throwable) {
            jitterBufferEnabled = false
            Log.w(logFrom, "[Stream Client] ⚠️ [JITTER] Update failed; disabling jitter buffer (non-fatal)", t)
        }
    }

    /* ===============================
     * Decode pipeline
     * =============================== */
    private fun startDecodeLoop() {
        if (decodeRunning) {
            Log.d(logFrom, "[Stream Client] 🔵 [DECODE LOOP] Decode loop already running, skipping start")
            return
        }
        Log.d(logFrom, "[Stream Client] 🔵 [DECODE LOOP] Starting decode loop - decodeRunning=$decodeRunning, queueSize=${decodeQueue.size}, decoder=${decoder != null}, waitingForKeyframe=$waitingForKeyframe")
        decodeRunning = true
        decodeExecutor.execute {
            Log.d(logFrom, "[Stream Client] 🔵 [DECODE LOOP] Decode loop thread started - thread=${Thread.currentThread().name}")
            while (decodeRunning) {
                try {
                    // Low-latency mode: always decode the *latest* frame available to avoid building up delay.
                    // Also, when waiting for a keyframe, prefer the newest keyframe in the drained batch.
                    val queueSizeBefore = decodeQueue.size
                    if (queueSizeBefore == 0) {
                        Log.d(logFrom, "[Stream Client] 🔵 [DECODE LOOP] Waiting for frame from queue (size=0, waitingForKeyframe=$waitingForKeyframe, decoder=${decoder != null})")
                    }
                    val first = decodeQueue.take()

                    // Two modes:
                    // - Low-latency mode (default): drain to latest to minimize delay.
                    // - Jitter-buffer mode: process frames in-order to preserve a small backlog and smooth bursty delivery.
                    //   While waiting for keyframe, we still prefer the latest keyframe for fastest recovery.
                    val toDecode: IncomingFrame =
                        if (!jitterBufferEnabled || waitingForKeyframe) {
                            var latest = first
                            var latestKey: IncomingFrame? = if (first.isKeyFrame) first else null
                            while (true) {
                                val next = decodeQueue.poll() ?: break
                                latest.recycle() // Recycle drained frames
                                latest = next
                                if (next.isKeyFrame) latestKey = next
                            }
                            if (waitingForKeyframe) (latestKey ?: latest) else latest
                        } else {
                            // Jitter-buffer mode: keep FIFO ordering.
                            // If backlog grows too large, drop oldest extras to bound latency.
                            val maxAllowed = (jitterTargetFrames + JITTER_BACKLOG_DROP_EXTRA_FRAMES)
                                .coerceAtLeast(JITTER_MAX_FRAMES + 6)
                            var dropped = 0
                            while (decodeQueue.size > maxAllowed) {
                                val droppedFrame = decodeQueue.poll() ?: break
                                droppedFrame.recycle() // Recycle dropped frames
                                dropped++
                            }
                            if (dropped > 0) {
                                val nowUptimeMs = android.os.SystemClock.uptimeMillis()
                                if (nowUptimeMs - lastJitterDropLogUptimeMs >= JITTER_LOG_THROTTLE_MS) {
                                    lastJitterDropLogUptimeMs = nowUptimeMs
                                    Log.w(
                                        logFrom,
                                        "⚠️ [JITTER] Decode backlog trimmed: dropped=$dropped queueSize=${decodeQueue.size} target=$jitterTargetFrames maxAllowed=$maxAllowed"
                                    )
                                }
                            }
                            first
                        }

                    Log.d(
                        logFrom,
                        "🔵 [DECODE LOOP] Processing frame: key=${toDecode.isKeyFrame}, size=${toDecode.data.size} bytes, " +
                            "waitingForKeyframe=$waitingForKeyframe, jitter=${jitterBufferEnabled && !waitingForKeyframe}, " +
                            "targetBacklog=$jitterTargetFrames, decoder=${if (decoder != null) "exists" else "null"}, queueSizeAfter=${decodeQueue.size}"
                    )
                    handleDecodedFrame(toDecode)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    // Never let decode thread crash the process; reset and wait for next keyframe.
                    Log.w(logFrom, "[Stream Client] Decode loop error", e)
                    waitingForKeyframe = true
                    resetDecoderInternal()
                }
            }
        }
    }

    private fun handleDecodedFrame(frame: IncomingFrame) {
        // CRITICAL FIX 1: Ensure decoder exists before processing frames
        // If decoder doesn't exist, try to start it FIRST before processing the frame
        // This prevents frames from being lost when decoder is not yet ready
        synchronized(decoderLock) {
            if (decoder == null) {
                // Decoder doesn't exist - attempt to start it if all conditions are met
                Log.d(logFrom, "[Stream Client] 🔵 [DECODER] Decoder is null, attempting to start before processing frame: surfaceReady=$surfaceReady, csd0=${csd0 != null}, csd1=${csd1 != null}, outputSurface=${outputSurface != null}")
                val started = startDecoderIfReady()
                if (!started) {
                    // CRITICAL: Log why decoder startup failed for better diagnostics
                    val reason = when {
                        !surfaceReady -> "surface not ready (surfaceReady=false)"
                        outputSurface == null -> "outputSurface is null"
                        csd0 == null || csd1 == null -> "CSD not available (csd0=${csd0 != null}, csd1=${csd1 != null})"
                        else -> "unknown reason"
                    }
                    Log.w(logFrom, "[Stream Client] 🔴 [DECODER] Cannot start decoder: $reason - frame will be skipped/re-queued")
                    // Can't start decoder yet - handle frame based on whether we're waiting for keyframe
                    if (waitingForKeyframe) {
                        // Waiting for keyframe - skip non-keyframes, keep keyframes in queue
                        if (!frame.isKeyFrame) {
                            val nowMs = System.currentTimeMillis()
                            if (nowMs - lastSkipNonKeyLogMs >= 2_000L) {
                                lastSkipNonKeyLogMs = nowMs
                                Log.d(logFrom, "[Stream Client] 🔵 [DECODER] Decoder not ready, skipping non-keyframe (waiting for decoder), skipCount=$skipCount")
                            }
                            skipCount++
                            // Only call maybeDowngrade if we've skipped many frames (don't downgrade on temporary issues)
                            if (skipCount >= 30) {
                                maybeDowngrade()
                            }
                            // Throttle keyframe requests to avoid spam
                            if (skipCount % 10 == 0) {
                                requestKeyframe("decoder_not_ready")
                            }
                            frame.recycle() // Recycle skipped frame
                            return // Skip this non-keyframe
                        } else {
                            // Keyframe arrived but decoder not ready - keep it in queue by re-queuing
                            // The decode loop will process it when decoder is ready
                            Log.d(logFrom, "[Stream Client] 🔵 [DECODER] Keyframe arrived but decoder not ready - re-queuing for later processing")
                            decodeQueue.offer(frame) // Re-queue keyframe
                            // Request keyframe in case decoder becomes ready soon
                            if (skipCount % 5 == 0) {
                                requestKeyframe("decoder_not_ready_keyframe")
                            }
                            return // Don't process now, will be processed when decoder starts
                        }
                    } else {
                        // Not waiting for keyframe but decoder doesn't exist - this shouldn't happen
                        // but if it does, skip the frame
                        Log.w(logFrom, "[Stream Client] 🔵 [DECODER] WARNING: Decoder is null but not waiting for keyframe - skipping frame")
                        frame.recycle() // Recycle skipped frame
                        return
                    }
                } else {
                    // IMPORTANT: Do NOT clear waitingForKeyframe just because the decoder started.
                    // We only clear it when we actually receive a keyframe (see logic below).
                }
            }
        }
        
        // IMPORTANT:
        // Do NOT clear waitingForKeyframe just because we *received* a keyframe header.
        //
        // Rationale:
        // - If we clear waitingForKeyframe before the keyframe is successfully queued into MediaCodec,
        //   the decode loop may start feeding P-frames immediately and produce green/garbled output.
        // - We therefore clear waitingForKeyframe ONLY after we successfully queue the keyframe into the decoder
        //   (see feedDecoder()).
        
// --- Keyframe gating ---
        if (waitingForKeyframe && !frame.isKeyFrame) {
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastSkipNonKeyLogMs >= 2_000L) {
                lastSkipNonKeyLogMs = nowMs
                Log.d(logFrom, "[Stream Client] Skipping non-keyframe (waiting for keyframe), skipCount=$skipCount")
            }
            skipCount++
            // Only call maybeDowngrade if we've skipped many frames (don't downgrade on temporary issues)
            if (skipCount >= 30) {
                maybeDowngrade()
            }
            // Throttle keyframe requests to avoid spam
            if (skipCount % 10 == 0) {
                requestKeyframe("drop_non_key")
            }
            frame.recycle() // Recycle skipped frame
            return
        }
        // Latency instrumentation:
        // Prefer server-computed age (clock-independent). If unavailable, fall back to clock-sync estimate.
        if (frame.ageMsAtSend >= 0) {
            val nowUs = System.nanoTime() / 1_000
            if (nowUs - lastLatencyLogUs > 1_000_000L) { // ~1s
                lastLatencyLogUs = nowUs
                Log.d(logFrom, "[Stream Client] Video ageAtSend=${frame.ageMsAtSend}ms q=${decodeQueue.size}")
            }
        } else if (frame.captureServerMs > 0 && clockSynced) {
            val nowUs = System.nanoTime() / 1_000
            if (nowUs - lastLatencyLogUs > 1_000_000L) { // ~1s
                lastLatencyLogUs = nowUs
                val nowMs = System.currentTimeMillis()
                val estServerNowMs = nowMs + clockOffsetMs
                val latencyMs = estServerNowMs - frame.captureServerMs.toDouble()
                Log.d(logFrom, "[Stream Client] Video latency=${"%.1f".format(latencyMs)}ms q=${decodeQueue.size}")
            }
        }
        // NOTE: Decoder existence check and waitingForKeyframe handling is now done above
        // (before keyframe gating logic). This ensures decoder exists before processing frames.
        val nowUs = System.nanoTime() / 1000
        if (nowUs - frame.receivedTimeUs > 80_000) {
            lateFrameCount++
        } else {
            lateFrameCount = 0
        }

        if (lateFrameCount >= 5 && !pressureActive && nowUs - lastBackpressureSignalUs > BACKPRESSURE_COOLDOWN_US) {
            Log.w(logFrom, "[Stream Client] Backpressure detected — notifying server")
            send("BACKPRESSURE")
            pressureActive = true
            lastBackpressureSignalUs = nowUs
        }

        if (pressureActive && lateFrameCount == 0 && nowUs - lastBackpressureSignalUs > BACKPRESSURE_COOLDOWN_US) {
            Log.d(logFrom, "[Stream Client] Pressure cleared — notifying server")
            send("PRESSURE_CLEAR")
            pressureActive = false
            lastBackpressureSignalUs = nowUs
        }

        feedDecoder(frame)
    }

    private fun feedDecoder(frame: IncomingFrame) {
        try {
            synchronized(decoderLock) {
                // CRITICAL FIX: Re-check decoder before each operation to prevent race condition
                // where resetDecoderInternal() destroys the decoder while we're using it
                // check decoder
                val codec = decoder ?: run {
                    frame.recycle()
                    return
                }

                val inputIndex = codec.dequeueInputBuffer(DECODE_TIMEOUT_US)
                if (inputIndex >= 0) {
                    // Re-check decoder is still valid before getInputBuffer
                    val currentCodec = decoder ?: run {
                        frame.recycle()
                        return
                    }
                    if (currentCodec !== codec) {
                        Log.w(logFrom, "[Stream Client] ⚠️ [RACE CONDITION] Decoder was replaced during input buffer dequeue - aborting frame processing")
                        frame.recycle()
                        return
                    }
                    
                    val inBuf = currentCodec.getInputBuffer(inputIndex)
                    if (inBuf == null) {
                        Log.w(logFrom, "[Stream Client] Input buffer is null")
                        frame.recycle()
                        return
                    }
                    
                    inBuf.clear()
                    if (frame.length > inBuf.capacity()) {
                        Log.w(
                            logFrom,
                            "Decoder input buffer too small: frame=${frame.length}, cap=${inBuf.capacity()} (dropping, waiting for keyframe)"
                        )
                        waitingForKeyframe = true
                        resetDecoderInternal()
                        frame.recycle()
                        return
                    }
                    
                    inBuf.put(frame.data, 0, frame.length)
                    // Use server-side timestamp for PTS to sync with audio (which also uses server timestamp).
                    // This is critical for A/V synchronization.
                    val ptsUs = frame.sentTimeUs
                    val flags = if (frame.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    
                    // CRITICAL: Log frame queuing to decoder for debugging
                    if (frame.isKeyFrame) {
                        Log.d(logFrom, "[Stream Client] Queueing KEYFRAME for decode: ${frame.length} bytes, pts=$ptsUs")
                    }
                    
                    // Re-check decoder before queueInputBuffer
                    val codecBeforeQueue = decoder ?: run {
                        frame.recycle()
                        return
                    }
                    if (codecBeforeQueue !== codec) {
                        Log.w(logFrom, "[Stream Client] ⚠️ [RACE CONDITION] Decoder was replaced before queueInputBuffer - aborting frame processing")
                        frame.recycle()
                        return
                    }
                    
                    codecBeforeQueue.queueInputBuffer(inputIndex, 0, frame.length, ptsUs, flags)
                    frame.recycle() // Done with buffer
                    
                    if (frame.isKeyFrame) {
                        // CRITICAL STARTUP RULE:
                        // Only after a keyframe is successfully queued into MediaCodec do we allow decoding of P-frames.
                        // This prevents "green/junk" output on devices that cannot decode deltas without a valid reference.
                        if (waitingForKeyframe) {
                            try {
                                Log.d(
                                    logFrom,
                                    "🔵 [DECODER] Keyframe queued successfully; clearing waitingForKeyframe and dropping any stale pre-IDR frames (queueSize=${decodeQueue.size})"
                                )
                                waitingForKeyframe = false
                                skipCount = 0
                                // Drop any frames received before this IDR; they reference earlier GOP state and can decode as green.
                                clearDecodeQueue()
                            } catch (t: Throwable) {
                                Log.w(logFrom, "[Stream Client] Failed to clear pre-IDR decode queue (non-fatal)", t)
                            }
                        }
                        queuedKeyframeSinceReset = true
                        if (firstQueuedKeyframePtsUsSinceReset < 0L) {
                            firstQueuedKeyframePtsUsSinceReset = ptsUs
                            if (isNordCe4Device) {
                                // Drop a few initial output buffers right after the first IDR is queued.
                                // This reduces the chance of a "green frame" being rendered as the first visible output.
                                nordCe4OutputWarmupDropsRemaining = 30
                                // Also suppress rendering for a short time window after first IDR is queued.
                                // Time-based gating is more reliable than a small fixed frame count on some firmwares.
                                val nowMs = android.os.SystemClock.uptimeMillis()
                                nordCe4SuppressRenderUntilUptimeMs = nowMs + 1500L
                                Log.w(
                                    logFrom,
                                    "⚠️ [NORD_CE4] Decoder warmup enabled: firstKeyPtsUs=$ptsUs, willDropOutputs=$nordCe4OutputWarmupDropsRemaining, suppressRenderUntilMs=$nordCe4SuppressRenderUntilUptimeMs (now=$nowMs)"
                                )
                            }
                        }
                    }
                } else {
                    // CRITICAL DIAGNOSTIC: Log when dequeueInputBuffer fails
                    // This can happen if input buffers are full or decoder is stuck
                    Log.w(logFrom, "[Stream Client] ⚠️ [DECODER] dequeueInputBuffer failed: returned $inputIndex (decoder may be full or stuck)")
                    frame.recycle() // Recycle frame if it couldn't be queued
                }

                /* =========================
                 * MediaCodec OUTPUT
                 * ========================= */
                val bufferInfo = MediaCodec.BufferInfo()
                var outputDrainCount = 0
                while (true) {
                    // CRITICAL FIX: Re-check decoder before each dequeueOutputBuffer call
                    // This prevents native crash if decoder was destroyed by resetDecoderInternal()
                    val currentCodecForOutput = decoder ?: break
                    if (currentCodecForOutput !== codec) {
                        Log.w(logFrom, "[Stream Client] ⚠️ [RACE CONDITION] Decoder was replaced during output drain - stopping output processing")
                        break
                    }
                    // Use non-zero timeout to allow other operations, but keep it low for responsiveness
                    val outputIndex = try {
                        currentCodecForOutput.dequeueOutputBuffer(bufferInfo, DECODE_TIMEOUT_US)
                    } catch (e: IllegalStateException) {
                        // CRITICAL FIX: Handle case where decoder was destroyed/released during dequeueOutputBuffer
                        Log.w(logFrom, "[Stream Client] ⚠️ [RACE CONDITION] dequeueOutputBuffer failed - decoder may have been destroyed: ${e.message}")
                        break
                    } catch (e: Exception) {
                        // Handle any other unexpected exceptions
                        Log.w(logFrom, "[Stream Client] ⚠️ [DECODER] Unexpected exception in dequeueOutputBuffer: ${e.message}", e)
                        break
                    }
                    outputDrainCount++
                    when {
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            // CRITICAL DIAGNOSTIC: Log when no output is available
                            // This is normal, but helps diagnose if decoder is producing output
                            if (outputDrainCount == 1) {
                                // Only log first attempt to avoid spam
                                Log.d(logFrom, "[Stream Client] 🔵 [DECODER OUTPUT] dequeueOutputBuffer returned INFO_TRY_AGAIN_LATER (no output available yet)")
                            }
                            break
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            // CRITICAL FIX: Re-check decoder before accessing outputFormat
                            val codecForFormat = decoder ?: break
                            if (codecForFormat !== codec) {
                                Log.w(logFrom, "[Stream Client] ⚠️ [RACE CONDITION] Decoder was replaced during format change - aborting")
                                break
                            }
                            val fmt = try {
                                codecForFormat.outputFormat
                            } catch (e: IllegalStateException) {
                                Log.w(logFrom, "[Stream Client] ⚠️ [RACE CONDITION] outputFormat failed - decoder may have been destroyed: ${e.message}")
                                break
                            } catch (e: Exception) {
                                Log.w(logFrom, "[Stream Client] ⚠️ [DECODER] Unexpected exception getting outputFormat: ${e.message}", e)
                                break
                            }
                            // UNCONDITIONAL LOG: Critical decoder event - confirms decoder output format is available
                            Log.d(logFrom, "[Stream Client] 🔵 [DECODER EVENT] INFO_OUTPUT_FORMAT_CHANGED received! Format: $fmt")
                            
                            // CRITICAL: Log all format keys for diagnostics
                            try {
                                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: "unknown"
                                val colorFormat = if (fmt.containsKey(MediaFormat.KEY_COLOR_FORMAT)) {
                                    fmt.getInteger(MediaFormat.KEY_COLOR_FORMAT)
                                } else -1
                                val width = if (fmt.containsKey(MediaFormat.KEY_WIDTH)) fmt.getInteger(MediaFormat.KEY_WIDTH) else -1
                                val height = if (fmt.containsKey(MediaFormat.KEY_HEIGHT)) fmt.getInteger(MediaFormat.KEY_HEIGHT) else -1
                                
                                Log.d(logFrom, "[Stream Client] 🔵 [DECODER] Output format details: mime=$mime, colorFormat=$colorFormat, dimensions=${width}x${height}")
                                
                                // NOTE:
                                // Some OEM decoders expose vendor-specific output color formats (e.g. 261 on some Samsung stacks)
                                // that may render black on SurfaceView even when MediaCodec is producing output.
                                if (colorFormat == 261) {
                                    Log.w(logFrom, "[Stream Client] ⚠️ [DECODER] Vendor output color format detected (colorFormat=261). If preview is black, disable SurfaceView (use TextureView).")
                                    if (DeviceQuirks.forceTextureViewForBlackSurfaceView()) {
                                        Log.w(logFrom, "[Stream Client] ⚠️ [DECODER] This device is in the known-bad SurfaceView list; TextureView is recommended.")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(logFrom, "[Stream Client] ⚠️ [DECODER] Could not extract format details", e)
                            }

                            // MediaCodec output size is authoritative (derived from SPS). It can differ from the
                            // negotiated SET_STREAM size on some devices / encoder profiles.
                            // If we keep using the negotiated size for transforms/buffer sizing, the preview can look
                            // "zoomed" or mis-framed even when aspect ratios match.
                            val fmtW =
                                if (fmt.containsKey(MediaFormat.KEY_WIDTH)) fmt.getInteger(MediaFormat.KEY_WIDTH) else -1
                            val fmtH =
                                if (fmt.containsKey(MediaFormat.KEY_HEIGHT)) fmt.getInteger(MediaFormat.KEY_HEIGHT) else -1

                            val hasCrop =
                                fmt.containsKey(KEY_CROP_LEFT) &&
                                    fmt.containsKey(KEY_CROP_TOP) &&
                                    fmt.containsKey(KEY_CROP_RIGHT) &&
                                    fmt.containsKey(KEY_CROP_BOTTOM)
                            if (hasCrop) {
                                val l = fmt.getInteger(KEY_CROP_LEFT)
                                val t = fmt.getInteger(KEY_CROP_TOP)
                                val r = fmt.getInteger(KEY_CROP_RIGHT)
                                val b = fmt.getInteger(KEY_CROP_BOTTOM)
                                Log.d(logFrom, "[Stream Client] Decoder output format changed crop=$l,$t,$r,$b")
                                // Use the *coded* size (KEY_WIDTH/KEY_HEIGHT) for SurfaceTexture buffer sizing,
                                // but the *crop rect* for visible content. Some decoders report aligned coded width
                                // (e.g. 1088) with cropRight=1079 (visible 1080).
                                val codedW = if (fmtW > 0) fmtW else (r - l + 1)
                                val codedH = if (fmtH > 0) fmtH else (b - t + 1)
                                if (codedW > 0 && codedH > 0) {
                                    val sizeChanged = codedW != activeWidth || codedH != activeHeight
                                    if (sizeChanged) {
                                        val prevW = activeWidth
                                        val prevH = activeHeight
                                        activeWidth = codedW
                                        activeHeight = codedH
                                        Log.d(
                                            logFrom,
                                            "Decoder output format size changed to ${codedW}x${codedH} (was ${prevW}x${prevH})"
                                        )
                                        // Update lastRequestedConfig if it's null or doesn't match
                                        if (lastRequestedConfig == null || lastRequestedConfig!!.width != codedW || lastRequestedConfig!!.height != codedH) {
                                            lastRequestedConfig = StreamConfig(codedW, codedH, lastRequestedConfig?.bitrate ?: 0, lastRequestedConfig?.fps ?: 30)
                                            Log.d(logFrom, "[Stream Client] Updated lastRequestedConfig from decoder output: ${codedW}x${codedH}")
                                        }
                                    }
                                    // IMPORTANT: Size must be posted BEFORE crop so UI doesn't reset crop after we set it.
                                    // Also post size at least once even if it didn't change (prevents UI from staying on defaults).
                                    if (!postedDecoderFormatSize || sizeChanged) {
                                        postedDecoderFormatSize = true
                                        postVideoSize(codedW, codedH)
                                    }
                                }
                                postVideoCrop(l, t, r, b)
                            } else {
                                Log.d(logFrom, "[Stream Client] Decoder output format changed (no crop keys)")
                                if (fmtW > 0 && fmtH > 0) {
                                    val sizeChanged = fmtW != activeWidth || fmtH != activeHeight
                                    if (sizeChanged) {
                                        val prevW = activeWidth
                                        val prevH = activeHeight
                                        activeWidth = fmtW
                                        activeHeight = fmtH
                                        Log.d(
                                            logFrom,
                                            "Decoder output format size changed to ${fmtW}x${fmtH} (was ${prevW}x${prevH})"
                                        )
                                        // Update lastRequestedConfig if it's null or doesn't match
                                        if (lastRequestedConfig == null || lastRequestedConfig!!.width != fmtW || lastRequestedConfig!!.height != fmtH) {
                                            lastRequestedConfig = StreamConfig(fmtW, fmtH, lastRequestedConfig?.bitrate ?: 0, lastRequestedConfig?.fps ?: 30)
                                            Log.d(logFrom, "[Stream Client] Updated lastRequestedConfig from decoder output: ${fmtW}x${fmtH}")
                                        }
                                    }
                                    if (!postedDecoderFormatSize || sizeChanged) {
                                        postedDecoderFormatSize = true
                                        postVideoSize(fmtW, fmtH)
                                    }
                                }
                                if (fmtW > 0 && fmtH > 0) {
                                    postVideoCrop(0, 0, fmtW - 1, fmtH - 1)
                                } else {
                                    postVideoCrop(0, 0, -1, -1)
                                }
                            }
                        }
                        outputIndex >= 0 -> {
                            // Release all valid frames to surface
                            // MediaCodec handles PTS internally, we just need to release valid frames
                            // IMPORTANT: For Surface decoding, MediaCodec often reports bufferInfo.size == 0 even for
                            // perfectly valid frames (because the actual pixel data is rendered directly to the Surface).
                            // If we gate rendering on size>0, we will show a black screen on many devices.
                            val surface = outputSurface
                            val surfaceValid = surface != null && surface.isValid
                            val isOutputKeyFrame =
                                bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            val eos =
                                bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            val nowUptimeMs = android.os.SystemClock.uptimeMillis()
                            val sinceRxMs =
                                if (lastFrameRxUptimeMs > 0L) (nowUptimeMs - lastFrameRxUptimeMs) else Long.MAX_VALUE

                            // UNCONDITIONAL LOG: Always log buffer release - confirms decoder is producing output.
                            Log.d(
                                logFrom,
                                "🔵 [DECODER] Releasing output buffer: index=$outputIndex, size=${bufferInfo.size}, pts=${bufferInfo.presentationTimeUs}, flags=${bufferInfo.flags}, eos=$eos, isKeyFrame=$isOutputKeyFrame, surfaceValid=$surfaceValid, surfaceReady=$surfaceReady"
                            )

                            // CRITICAL: Don't render anything until we've actually queued a keyframe into the decoder.
                            // This prevents "green/junk" output that some hardware decoders produce before the first IDR.
                            if (!queuedKeyframeSinceReset) {
                                // CRITICAL FIX: Re-check decoder before releaseOutputBuffer
                                val codecForRelease = decoder ?: break
                                if (codecForRelease !== codec) {
                                    Log.w(logFrom, "[Stream Client] ⚠️ [RACE CONDITION] Decoder was replaced before releaseOutputBuffer - aborting")
                                    break
                                }
                                try {
                                    codecForRelease.releaseOutputBuffer(outputIndex, false)
                                } catch (e: IllegalStateException) {
                                    Log.w(logFrom, "[Stream Client] ⚠️ [RACE CONDITION] releaseOutputBuffer failed - decoder may have been destroyed: ${e.message}")
                                    break
                                }
                                break
                            }

                            // Nord CE4-specific: drop a few output buffers immediately after the first queued IDR,
                            // and never render output with PTS older than the first queued IDR PTS.
                            if (isNordCe4Device) {
                                val firstKeyPtsUs = firstQueuedKeyframePtsUsSinceReset
                                if (firstKeyPtsUs > 0L && bufferInfo.presentationTimeUs in 0 until firstKeyPtsUs) {
                                    Log.w(
                                        logFrom,
                                        "⚠️ [NORD_CE4] Dropping output (old PTS): outPtsUs=${bufferInfo.presentationTimeUs} < firstKeyPtsUs=$firstKeyPtsUs"
                                    )
                                    // CRITICAL FIX: Safe release with decoder re-check
                                    val codecForRelease = decoder ?: break
                                    if (codecForRelease !== codec) break
                                    try { codecForRelease.releaseOutputBuffer(outputIndex, false) } catch (e: IllegalStateException) { break }
                                    break
                                }
                                val suppressUntil = nordCe4SuppressRenderUntilUptimeMs
                                if (suppressUntil > 0L && nowUptimeMs < suppressUntil) {
                                    // Do not render during the initial unstable window; this prevents a green flash.
                                    if (outputDrainCount <= 10 || outputDrainCount % 30 == 0) {
                                        Log.w(
                                            logFrom,
                                            "⚠️ [NORD_CE4] Suppressing render during warmup window: now=$nowUptimeMs < until=$suppressUntil (render=false)"
                                        )
                                    }
                                    // CRITICAL FIX: Safe release with decoder re-check
                                    val codecForRelease = decoder ?: break
                                    if (codecForRelease !== codec) break
                                    try { codecForRelease.releaseOutputBuffer(outputIndex, false) } catch (e: IllegalStateException) { break }
                                    break
                                }
                                val drops = nordCe4OutputWarmupDropsRemaining
                                if (drops > 0) {
                                    nordCe4OutputWarmupDropsRemaining = drops - 1
                                    Log.w(
                                        logFrom,
                                        "⚠️ [NORD_CE4] Dropping output for warmup: remaining=${drops - 1}"
                                    )
                                    // CRITICAL FIX: Safe release with decoder re-check
                                    val codecForRelease = decoder ?: break
                                    if (codecForRelease !== codec) break
                                    try { codecForRelease.releaseOutputBuffer(outputIndex, false) } catch (e: IllegalStateException) { break }
                                    break
                                }
                            }

                            // CRITICAL: If the stream has stalled (no new frames received) but the decoder still outputs
                            // buffers, rendering those buffers can produce a "stuck green" preview on some devices.
                            // In that case, drop output without rendering. The watchdog will downgrade to CONNECTED.
                            if (sinceRxMs >= 2_000L) {
                                Log.w(
                                    logFrom,
                                    "⚠️ [DECODER] Dropping output render due to stalled input: sinceRxMs=$sinceRxMs (prevents green/stale preview)"
                                )
                                // CRITICAL FIX: Safe release with decoder re-check
                                val codecForRelease = decoder ?: break
                                if (codecForRelease !== codec) break
                                try { codecForRelease.releaseOutputBuffer(outputIndex, false) } catch (e: IllegalStateException) { break }
                                break
                            }

                            if (!surfaceValid) {
                                // Surface invalid - cannot render.
                                Log.e(
                                    logFrom,
                                    "🔴 [DECODER] CRITICAL: Surface is NULL or INVALID! Dropping output buffer (render=false)."
                                )
                                // CRITICAL FIX: Safe release with decoder re-check
                                val codecForRelease = decoder ?: break
                                if (codecForRelease !== codec) break
                                try { codecForRelease.releaseOutputBuffer(outputIndex, false) } catch (e: IllegalStateException) { break }
                                break
                            }

                            if (!postedFirstFrameRendered) {
                                Log.d(
                                    logFrom,
                                    "🔵 [DECODER] First frame: releaseOutputBuffer(render=true) with valid Surface (size=${bufferInfo.size}). Frame should render now."
                                )
                            }

                            // Render to the Surface regardless of bufferInfo.size (see note above).
                            if (firstRenderAttemptUptimeMs == 0L) {
                                firstRenderAttemptUptimeMs = nowUptimeMs
                                Log.d(
                                    logFrom,
                                    "🔵 [DECODER] First render attempt started: uptimeMs=$firstRenderAttemptUptimeMs (postedFirstFrameRendered=$postedFirstFrameRendered, isNordCe4=$isNordCe4Device)"
                                )
                            }
                            // CRITICAL FIX: Re-check decoder before final releaseOutputBuffer with render=true
                            val codecForFinalRelease = decoder ?: break
                            if (codecForFinalRelease !== codec) {
                                Log.w(logFrom, "[Stream Client] ⚠️ [RACE CONDITION] Decoder was replaced before final releaseOutputBuffer - aborting")
                                break
                            }
                            // A/V Sync Logic
                            if (latestPlayedAudioTsUs > 0) {
                                val diff = bufferInfo.presentationTimeUs - latestPlayedAudioTsUs
                                // If video is ahead of audio (diff > 0), wait to maintain sync.
                                // If video is behind (diff < 0), render immediately (no wait).
                                if (diff > 0) {
                                     // Cap wait time to avoid freezes/stalls (e.g. max 40ms)
                                     val waitMs = (diff / 1000).coerceAtMost(40)
                                     if (waitMs > 2) {
                                         try { Thread.sleep(waitMs) } catch(_:Exception){}
                                     }
                                }
                            }

                            try {
                                codecForFinalRelease.releaseOutputBuffer(outputIndex, true)
                            } catch (e: IllegalStateException) {
                                Log.w(logFrom, "[Stream Client] ⚠️ [RACE CONDITION] releaseOutputBuffer(render=true) failed - decoder may have been destroyed: ${e.message}")
                                break
                            }
                            // Mark render activity for watchdog/state correctness.
                            lastFrameRenderUptimeMs = android.os.SystemClock.uptimeMillis()
                            // UI-state correctness:
                            // We can legitimately enter RECOVERING during stalls/reconfigure windows even while the TCP
                            // connection stays alive. If we are visibly rendering again, we must promote back to STREAMING,
                            // otherwise the UI can show video while state remains RECOVERING.
                            try {
                                if (currentState == ConnectionState.RECOVERING) {
                                    Log.d(logFrom, "[Stream Client] ✅ [STATE] Frame rendered while RECOVERING -> posting STREAMING")
                                    postState(ConnectionState.STREAMING)
                                }
                            } catch (t: Throwable) {
                                Log.w(logFrom, "[Stream Client] Failed to promote RECOVERING->STREAMING on render (non-fatal)", t)
                            }

                            if (!postedFirstFrameRendered) {
                                if (isNordCe4Device) {
                                    // Only "reveal" after warmup gates are cleared and we have a few rendered frames.
                                    // This prevents revealing the Surface while the device is still outputting green.
                                    val suppressUntil = nordCe4SuppressRenderUntilUptimeMs
                                    val dropsRemaining = nordCe4OutputWarmupDropsRemaining
                                    if ((suppressUntil <= 0L || nowUptimeMs >= suppressUntil) && dropsRemaining <= 0) {
                                        nordCe4RenderedFramesAfterWarmup++
                                    }
                                    if (nordCe4RenderedFramesAfterWarmup >= 5) {
                                        postedFirstFrameRendered = true
                                        Log.d(
                                            logFrom,
                                            "🔵 [DECODER] Nord CE4: stable frames reached ($nordCe4RenderedFramesAfterWarmup) -> invoking onFirstFrameRendered"
                                        )
                                        try {
                                            if (currentState != ConnectionState.STREAMING) {
                                                Log.d(logFrom, "[Stream Client] ✅ [STATE] First stable rendered frames -> posting STREAMING (currentState=$currentState)")
                                                postState(ConnectionState.STREAMING)
                                            }
                                        } catch (t: Throwable) {
                                            Log.w(logFrom, "[Stream Client] Failed to post STREAMING on first stable render (non-fatal)", t)
                                        }
                                        mainHandler.post {
                                            Log.d(logFrom, "[Stream Client] 🔵 [DECODER] onFirstFrameRendered callback running on main thread")
                                            onFirstFrameRendered()
                                        }
                                    } else {
                                        // Fallback safety net:
                                        // If we have been rendering for a while after warmup gates cleared but still
                                        // haven't "revealed" (e.g., due to rare counter/reset edge-cases), force-reveal
                                        // so the UI doesn't get stuck on "Starting stream".
                                        val warmupCleared =
                                            (suppressUntil <= 0L || nowUptimeMs >= suppressUntil) &&
                                                nordCe4OutputWarmupDropsRemaining <= 0
                                        val renderAttemptAgeMs =
                                            if (firstRenderAttemptUptimeMs > 0L) (nowUptimeMs - firstRenderAttemptUptimeMs) else 0L
                                        if (warmupCleared && renderAttemptAgeMs >= 2_000L) {
                                            postedFirstFrameRendered = true
                                            Log.w(
                                                logFrom,
                                                "⚠️ [NORD_CE4] Fallback reveal: forcing first-frame rendered after ${renderAttemptAgeMs}ms of render attempts (stableFrames=$nordCe4RenderedFramesAfterWarmup)"
                                            )
                                            try {
                                                if (currentState != ConnectionState.STREAMING) {
                                                    Log.d(logFrom, "[Stream Client] ✅ [STATE] Fallback reveal -> posting STREAMING (currentState=$currentState)")
                                                    postState(ConnectionState.STREAMING)
                                                }
                                            } catch (t: Throwable) {
                                                Log.w(logFrom, "[Stream Client] Failed to post STREAMING on fallback reveal (non-fatal)", t)
                                            }
                                            mainHandler.post {
                                                Log.d(logFrom, "[Stream Client] 🔵 [DECODER] onFirstFrameRendered callback running on main thread (fallback reveal)")
                                                onFirstFrameRendered()
                                            }
                                        }

                                        if (nordCe4RenderedFramesAfterWarmup == 0) {
                                            Log.w(
                                                logFrom,
                                                "⚠️ [NORD_CE4] Rendered but not revealing yet: warmup not cleared (dropsRemaining=$dropsRemaining, now=$nowUptimeMs, suppressUntil=$suppressUntil)"
                                            )
                                        } else if (nordCe4RenderedFramesAfterWarmup <= 5) {
                                            Log.w(
                                                logFrom,
                                                "⚠️ [NORD_CE4] Rendered stable frame count=$nordCe4RenderedFramesAfterWarmup/5 (holding UI reveal)"
                                            )
                                        }
                                    }
                                } else {
                                    postedFirstFrameRendered = true
                                    Log.d(
                                        logFrom,
                                        "🔵 [DECODER] First frame rendered! Invoking onFirstFrameRendered callback"
                                    )
                                    // Now that we have proven real rendering, we can safely declare STREAMING.
                                    try {
                                        if (currentState != ConnectionState.STREAMING) {
                                            Log.d(logFrom, "[Stream Client] ✅ [STATE] First rendered frame -> posting STREAMING (currentState=$currentState)")
                                            postState(ConnectionState.STREAMING)
                                        }
                                    } catch (t: Throwable) {
                                        Log.w(logFrom, "[Stream Client] Failed to post STREAMING on first render (non-fatal)", t)
                                    }
                                    mainHandler.post {
                                        Log.d(logFrom, "[Stream Client] 🔵 [DECODER] onFirstFrameRendered callback running on main thread")
                                        onFirstFrameRendered()
                                    }
                                }
                            }

                            // Render FPS tracking and adaptive downgrade for smooth preview.
                            val nowMs = System.currentTimeMillis()
                            if (renderWindowStartMs == 0L) renderWindowStartMs = nowMs
                            renderFramesInWindow++
                            val elapsed = nowMs - renderWindowStartMs
                            if (elapsed >= 1000L) {
                                val fps = renderFramesInWindow * 1000.0 / elapsed.toDouble()
                                Log.d(
                                    logFrom,
                                    "Render fps=${String.format("%.1f", fps)} req=${lastRequestedConfig} (outInfo.size=${bufferInfo.size}) pts=${bufferInfo.presentationTimeUs}"
                                )
                                requestPerfDowngradeIfNeeded(fps)
                                renderWindowStartMs = nowMs
                                renderFramesInWindow = 0
                            }
                        }
                    }
                }
            }
        } catch (e: IllegalStateException) {
            // Decoder got into a bad state (often during surface transitions / rapid resets).
            // Try a lightweight flush first to reduce decoder churn. If flush fails, fall back to full reset.
            Log.w(logFrom, "[Stream Client] Decoder IllegalStateException; attempting flush-before-reset", e)
            waitingForKeyframe = true
            val flushed = tryFlushDecoderLocked(reason = "illegal_state")
            if (!flushed) {
                // Full reset fallback.
                resetDecoderInternal()
            }
            // Always request a fresh IDR after a decoder recovery path.
            requestKeyframe("decoder_illegal_state_recover")
        }
    }

    /**
     * Attempts to flush the decoder in-place (cheaper than full stop/release/recreate).
     *
     * Intended behavior:
     * - If the decoder is started and in a recoverable state, `flush()` clears internal buffers without
     *   tearing down codec resources.
     * - If flush is not supported or codec is in a bad state, return false so caller can recreate.
     *
     * This MUST be called when holding/under decoderLock (or from a context that guarantees decoder isn't
     * concurrently released).
     */
    private fun tryFlushDecoderLocked(reason: String): Boolean {
        synchronized(decoderLock) {
            val codec = decoder ?: return false
            return try {
                Log.w(logFrom, "[Stream Client] 🟡 [DECODER RECOVERY] Attempting codec.flush() (reason=$reason)")
                codec.flush()
                // After flush, require a new IDR to rebuild references.
                waitingForKeyframe = true
                droppedNonKeyWhileWaitingCount = 0
                lastDropNonKeyWhileWaitingLogMs = 0L
                queuedKeyframeSinceReset = false
                firstQueuedKeyframePtsUsSinceReset = -1L
                nordCe4OutputWarmupDropsRemaining = 0
                nordCe4SuppressRenderUntilUptimeMs = 0L
                nordCe4RenderedFramesAfterWarmup = 0
                postedFirstFrameRendered = false
                renderWindowStartMs = 0L
                renderFramesInWindow = 0
                clearDecodeQueue()
                Log.w(logFrom, "[Stream Client] 🟢 [DECODER RECOVERY] codec.flush() succeeded; waitingForKeyframe=true")
                true
            } catch (t: Throwable) {
                Log.w(logFrom, "[Stream Client] 🔴 [DECODER RECOVERY] codec.flush() failed; will recreate decoder", t)
                false
            }
        }
    }

    /* ===============================
      * Decoder lifecycle
      * =============================== */
    fun attachSurface(surface: android.view.Surface) {
        // Attach a rendering surface (SurfaceView/TextureView).
        //
        // IMPORTANT:
        // - Compose/SurfaceHolder can call surfaceCreated/surfaceChanged multiple times during layout.
        // - Our previous behavior reset the decoder on every attach, which can prevent MediaCodec from ever
        //   producing stable output (seen as "stuck on starting stream").
        // - We therefore dedupe repeated attaches of the same Surface.
        val surfaceValid = try {
            surface.isValid
        } catch (t: Throwable) {
            Log.w(logFrom, "[Stream Client] 🔴 [SURFACE ATTACH] Surface validity check failed", t)
            false
        }
        Log.d(logFrom, "[Stream Client] 🔍 [SURFACE ATTACH] attachSurface CALLED: valid=$surfaceValid, sameSurface=${outputSurface === surface}, surfaceReady=$surfaceReady, decoder=${decoder != null}")

        if (!surfaceValid) {
            Log.w(logFrom, "[Stream Client] 🔴 [SURFACE ATTACH] CRITICAL: Surface is INVALID! Decoder cannot render.")
            return
        }

        synchronized(decoderLock) {
            // Dedupe: if this exact Surface is already attached and decoder exists, do NOT reset.
            // This prevents infinite warmup / repeated resets on SurfaceView.surfaceChanged().
            if (surfaceReady && outputSurface === surface && decoder != null) {
                Log.w(logFrom, "[Stream Client] ⚠️ [SURFACE ATTACH] Duplicate attach of same Surface while decoder is active - skipping reset/reconfigure")
                // Still request a keyframe to accelerate visible output if we were mid-start.
                try {
                    requestKeyframe("surface_attach_duplicate")
                } catch (_: Throwable) {
                }
                return
            }

            outputSurface = surface
            surfaceReady = true

            // Reset decode/render state only when the Surface actually changes (or decoder was null).
            waitingForKeyframe = true
            droppedNonKeyWhileWaitingCount = 0
            lastDropNonKeyWhileWaitingLogMs = 0L
            queuedKeyframeSinceReset = false
            firstQueuedKeyframePtsUsSinceReset = -1L
            nordCe4OutputWarmupDropsRemaining = 0
            clearDecodeQueue()
            skipCount = 0

            // Reset decoder to bind it to the new surface.
            resetDecoderInternal()
        }
        
        // After surface is ready, try to start decoder if CSD and dimensions are available
        synchronized(decoderLock) {
            Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] Surface attached - checking decoder startup: csd0=${csd0 != null}, csd1=${csd1 != null}, lastRequestedConfig=${lastRequestedConfig?.let { "${it.width}x${it.height}" } ?: "null"}, activeWidth=$activeWidth, activeHeight=$activeHeight")
            if (decoder == null && csd0 != null && csd1 != null) {
                val accepted = getAcceptedDimsOrNull()
                if (accepted != null) {
                    Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] Surface attached, CSD ready, STREAM_ACCEPTED dims known (${accepted.first}x${accepted.second}) - attempting decoder start")
                    val started = startDecoderIfReady()
                    Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] startDecoderIfReady() after surface attach: $started")
                } else {
                    // Do NOT start decoder based on requested dims; wait for server truth to avoid mismatch loops.
                    Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] Surface attached, CSD ready but STREAM_ACCEPTED dims not known yet - waiting for STREAM_ACCEPTED")
                }
            } else {
                Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] Surface attached but cannot start decoder: decoder=${decoder != null}, csd0=${csd0 != null}, csd1=${csd1 != null}")
            }
        }
        
        // Reduce foreground resume delay: request a keyframe from server immediately.
        // NOTE: attachSurface() can happen while DISCONNECTED (Compose pre-creates the Surface).
        // requestKeyframe() is now guarded and will no-op unless connected.
        requestKeyframe("surface_attach")

    }

    fun detachSurface() {
        // Detach the current rendering surface.
        //
        // Intent:
        // - Stop rendering safely when the UI surface is destroyed/removed.
        // - Avoid unnecessary decoder resets if we're already detached (prevents duplicate work when both
        //   Compose onDispose and View callbacks call detachSurface()).
        var shouldReset = false
        synchronized(decoderLock) {
            val hadSurface = surfaceReady || outputSurface != null
            val hadDecoder = decoder != null
            shouldReset = hadSurface || hadDecoder
            surfaceReady = false
            outputSurface = null
        }
        Log.d(logFrom, "[Stream Client] Surface detached (resetDecoder=$shouldReset)")
        if (shouldReset) {
            resetDecoderInternal()
        }
    }

    /**
     * Start H.264 decoder if prerequisites are satisfied.
     *
     * Contract:
     * - Decoder MUST be configured using STREAM_ACCEPTED dimensions (server truth).
     * - `forceWidth/forceHeight` is used when STREAM_ACCEPTED just arrived but lastAcceptedWidth/Height
     *   have not yet been committed (comparison logic still needs the previous accepted dims).
     */
    private fun startDecoderIfReady(forceWidth: Int? = null, forceHeight: Int? = null): Boolean {
        synchronized(decoderLock) {
            if (!surfaceReady) {
                Log.w(logFrom, "[Stream Client] 🔴 [DECODER] startDecoderIfReady: FAILED - surfaceReady=false, cannot start decoder. Surface attachment may not have completed yet.")
                return false
            }
            if (decoder != null) {
                Log.d(logFrom, "[Stream Client] 🔵 [DECODER] startDecoderIfReady: decoder already exists, returning true")
                return true
            }

            val surface = outputSurface
            if (surface == null) {
                Log.d(logFrom, "[Stream Client] 🔵 [DECODER] startDecoderIfReady: outputSurface is null, cannot start")
                return false
            }
            
            // Choose server-accepted dimensions.
            val forcedW = forceWidth ?: 0
            val forcedH = forceHeight ?: 0
            val accepted = if (forcedW > 0 && forcedH > 0) (forcedW to forcedH) else getAcceptedDimsOrNull()
            if (accepted == null) {
                Log.w(logFrom, "[Stream Client] 🔴 [DECODER] startDecoderIfReady: Waiting for STREAM_ACCEPTED dimensions (server truth) before configuring decoder")
                return false
            }
            val decoderWidth = accepted.first
            val decoderHeight = accepted.second
            
            Log.d(logFrom, "[Stream Client] 🔵 [DECODER] startDecoderIfReady: decoderWidth=$decoderWidth, decoderHeight=$decoderHeight (from STREAM_ACCEPTED)")
            
            if (decoderWidth <= 0 || decoderHeight <= 0) {
                Log.w(logFrom, "[Stream Client] 🔴 [DECODER] Cannot configure decoder: invalid dimensions (w=$decoderWidth, h=$decoderHeight)")
                return false
            }
            
            // CSD must be available to configure decoder
            val sps = csd0
            val pps = csd1
            if (sps == null || pps == null) {
                Log.w(logFrom, "[Stream Client] 🔴 [DECODER] Cannot configure decoder: CSD not available yet (csd0=${csd0 != null}, csd1=${csd1 != null})")
                return false
            }
            
            // Configure decoder with negotiated dimensions and CSD
            // MediaCodec will derive actual size from SPS, but we need valid initial dimensions
            val format = MediaFormat.createVideoFormat("video/avc", decoderWidth, decoderHeight).apply {
                val spsHex = sps.take(8).joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }
                val ppsHex = pps.take(8).joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }
                Log.d(logFrom, "[Stream Client] 🔍 [CSD CHECK] Configuring decoder with CSD: SPS=$spsHex..., PPS=$ppsHex...")
                
                setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            }

            val wasWaitingForKeyframe = waitingForKeyframe
            val surfaceValid = surface.isValid
            Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] startDecoderIfReady: wasWaitingForKeyframe=$wasWaitingForKeyframe before decoder creation, decoder=${decoder != null}, surfaceReady=$surfaceReady, surfaceValid=$surfaceValid")
            
            if (!surfaceValid) {
                Log.w(logFrom, "[Stream Client] 🔴 [DECODER] CRITICAL: Surface is INVALID when configuring decoder! Decoder will not render frames to TextureView. onSurfaceTextureUpdated will not be called.")
            }
            
            try {
                decoder = MediaCodec.createDecoderByType("video/avc").apply {
                    // CRITICAL: Configure decoder with Surface for hardware-accelerated rendering
                    configure(
                        format, surface, null, 0
                    )

                    // Track configured dimensions for later mismatch detection.
                    // This is used by STREAM_ACCEPTED to decide whether we must force a reset/restart.
                    decoderConfiguredWidth = decoderWidth
                    decoderConfiguredHeight = decoderHeight
                    Log.d(
                        logFrom,
                        "🔍 [DECODER] Tracked configured dims: ${decoderConfiguredWidth}x${decoderConfiguredHeight}"
                    )
                    
                    // Get actual output format after configuration to verify dimensions
                    try {
                        val outputFormat = outputFormat
                        val outputWidth = outputFormat.getInteger(MediaFormat.KEY_WIDTH)
                        val outputHeight = outputFormat.getInteger(MediaFormat.KEY_HEIGHT)
                        Log.d(logFrom, "[Stream Client] 🔍 [DECODER] Decoder output format: ${outputWidth}x${outputHeight} (configured ${decoderWidth}x${decoderHeight})")
                    } catch (e: Exception) {
                        Log.w(logFrom, "[Stream Client] ⚠️ [DECODER] Could not get output format immediately after configure", e)
                    }
                    
                    try {
                        setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                        Log.d(
                            logFrom,
                            "🔍 [DECODER] Decoder configured ${decoderWidth}x${decoderHeight} (source=STREAM_ACCEPTED), scaling=FIT"
                        )
                    } catch (t: Throwable) {
                        Log.w(logFrom, "[Stream Client] 🔴 [DECODER] Decoder scaling mode set failed", t)
                    }
                    
                    start()
                    Log.d(logFrom, "[Stream Client] 🔍 [DECODER] Decoder.start() called successfully - decoder is now running and ready to decode frames")
                }
                // IMPORTANT:
                // Do NOT clear waitingForKeyframe here.
                // Green/garbled frames often happen if we decode before the first IDR arrives after a pause/loss.
                // Keep waitingForKeyframe=true and only clear it once we actually start decoding from a keyframe.
                //
                // Also do NOT post STREAMING just because the decoder started.
                // We only transition to STREAMING after the first frame is actually rendered to the surface.
                Log.d(
                    logFrom,
                    "🔍 [CSD DIAGNOSTIC] Decoder started. Keep waitingForKeyframe=$waitingForKeyframe; currentState=$currentState. STREAMING will be posted on first rendered frame."
                )
                return true
            } catch (e: IllegalArgumentException) {
                Log.w(logFrom, "[Stream Client] Failed to configure decoder: ${e.message}", e)
                decoder = null
                return false
            } catch (e: Exception) {
                Log.w(logFrom, "[Stream Client] Failed to start decoder", e)
                decoder = null
                return false
            }
        }
    }

    private fun resetDecoderInternal() {
        synchronized(decoderLock) {
            // CRITICAL FIX: Log decoder reset to help diagnose race conditions
            val decoderExists = decoder != null
            Log.d(logFrom, "[Stream Client] 🔵 [DECODER RESET] resetDecoderInternal() called - decoder exists: $decoderExists, thread: ${Thread.currentThread().name}")
            try {
                if (decoderExists) {
                    Log.d(logFrom, "[Stream Client] 🔵 [DECODER RESET] Stopping and releasing decoder")
                    decoder?.stop()
                    decoder?.release()
                }
            } catch (e: Exception) {
                Log.w(logFrom, "[Stream Client] ⚠️ [DECODER RESET] Exception during decoder stop/release: ${e.message}", e)
            } finally {
                if (decoderExists) {
                    Log.d(logFrom, "[Stream Client] 🔵 [DECODER RESET] Decoder set to null")
                }
                decoder = null
                waitingForKeyframe = true
                droppedNonKeyWhileWaitingCount = 0
                lastDropNonKeyWhileWaitingLogMs = 0L
                queuedKeyframeSinceReset = false
                firstQueuedKeyframePtsUsSinceReset = -1L
                nordCe4OutputWarmupDropsRemaining = 0
                nordCe4SuppressRenderUntilUptimeMs = 0L
                nordCe4RenderedFramesAfterWarmup = 0
                postedDecoderFormatSize = false
                postedFirstFrameRendered = false
                decoderConfiguredWidth = 0
                decoderConfiguredHeight = 0
                perfLevel = 0
                renderWindowStartMs = 0L
                renderFramesInWindow = 0
                // Reset downgrade flag when decoder is reset to allow future downgrades if needed
                downgraded = false
                clearDecodeQueue()
            }
        }
    }

    /**
     * Reads exactly buffer.size bytes from the socket input stream.
     * Handles socket closure and connection resets gracefully.
     * 
     * @throws java.net.SocketException if socket is closed or connection is reset
     * @throws java.net.SocketException if input stream is not available
     */
    private fun readFully(buffer: ByteArray) {
        var offset = 0
        // Use synchronized access to prevent race conditions with closeSocket()
        val src = synchronized(inputLock) {
            input ?: throw java.net.SocketException("Socket input not available")
        }
        
        // Check if socket is still connected before reading
        val currentSocket = socket
        if (currentSocket == null || currentSocket.isClosed) {
            throw java.net.SocketException("Socket is closed")
        }
        
        try {
            while (offset < buffer.size) {
                val read = src.read(buffer, offset, buffer.size - offset)
                if (read < 0) {
                    throw java.net.SocketException("Socket closed during read")
                }
                offset += read
            }
        } catch (e: java.net.SocketException) {
            // Re-throw socket exceptions as-is (connection reset, closed, etc.)
            throw e
        } catch (e: java.io.IOException) {
            // Wrap other IO exceptions as SocketException for consistent handling
            throw java.net.SocketException("IO error during read: ${e.message}").apply {
                initCause(e)
            }
        }
    }

    private fun readFullyFromSocket(buffer: ByteArray) = readFully(buffer)
    
    /**
     * Read exactly 'length' bytes from socket into buffer starting at index 0.
     * Used for efficient frame draining when decoding is paused.
     * The buffer must be at least 'length' bytes in size.
     * Handles socket closure and connection resets gracefully.
     * 
     * @throws java.net.SocketException if socket is closed or connection is reset
     * @throws java.net.SocketException if input stream is not available
     */
    private fun readFullyFromSocket(buffer: ByteArray, length: Int) {
        var offset = 0
        // Use synchronized access to prevent race conditions with closeSocket()
        val src = synchronized(inputLock) {
            input ?: throw java.net.SocketException("Socket input not available")
        }
        
        // Check if socket is still connected before reading
        val currentSocket = socket
        if (currentSocket == null || currentSocket.isClosed) {
            throw java.net.SocketException("Socket is closed")
        }
        
        try {
            while (offset < length) {
                val read = src.read(buffer, offset, length - offset)
                if (read < 0) {
                    throw java.net.SocketException("Socket closed during read")
                }
                offset += read
            }
        } catch (e: java.net.SocketException) {
            // Re-throw socket exceptions as-is (connection reset, closed, etc.)
            throw e
        } catch (e: java.io.IOException) {
            // Wrap other IO exceptions as SocketException for consistent handling
            throw java.net.SocketException("IO error during read: ${e.message}").apply {
                initCause(e)
            }
        }
    }

    /**
     * Reads a line from the socket input stream.
     * Handles socket closure and connection resets gracefully.
     * 
     * @return the line read, or null if socket is closed or connection is reset
     */
    private fun readLineFromSocket(): String? {
        // Use synchronized access to prevent race conditions with closeSocket()
        val src = synchronized(inputLock) {
            input ?: return null
        }
        
        // Check if socket is still connected before reading
        val currentSocket = socket
        if (currentSocket == null || currentSocket.isClosed) {
            return null
        }
        
        try {
            val out = StringBuilder(64)
            while (true) {
                val b = src.read()
                if (b == -1) return if (out.isEmpty()) null else out.toString()
                if (b == '\n'.code) break
                if (b != '\r'.code) out.append(b.toChar())
            }
            return out.toString()
        } catch (e: java.net.SocketException) {
            // Connection reset or socket closed - return null to signal end of stream
            Log.d(logFrom, "🔵 [SOCKET] Socket exception in readLineFromSocket: ${e.message}")
            return null
        } catch (e: java.io.IOException) {
            // Other IO errors - return null to signal end of stream
            Log.d(logFrom, "🔵 [SOCKET] IO exception in readLineFromSocket: ${e.message}")
            return null
        }
    }

    private fun requestKeyframe(reason: String) {
        val nowNs = System.nanoTime()
        val nowUptimeMs = android.os.SystemClock.uptimeMillis()

        // IMPORTANT:
        // Aggressive REQ_KEYFRAME spam can happen when Primary is not capturing (no frames),
        // because waitingForKeyframe stays true. That overloads Primary and creates unstable states.
        //
        // Policy:
        // - If we're CONNECTED (No Video), probe at low rate.
        // - If we've never received a frame yet, allow moderate probing.
        // - If we recently received frames and are waiting for an IDR, be somewhat aggressive but not 100ms spam.
        val sinceLastRxMs =
            if (lastFrameRxUptimeMs > 0L) (nowUptimeMs - lastFrameRxUptimeMs) else Long.MAX_VALUE
        val state = currentState

        // Never send protocol commands when not connected.
        // Surface can be attached before connect() due to Compose keeping SurfaceView alive.
        if (state == ConnectionState.DISCONNECTED || socket == null || out == null) {
            Log.d(logFrom, "[Stream Client] REQ_KEYFRAME skipped (not connected): reason=$reason state=$state socket=${socket != null} out=${out != null}")
            return
        }

        val throttleMs = when {
            state == ConnectionState.CONNECTED -> 5_000L
            !waitingForKeyframe -> 500L
            // Waiting for keyframe (e.g., after reset/corruption):
            // If stream is stalled / no capture, back off heavily.
            sinceLastRxMs >= 5_000L -> 2_000L
            sinceLastRxMs >= 2_000L -> 1_000L
            else -> 250L
        }

        val throttleNs = throttleMs * 1_000_000L
        if (nowNs - lastKeyReqNs > throttleNs) {
            send("REQ_KEYFRAME")
            lastKeyReqNs = nowNs
            Log.d(
                logFrom,
                "REQ_KEYFRAME reason=$reason (waiting=$waitingForKeyframe state=$state throttleMs=$throttleMs sinceLastRxMs=$sinceLastRxMs)"
            )
        }
    }

    private fun maybeDowngrade() {
        if (downgraded) return
        // Be very conservative: only downgrade if we've skipped MANY frames
        // This prevents downgrades during temporary issues (rotation, movement, network hiccups)
        // Increased threshold from 30 to 60 to be less aggressive
        if (skipCount >= 60) {
            val actualSkipCount = skipCount // Capture value before reset
            downgraded = true
            // Keep 4:3 portrait. 720x1280 (9:16) can introduce padding/bars when the Primary pipeline is 4:3.
            val low = StreamConfig(720, 960, 3_000_000, 30)
            sendCapabilities(maxWidth = low.width, maxHeight = low.height, maxBitrate = low.bitrate)
            sendSetStream(low)
            requestKeyframe("downgrade")
            skipCount = 0
            Log.w(logFrom, "[Stream Client] Downgrading due to excessive frame skips ($actualSkipCount), requesting ${low.width}x${low.height}")
        }
    }

    /* ===============================
     * Audio playback
     * =============================== */
    private fun ensureAudioTrack(rate: Int, ch: Int): AudioTrack {
        val existing = audioTrack
        // Recreate AudioTrack if sample rate or channel count changes to avoid distortion
        if (existing != null) {
            val existingRate = existing.sampleRate
            val existingChannels = if (existing.channelCount == 1) 1 else 2
            if (existingRate == rate && existingChannels == ch) {
                return existing
            } else {
                // Sample rate or channel count changed - release old track
                Log.d(logFrom, "[Stream Client] AudioTrack rate/channel changed: ${existingRate}Hz/$existingChannels -> ${rate}Hz/$ch, recreating")
                releaseAudioTrack()
            }
        }
        val channelCfg = if (ch == 1) AUDIO_CHANNEL_CONFIG_OUT else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(
            rate,
            channelCfg,
            AUDIO_FORMAT
        ).coerceAtLeast(AUDIO_BYTES_PER_FRAME * 2)
        // Use larger buffer to reduce underruns and improve stability
        val bufferSize = (minBuf * 2).coerceAtMost(rate / 10) // Max 100ms buffer
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(rate)
                    .setChannelMask(channelCfg)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSize)
            .build()
        track.play()
        audioTrack = track
        return track
    }

    /**
     * Release AAC decoder resources
     */
    private fun releaseAudioDecoder() {
        audioDecoding = false
        try {
            audioDecoderThread?.join(500)
        } catch (e: InterruptedException) {
            Log.w(logFrom, "[Stream Client] Interrupted while waiting for audio decoder thread", e)
        }
        
        synchronized(audioDecoderLock) {
            try {
                audioDecoder?.stop()
                audioDecoder?.release()
            } catch (e: Exception) {
                Log.w(logFrom, "[Stream Client] Error releasing audio decoder", e)
            }
            audioDecoder = null
        }
        
        aacInputQueue.clear()
    }
    
    private fun releaseAudioTrack() {
        val t = audioTrack ?: return
        try { t.pause() } catch (_: Throwable) {}
        try { t.flush() } catch (_: Throwable) {}
        try { t.stop() } catch (_: Throwable) {}
        try { t.release() } catch (_: Throwable) {}
        audioTrack = null
    }

    // Offload audio playback to dedicated thread to prevent blocking network loop
    // This function now just enqueues audio data and returns immediately
    private fun playAudioDown(data: ByteArray, length: Int, rate: Int, ch: Int, tsUs: Long) {
        if (audioMuted || !audioPlaybackRunning) {
            ByteArrayPool.recycle(data)
            return
        }
        val packet = AudioPacket(data, length, rate, ch, tsUs)
        if (!audioPlaybackQueue.offer(packet)) {
            // Queue full - drop oldest to prevent blocking
            val dropped = audioPlaybackQueue.poll()
            dropped?.recycle()
            audioPlaybackQueue.offer(packet)
        }
    }
    
    private fun startAudioPlayback() {
        if (audioPlaybackRunning) return
        audioPlaybackRunning = true
        audioPlaybackQueue.clear() // Clear any stale packets
        audioPlaybackExecutor.execute {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            while (audioPlaybackRunning) {
                try {
                    // Blocking take: wait for audio packets
                    val packet = audioPlaybackQueue.take()
                    processAudioPacket(packet.data, packet.length, packet.rate, packet.ch, packet.tsUs)
                    // Recycle buffer after processing
                    packet.recycle()
                } catch (_: InterruptedException) {
                    break
                } catch (t: Throwable) {
                    Log.w(logFrom, "[Stream Client] Audio playback thread error", t)
                }
            }
        }
    }
    
    private fun stopAudioPlayback() {
        audioPlaybackRunning = false
        val list = ArrayList<AudioPacket>()
        audioPlaybackQueue.drainTo(list)
        list.forEach { it.recycle() }
        // Executor shutdown handled in shutdown()
    }
    
    // Process audio packet with RMS gating and AudioTrack.write() - runs on dedicated thread
    /**
     * Decode AAC frame to PCM
     * @param aacFrame AAC frame with ADTS header (7 bytes header + payload)
     */
    private fun decodeAacToPcm(aacFrame: ByteArray, length: Int, rate: Int, ch: Int, tsUs: Long) {
        if (!audioDecoding && audioDecoder == null) {
            // Initialize AAC decoder on first compressed frame
            setupAudioDecoder(rate, ch)
        }
        
        // Queue AAC frame for decoding (including ADTS header)
        // MediaCodec AAC decoder can handle ADTS streams directly
        val frame = AacFrame(aacFrame, length, tsUs)
        if (!aacInputQueue.offer(frame)) {
            // Queue full, drop oldest frame
            val dropped = aacInputQueue.poll()
            dropped?.recycle()
            aacInputQueue.offer(frame)
        }
    }
    
    /**
     * Setup AAC decoder
     * Note: For ADTS streams, we need to handle format detection from the first frame
     */
    private fun setupAudioDecoder(sampleRate: Int, channels: Int) {
        synchronized(audioDecoderLock) {
            if (audioDecoder != null) return // Already initialized
            
            try {
                // IMPORTANT:
                // Primary sends AAC with ADTS headers (7 bytes).
                // Many Android devices require KEY_IS_ADTS=1 and/or csd-0 (AudioSpecificConfig) for MediaCodec
                // to accept ADTS-framed AAC. Without it, you can receive AUDIO_FRAME packets but hear silence.
                val csd0 = buildAacLcAudioSpecificConfig(sampleRate, channels)

                audioDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                    val format = MediaFormat.createAudioFormat(
                        MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels
                    )
                    try {
                        format.setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    } catch (_: Throwable) {
                    }
                    try {
                        format.setInteger(MediaFormat.KEY_IS_ADTS, 1)
                    } catch (_: Throwable) {
                    }
                    try {
                        format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
                    } catch (_: Throwable) {
                    }
                    configure(format, null, null, 0)
                    start()
                }
                
                audioDecoding = true
                audioDecoderThread = Thread({ audioDecodingLoop() }, "CCTV-Audio-Decoder").apply {
                    isDaemon = true
                    start()
                }
                
                Log.d(logFrom, "[Stream Client] AAC decoder initialized: sampleRate=$sampleRate, channels=$channels")
            } catch (e: Exception) {
                Log.w(logFrom, "[Stream Client] Failed to setup AAC decoder, will use PCM fallback", e)
                audioDecoding = false
            }
        }
    }
    
    /**
     * AAC decoding loop - processes queued AAC frames and outputs PCM
     */
    private fun audioDecodingLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        
        val bufferInfo = MediaCodec.BufferInfo()
        val TIMEOUT_US = 10_000L
        
        while (audioDecoding) {
            val decoder = synchronized(audioDecoderLock) { audioDecoder } ?: break
            
            try {
                // Feed AAC frame to decoder (ADTS-framed AAC)
                val aacWrapper: AacFrame? = aacInputQueue.poll()
                if (aacWrapper != null && aacWrapper.length > 7) {
                    val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex) ?: continue
                        inputBuffer.clear()
                        
                        // Feed entire frame including ADTS header
                        inputBuffer.put(aacWrapper.data, 0, aacWrapper.length)
                        
                        decoder.queueInputBuffer(inputIndex, 0, aacWrapper.length, aacWrapper.tsUs, 0)
                        aacWrapper.recycle()
                    } else {
                        // Decoder busy; don't leak buffers.
                        aacWrapper.recycle()
                    }
                } else if (aacWrapper == null) {
                    Thread.sleep(2) // No data available
                    continue
                } else {
                    // Bad frame
                    aacWrapper.recycle()
                }
                
                // Drain decoded PCM
                while (true) {
                    val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    
                    when {
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = decoder.outputFormat
                            val outRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val outChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            Log.d(logFrom, "[Stream Client] AAC decoder format: sampleRate=$outRate, channels=$outChannels")
                        }
                        outputIndex >= 0 -> {
                            if (bufferInfo.size > 0) {
                                val outputBuffer = decoder.getOutputBuffer(outputIndex)
                                if (outputBuffer != null) {
                                    outputBuffer.position(bufferInfo.offset)
                                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                    
                                    val pcm = ByteArray(bufferInfo.size)
                                    outputBuffer.get(pcm)
                                    
                                    // Feed decoded PCM to playback pipeline
                                    val outRate = decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                    val outChannels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                    processAudioPacket(pcm, bufferInfo.size, outRate, outChannels, bufferInfo.presentationTimeUs)
                                }
                            }
                            decoder.releaseOutputBuffer(outputIndex, false)
                        }
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        else -> break
                    }
                }
            } catch (e: Exception) {
                Log.w(logFrom, "[Stream Client] Error in AAC decoding loop", e)
                break
            }
        }
        
        Log.d(logFrom, "[Stream Client] AAC decoding loop exited")
    }
    
    private fun processAudioPacket(data: ByteArray, length: Int, rate: Int, ch: Int, tsUs: Long) {
        try {
            if (audioMuted) return
            
            // RMS gate to suppress low-level hiss/noise right after connect.
            // PCM 16-bit little-endian - proper sign extension (matches Primary implementation)
            val rms = computeRmsPcm16le(data, length)
            val now = System.nanoTime()
            if (firstAudioDownNs == 0L) firstAudioDownNs = now

            // Calibrate noise floor for ~1s after unmute; don't play during calibration.
            if (noiseCalibrating) {
                noiseCalSamples++
                // Running average (stable + cheap)
                noiseFloorRms += (rms - noiseFloorRms) / noiseCalSamples.coerceAtLeast(1)
                if (now - noiseCalStartNs >= 1_000_000_000L) {
                    noiseCalibrating = false
                }
                return
            }

            // Adaptive gate: reject steady background noise even if it's above a fixed threshold.
            // Gate is based on measured noise floor + margin.
            // Lower threshold for hardware-processed audio (hardware NS already reduces noise)
            val adaptiveGate = (noiseFloorRms * 1.3) + 100.0
            val gate = maxOf(minRmsGate.toDouble(), 200.0, adaptiveGate)
            if (rms < gate) {
                // Slowly track noise floor when we're below gate (lets it adapt to environment).
                noiseFloorRms = noiseFloorRms * 0.995 + rms * 0.005
                return
            }

            if (now - lastAudioDownNs > 2_000_000_000L) {
               Log.d(logFrom, "[Stream Client] AudioDown play: rms=$rms gate=$gate floor=$noiseFloorRms bytes=$length rate=$rate ch=$ch tsUs=$tsUs")
            }
            latestPlayedAudioTsUs = tsUs
            val track = ensureAudioTrack(rate, ch)
            // This blocking call now runs on dedicated thread, not blocking network loop
            track.write(data, 0, length)
        } catch (t: Throwable) {
            Log.w(logFrom, "[Stream Client] Audio playback failed", t)
        }
    }

    /* ===============================
     * Push-to-talk (viewer -> primary)
     * =============================== */
    @android.annotation.SuppressLint("MissingPermission") // Permission checked in UI before invoking
    fun startTalk() {
        if (talkActive) return
        talkActive = true
        audioRecordExecutor.execute {
            val minBuf = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AUDIO_CHANNEL_CONFIG_IN,
                AUDIO_FORMAT
            ).coerceAtLeast(AUDIO_BYTES_PER_FRAME * 2)
            // Try VOICE_COMMUNICATION first for better quality (matches Primary streaming source)
            // Fallback to MIC if VOICE_COMMUNICATION is not available
            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    AUDIO_SAMPLE_RATE,
                    AUDIO_CHANNEL_CONFIG_IN,
                    AUDIO_FORMAT,
                    minBuf
                )
            } catch (e: Throwable) {
                Log.w(logFrom, "[Stream Client] Failed to create AudioRecord with VOICE_COMMUNICATION, trying MIC", e)
                try {
                    AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        AUDIO_SAMPLE_RATE,
                        AUDIO_CHANNEL_CONFIG_IN,
                        AUDIO_FORMAT,
                        minBuf
                    )
                } catch (se: SecurityException) {
                    Log.w(logFrom, "[Stream Client] AudioRecord permission denied", se)
                    talkActive = false
                    return@execute
                } catch (t: Throwable) {
                    Log.w(logFrom, "[Stream Client] AudioRecord init failed", t)
                    talkActive = false
                    return@execute
                }
            }
            // Audio effects (best-effort). On some devices, VOICE_COMMUNICATION can be quiet without AGC.
            // Explicitly enabling available effects improves consistency.
            try {
                if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                    runCatching {
                        android.media.audiofx.NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true }
                    }
                }
            } catch (_: Throwable) {}
            try {
                if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                    runCatching {
                        android.media.audiofx.AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
                    }
                }
            } catch (_: Throwable) {}
            try {
                if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
                    runCatching {
                        android.media.audiofx.AutomaticGainControl.create(recorder.audioSessionId)?.apply { enabled = true }
                    }
                }
            } catch (_: Throwable) {}
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(logFrom, "[Stream Client] AudioRecord not initialized")
                talkActive = false
                recorder.release()
                return@execute
            }
            val buf = ByteArray(AUDIO_BYTES_PER_FRAME)
            try {
                recorder.startRecording()
                while (talkActive) {
                    val read = recorder.read(buf, 0, buf.size)
                    if (read > 0) {
                        // Gain estimation (rate-limited logs). This does NOT mutate `buf`; gain is applied later to the copied payload.
                        try {
                            val rms = computeRmsPcm16le(buf, read)
                            // Target a moderate speech RMS. If mic is quiet, this increases loudness.
                            // Clamp to avoid severe clipping; final clamping still exists on Primary.
                            val desired = 1800.0
                            val g = if (rms <= 1.0) 1.0 else (desired / rms)
                            talkUpGain = g.coerceIn(1.0, 2.8).toFloat()

                            val nowUptime = android.os.SystemClock.uptimeMillis()
                            if (nowUptime - lastTalkUpLogUptimeMs > 2_000L) {
                                lastTalkUpLogUptimeMs = nowUptime
                                Log.d(logFrom, "[Stream Client] TalkUp rms=${"%.1f".format(rms)} gain=${"%.2f".format(talkUpGain)} src=${recorder.audioSource}")
                            }
                        } catch (_: Throwable) {
                        }
                        sendAudioUp(buf, read)
                    }
                }
            } catch (t: Throwable) {
                Log.w(logFrom, "[Stream Client] AudioRecord failed", t)
            } finally {
                try {
                    recorder.stop()
                } catch (_: Throwable) {
                }
                recorder.release()
            }
        }
    }

    fun stopTalk() {
        talkActive = false
        // Executor shutdown handled in disconnect()
    }

    private fun sendAudioUp(data: ByteArray, len: Int) {
        val o = out ?: return
        // CRITICAL:
        // `data` is a reused buffer from the AudioRecord loop.
        // Since we send on `senderExecutor`, the buffer may be overwritten by the next mic read
        // before the write happens → garbled/quiet/unclear talkback on Primary.
        val payload = ByteArrayPool.get(len)
        System.arraycopy(data, 0, payload, 0, len)
        // Apply a conservative gain to the copied payload (PCM16LE).
        // This improves loudness for quiet mics without changing the AudioRecord loop buffer.
        val gain = talkUpGain
        if (gain > 1.05f) {
            try {
                var j = 0
                while (j + 1 < len) {
                    val s = (payload[j].toInt() and 0xFF) or (payload[j + 1].toInt() shl 8)
                    val v = s.toShort().toInt()
                    val out = (v.toFloat() * gain).toInt().coerceIn(-32768, 32767)
                    payload[j] = (out and 0xFF).toByte()
                    payload[j + 1] = ((out shr 8) and 0xFF).toByte()
                    j += 2
                }
            } catch (_: Throwable) {
            }
        }
        senderExecutor.execute {
            try {
                val header = "AUDIO_FRAME|dir=up|size=$len|rate=$AUDIO_SAMPLE_RATE|ch=1\n"
                val headerBytes = header.toByteArray(Charsets.UTF_8)
                synchronized(writeLock) {
                    o.write(headerBytes)
                    o.write(payload, 0, len)
                    o.flush()
                }
            } catch (e: Exception) {
                Log.w(logFrom, "[Stream Client] Send audio failed", e)
            } finally {
                ByteArrayPool.recycle(payload)
            }
        }
    }

    private fun closeSocket() {
        // Synchronize on inputLock to prevent race conditions with read operations
        synchronized(inputLock) {
            try {
                input?.close()
            } catch (_: Exception) {
            }
            input = null
        }

        try {
            out?.close()
        } catch (_: Exception) {
        }

        try {
            socket?.close()
        } catch (_: Exception) {
        }

        out = null
        socket = null
        try { setPreviewVisible(false) } catch (_: Throwable) {}
    }

    private fun closeSender() {
        if (senderClosed.compareAndSet(false, true)) {
            try {
                senderExecutor.shutdownNow()
            } catch (_: Exception) {
            }
        }
    }

    private fun startHeartbeat() {
        if (heartbeatRunning) return
        heartbeatRunning = true
        heartbeatExecutor.execute {
            while (heartbeatRunning && running) {
                try {
                    Thread.sleep(HEARTBEAT_PING_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                // If the socket is gone, let reconnect logic handle it.
                if (socket == null || out == null) continue
                // Handshake watchdog: prevent "stuck on Starting stream…" forever.
                checkHandshakeHealth(android.os.SystemClock.uptimeMillis())
                val nowMs = System.currentTimeMillis()
                val nowUptimeMs = android.os.SystemClock.uptimeMillis()
                val sinceLastPong = if (lastPongUptimeMs > 0L) (nowUptimeMs - lastPongUptimeMs) else Long.MAX_VALUE
                // CRITICAL DIAGNOSTIC: Log PING sends to help diagnose heartbeat issues
                Log.d(logFrom, "[Stream Client] 🔵 [HEARTBEAT] Sending PING: sinceLastPong=${sinceLastPong}ms, state=$currentState")
                send("PING|tsMs=$nowMs")
                // Also use heartbeat ticks as a watchdog for stream activity.
                // If frames stop arriving (Primary stopped capture) but PONGs keep coming, we should NOT remain STREAMING.
                checkStreamHealth(nowUptimeMs)
            }
        }
    }

    /**
     * Handshake watchdog:
     * - Runs while CONNECTING/AUTHENTICATED/CONNECTED.
     * - Retries negotiation if Primary didn't accept/stream.
     * - Downgrades UI from AUTHENTICATED -> CONNECTED if no video ever starts, so UI shows "No Video" instead of hanging.
     *
     * This is intentionally conservative and uses throttling to avoid spamming Primary.
     */
    private fun checkHandshakeHealth(nowUptimeMs: Long) {
        try {
            val state = currentState
            if (state == ConnectionState.DISCONNECTED) return
            val inGrace = inReconfigureGrace(nowUptimeMs)

            // If we connected but never got AUTH_OK, treat as handshake failure.
            if (state == ConnectionState.CONNECTING) {
                val sinceConnect = if (connectedUptimeMs > 0L) (nowUptimeMs - connectedUptimeMs) else Long.MAX_VALUE
                if (sinceConnect >= 10_000L && lastAuthOkUptimeMs <= 0L) {
                    Log.w(logFrom, "[Stream Client] ⚠️ [HANDSHAKE] No AUTH_OK after ${sinceConnect}ms; reconnecting")
                    disconnectForRecovery("handshake_no_auth_ok")
                }
                return
            }

            val sinceAuthOk =
                if (lastAuthOkUptimeMs > 0L) (nowUptimeMs - lastAuthOkUptimeMs) else Long.MAX_VALUE

            // Throttle handshake kicks.
            val sinceLastKick =
                if (lastHandshakeKickUptimeMs > 0L) (nowUptimeMs - lastHandshakeKickUptimeMs) else Long.MAX_VALUE

            // In AUTHENTICATED, if we haven't gotten STREAM_ACCEPTED/CSD soon, re-send CAPS+SET_STREAM and request keyframe.
            if (state == ConnectionState.AUTHENTICATED) {
                val lastActivity = maxOf(lastFrameRxUptimeMs, lastFrameRenderUptimeMs)
                val sinceLastActivity = if (lastActivity > 0L) (nowUptimeMs - lastActivity) else Long.MAX_VALUE
                val noFramesEver = lastActivity <= 0L
                val framesStalled = lastActivity > 0L && sinceLastActivity >= 6_000L
                
                // If we have recent frames, exit early (stream is healthy).
                if (!noFramesEver && !framesStalled) return
                
                val missingAccepted = lastStreamAcceptedUptimeMs <= 0L
                val missingCsd = lastCsdUptimeMs <= 0L

                if (sinceAuthOk >= 3_000L && sinceLastKick >= 2_500L && (missingAccepted || missingCsd)) {
                    handshakeRetryCount++
                    lastHandshakeKickUptimeMs = nowUptimeMs
                    Log.w(
                        logFrom,
                        "[Stream Client] ⚠️ [HANDSHAKE] AUTHENTICATED but stream not starting (accepted=$missingAccepted csd=$missingCsd noFramesEver=$noFramesEver framesStalled=$framesStalled). " +
                            "Retry #$handshakeRetryCount: re-sending CAPS+SET_STREAM and requesting keyframe."
                    )
                    val p = selectProfile()
                    sendCapabilities(maxWidth = p.w, maxHeight = p.h, maxBitrate = p.bitrate)
                    sendSetStream(StreamConfig(p.w, p.h, p.bitrate, p.fps))
                    requestKeyframe("handshake_retry")
                }

                // If we're AUTHENTICATED for too long with no frames (or frames stalled), downgrade to CONNECTED to show "No Video".
                val shouldDowngrade = (noFramesEver && sinceAuthOk >= 12_000L) || framesStalled
                if (shouldDowngrade) {
                    Log.w(
                        logFrom,
                        "[Stream Client] ⚠️ [HANDSHAKE] No frames after ${sinceAuthOk}ms in AUTHENTICATED (noFramesEver=$noFramesEver framesStalled=$framesStalled sinceLastActivityMs=${if (lastActivity > 0L) sinceLastActivity else -1L}) -> posting CONNECTED (No Video)"
                    )
                    postState(ConnectionState.CONNECTED)
                }

                // Hard limit: if still nothing after a long time, reconnect.
                val shouldReconnect = (noFramesEver && sinceAuthOk >= 25_000L) || (framesStalled && sinceLastActivity >= 20_000L)
                if (shouldReconnect) {
                    Log.w(
                        logFrom,
                        "[Stream Client] ⚠️ [HANDSHAKE] No frames after ${sinceAuthOk}ms (noFramesEver=$noFramesEver framesStalled=$framesStalled sinceLastActivityMs=${if (lastActivity > 0L) sinceLastActivity else -1L}); reconnecting"
                    )
                    // During recording-triggered pauses, frames can be absent temporarily. Do not hard-reconnect within grace.
                    if (!inGrace) {
                        disconnectForRecovery("handshake_no_frames")
                    } else {
                        Log.w(logFrom, "[Stream Client] 🟡 [RECONFIG GRACE] Skipping reconnect while AUTHENTICATED (recording/reconfigure grace window)")
                        requestKeyframe("handshake_grace_probe")
                    }
                }
                return
            }

            // In CONNECTED (No Video), recover from:
            // - "no frames ever" (startup never produced frames)
            // - "frames stopped" (Primary capture crashed/stopped but socket still alive with PONGs)
            if (state == ConnectionState.CONNECTED) {
                val lastActivity = maxOf(lastFrameRxUptimeMs, lastFrameRenderUptimeMs)
                val sinceLastActivity =
                    if (lastActivity > 0L) (nowUptimeMs - lastActivity) else Long.MAX_VALUE
                val noFramesEver = lastActivity <= 0L
                val stalledAfterVideo = lastActivity > 0L && sinceLastActivity >= 6_000L
                val sincePong = if (lastPongUptimeMs > 0L) (nowUptimeMs - lastPongUptimeMs) else Long.MAX_VALUE

                // CRITICAL FIX: Increase timeout when audio frames are being received (connection is active)
                // Audio frames indicate the connection is working, so PONG delay might be due to socket buffer processing
                val audioActive = (nowUptimeMs - lastAudioDownRxUptimeMs) < 5_000L
                val pongTimeout =
                    when {
                        inGrace -> 25_000L
                        audioActive -> 15_000L
                        else -> 7_000L
                    } // Longer timeout during recording/reconfigure grace window
                
                if (sincePong >= pongTimeout) {
                    Log.w(logFrom, "[Stream Client] ⚠️ [CONNECTED] No PONG for ${sincePong}ms while CONNECTED (audioActive=$audioActive, timeout=${pongTimeout}ms) -> disconnecting to recover")
                    disconnectForRecovery("connected_no_pong")
                    return
                } else if (sincePong >= 5_000L) {
                    // Log warning before timeout to help diagnose
                    Log.w(logFrom, "[Stream Client] ⚠️ [CONNECTED] PONG delay: ${sincePong}ms (audioActive=$audioActive, timeout=${pongTimeout}ms)")
                }

                // Step 1: probe with keyframe requests (throttled).
                if (sinceLastKick >= 5_000L) {
                    lastHandshakeKickUptimeMs = nowUptimeMs
                    Log.w(
                        logFrom,
                        "[Stream Client] ⚠️ [CONNECTED] No video (noFramesEver=$noFramesEver stalledAfterVideo=$stalledAfterVideo). " +
                            "Requesting keyframe probe (sinceLastActivityMs=${if (lastActivity > 0L) sinceLastActivity else -1L})"
                    )
                    requestKeyframe("connected_probe")
                }

                // Step 2: if still stuck, re-negotiate stream (CAPS + SET_STREAM) to re-arm Primary after crashes/resumes.
                val sinceLastConnectedKick =
                    if (lastConnectedRecoveryKickUptimeMs > 0L) (nowUptimeMs - lastConnectedRecoveryKickUptimeMs) else Long.MAX_VALUE
                if ((noFramesEver && sinceAuthOk >= 15_000L) || stalledAfterVideo) {
                    if (sinceLastConnectedKick >= 12_000L) {
                        connectedRecoveryCount++
                        lastConnectedRecoveryKickUptimeMs = nowUptimeMs
                        val p = selectProfile()
                        Log.w(
                            logFrom,
                            "[Stream Client] ⚠️ [CONNECTED] Escalation #$connectedRecoveryCount: re-sending CAPS+SET_STREAM (${p.w}x${p.h}@${p.fps}) to recover stream"
                        )
                        try {
                            sendCapabilities(maxWidth = p.w, maxHeight = p.h, maxBitrate = p.bitrate)
                        } catch (t: Throwable) {
                            Log.w(logFrom, "[Stream Client] CONNECTED renegotiate: CAPS failed (non-fatal)", t)
                        }
                        try {
                            sendSetStream(StreamConfig(p.w, p.h, p.bitrate, p.fps))
                        } catch (t: Throwable) {
                            Log.w(logFrom, "[Stream Client] CONNECTED renegotiate: SET_STREAM failed (non-fatal)", t)
                        }
                        requestKeyframe("connected_renegotiate")
                        // Use RECOVERING to make UI intent explicit while we wait for fresh frames/render.
                        postState(ConnectionState.RECOVERING)
                    }
                }

                // Step 3: last resort — disconnect to force a clean TCP/session + decoder restart.
                //
                // Stability improvement:
                // If we had video before (stalledAfterVideo) but now receive no frames, recover faster.
                // This addresses rare “silent decoder stall” cases where keyframe probes don't restore rendering.
                val stuckTooLong = (noFramesEver && sinceAuthOk >= 45_000L) || (stalledAfterVideo && sinceLastActivity >= 10_000L)
                if (stuckTooLong) {
                    Log.w(
                        logFrom,
                        "[Stream Client] ⚠️ [CONNECTED] Still no video after prolonged stall (noFramesEver=$noFramesEver sinceAuthOkMs=$sinceAuthOk sinceLastActivityMs=${if (lastActivity > 0L) sinceLastActivity else -1L}). Disconnecting to recover."
                    )
                    // If audio is still active and PONGs are recent, the socket is healthy; this is almost always a
                    // recording-triggered video pause/rebind. Do NOT disconnect in that case.
                    if (audioActive) {
                        Log.w(logFrom, "[Stream Client] 🟡 [AUDIO ACTIVE] Skipping disconnect while CONNECTED; waiting for video to resume")
                        beginReconfigureGrace("connected_audio_active_no_video", 120_000L)
                        requestKeyframe("connected_audio_active_probe")
                        postState(ConnectionState.RECOVERING)
                    } else if (!inGrace) {
                        disconnectForRecovery("connected_stuck_too_long")
                    } else {
                        Log.w(logFrom, "[Stream Client] 🟡 [RECONFIG GRACE] Skipping disconnect while CONNECTED (recording/reconfigure grace window)")
                        requestKeyframe("connected_grace_probe")
                        postState(ConnectionState.RECOVERING)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(logFrom, "[Stream Client] Handshake watchdog failed (non-fatal)", t)
        }
    }

    /**
     * Stream watchdog:
     * - If we're in STREAMING but there are no frames for a while, downgrade to CONNECTED and request a keyframe.
     * - If the connection is also stale (no PONG), disconnect to trigger reconnect logic.
     */
    private fun checkStreamHealth(nowUptimeMs: Long) {
        try {
            val state = currentState
            if (state != ConnectionState.STREAMING && state != ConnectionState.RECOVERING) return
            val inGrace = inReconfigureGrace(nowUptimeMs)

            val lastActivity = maxOf(lastFrameRxUptimeMs, lastFrameRenderUptimeMs)
            val connectedAt = connectedUptimeMs
            val sinceConnect = if (connectedAt > 0L) (nowUptimeMs - connectedAt) else Long.MAX_VALUE

            // Small-but-important watchdog improvement:
            // If receiver sees no frames for >2s:
            // - request keyframe (best-effort; throttled)
            // - downgrade UI to CONNECTED ("No Video") to avoid misleading STREAMING state
            //
            // If still stuck, CONNECTED-state escalation will reconnect later.
            val noFramesEver = lastActivity <= 0L && sinceConnect >= 2_000L
            val stalled = lastActivity > 0L && (nowUptimeMs - lastActivity) >= 2_000L

            if (!noFramesEver && !stalled) return

            val sincePong = if (lastPongUptimeMs > 0L) (nowUptimeMs - lastPongUptimeMs) else Long.MAX_VALUE
            Log.w(
                logFrom,
                "[Stream Client] ⚠️ [WATCHDOG] Stream stalled (state=$state). " +
                    "noFramesEver=$noFramesEver stalled=$stalled sinceConnectMs=$sinceConnect " +
                    "sinceLastFrameMs=${if (lastActivity > 0L) (nowUptimeMs - lastActivity) else -1L} " +
                    "sincePongMs=$sincePong"
            )

            // Audio frames indicate the connection is alive (even if video is paused during recording rebind).
            val audioActive = (nowUptimeMs - lastAudioDownRxUptimeMs) < 5_000L
            val pongTimeout =
                when {
                    inGrace -> 25_000L
                    audioActive -> 15_000L
                    else -> 7_000L
                } // Longer timeout during recording/reconfigure grace window
            if (sincePong >= pongTimeout) {
                // Connection likely dead; disconnect to trigger reconnect and correct UI.
                Log.w(logFrom, "[Stream Client] ⚠️ [WATCHDOG] No PONG recently (${sincePong}ms >= ${pongTimeout}ms, audioActive=$audioActive); disconnecting to recover")
                disconnectForRecovery("stream_no_pong")
                return
            }

            // If we're inside an expected reconfigure window OR we have active audio, do not downgrade to CONNECTED
            // and do not trigger CONNECTED-state "stuckTooLong" disconnect escalation. Stay in RECOVERING and wait.
            if (inGrace || audioActive) {
                // Socket is alive; tolerate brief stall during recording/encoder/camera reconfigure.
                waitingForKeyframe = true
                requestKeyframe(if (inGrace) "watchdog_reconfigure_grace" else "watchdog_audio_active_no_video")
                postState(ConnectionState.RECOVERING)
                return
            }

            // Socket is alive (PONGs still coming): Primary is up, but capture/stream likely stopped.
            // Downgrade UI state and attempt a keyframe request in case stream resumes quickly.
            waitingForKeyframe = true
            droppedNonKeyWhileWaitingCount = 0
            lastDropNonKeyWhileWaitingLogMs = 0L
            // Also stop rendering output until we have a fresh IDR again (prevents "stuck green" output).
            queuedKeyframeSinceReset = false
            firstQueuedKeyframePtsUsSinceReset = -1L
            nordCe4OutputWarmupDropsRemaining = 0
            nordCe4SuppressRenderUntilUptimeMs = 0L
            nordCe4RenderedFramesAfterWarmup = 0
            clearDecodeQueue()
            // IMPORTANT: Do NOT reset the decoder here. Resetting during normal "capture stopped" creates flicker,
            // state flapping, and can trigger green-frame artifacts on some devices.
            // We simply downgrade to CONNECTED and wait for a clean keyframe + render to re-enter STREAMING.
            postedFirstFrameRendered = false
            renderWindowStartMs = 0L
            renderFramesInWindow = 0
            requestKeyframe("watchdog_no_frames")
            postState(ConnectionState.CONNECTED)
        } catch (t: Throwable) {
            Log.w(logFrom, "[Stream Client] [WATCHDOG]  Stream watchdog failed (non-fatal)", t)
        }
    }

    private fun stopHeartbeat() {
        heartbeatRunning = false
        // Executor shutdown handled in disconnect()
    }

    private fun triggerReconnect() {
        if (!running || !autoReconnect || reconnecting) return
        reconnecting = true
        
        // Exponential backoff: 1s, 2s, 4s, 8s, max 10s
        val nowUptimeMs = android.os.SystemClock.uptimeMillis()
        val sinceLastReconnect = if (lastReconnectUptimeMs > 0L) (nowUptimeMs - lastReconnectUptimeMs) else Long.MAX_VALUE
        
        // If last reconnect was recent (< 2s), increment attempt count; otherwise reset
        if (sinceLastReconnect < 2_000L) {
            reconnectAttemptCount++
        } else {
            reconnectAttemptCount = 0
        }
        
        val backoffMs = when {
            reconnectAttemptCount <= 0 -> 1_000L
            reconnectAttemptCount == 1 -> 2_000L
            reconnectAttemptCount == 2 -> 4_000L
            reconnectAttemptCount == 3 -> 8_000L
            else -> 10_000L // Cap at 10s
        }
        
        lastReconnectUptimeMs = nowUptimeMs
        Log.d(logFrom, "[Stream Client] 🔄 [RECONNECT] Scheduling reconnect attempt #${reconnectAttemptCount + 1} after ${backoffMs}ms backoff")
        
        reconnectExecutor.execute {
            try {
                Thread.sleep(backoffMs)
            } catch (_: InterruptedException) {
            }
            reconnecting = false
            connect()
        }
    }

    private fun postState(state: ConnectionState) {
        Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] postState() called: $state (from ${Thread.currentThread().name}), currentState=$currentState")
        
        // Prevent state downgrades: don't allow AUTHENTICATED to overwrite STREAMING or RECOVERING
        // State hierarchy: DISCONNECTED < CONNECTING < AUTHENTICATED < RECOVERING < STREAMING
        if (state == ConnectionState.AUTHENTICATED && currentState == ConnectionState.STREAMING) {
            Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] Ignoring AUTHENTICATED state - already STREAMING (preventing downgrade)")
            return
        }
        if (state == ConnectionState.AUTHENTICATED && currentState == ConnectionState.RECOVERING) {
            Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] Ignoring AUTHENTICATED state - already RECOVERING (preventing downgrade)")
            return
        }
        
        // Update current state IMMEDIATELY to prevent queued callbacks from overwriting
        currentState = state
        
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] Calling onStateChanged on main thread: $state")
            onStateChanged(state)
        } else {
            Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] Posting onStateChanged to main thread: $state")
            // Capture current state snapshot to check in the handler callback
            val stateAtPostTime = state
            mainHandler.post { 
                // Double-check currentState in case a higher priority state was set while queued
                if (stateAtPostTime == ConnectionState.AUTHENTICATED) {
                    if (currentState == ConnectionState.STREAMING || currentState == ConnectionState.RECOVERING) {
                        Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] Ignoring queued AUTHENTICATED callback - currentState=$currentState (preventing downgrade)")
                        return@post
                    }
                }
                Log.d(logFrom, "[Stream Client] 🔍 [CSD DIAGNOSTIC] onStateChanged callback invoked on main thread: $stateAtPostTime (currentState=$currentState)")
                onStateChanged(stateAtPostTime) 
            }
        }
    }

    private fun postError(msg: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onError(msg)
        } else {
            mainHandler.post { onError(msg) }
        }
    }

    private fun postRotation(deg: Int) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onRotationChanged(deg)
        } else {
            mainHandler.post { onRotationChanged(deg) }
        }
    }

    private fun postRecording(active: Boolean) {
        Log.d(logFrom, "[Stream Client] 🔴 [RECORDING] postRecording(active=$active) called - posting to main thread callback")
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.d(logFrom, "[Stream Client] 🔴 [RECORDING] Already on main thread - calling onRecordingStateChanged($active) directly")
            onRecordingStateChanged(active)
        } else {
            Log.d(logFrom, "[Stream Client] 🔴 [RECORDING] Not on main thread - posting onRecordingStateChanged($active) to main thread")
            mainHandler.post { 
                Log.d(logFrom, "[Stream Client] 🔴 [RECORDING] onRecordingStateChanged($active) callback executing on main thread")
                onRecordingStateChanged(active) 
            }
        }
    }

    private fun postVideoSize(w: Int, h: Int) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onVideoSizeChanged(w, h)
        } else {
            mainHandler.post { onVideoSizeChanged(w, h) }
        }
    }

    private fun postVideoCrop(left: Int, top: Int, right: Int, bottom: Int) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onVideoCropChanged(left, top, right, bottom)
        } else {
            mainHandler.post { onVideoCropChanged(left, top, right, bottom) }
        }
    }

}
