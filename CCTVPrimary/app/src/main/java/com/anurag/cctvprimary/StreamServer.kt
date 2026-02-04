package com.anurag.cctvprimary

import android.util.Log
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
enum class RemoteCommand {
    START_RECORDING, STOP_RECORDING, BACKPRESSURE, PRESSURE_CLEAR, REQ_KEYFRAME, SWITCH_CAMERA, ZOOM, ADJUST_BITRATE
}

data class StreamConfig(
    val width: Int, val height: Int, val bitrate: Int, val fps: Int
)

class StreamServer(
    private val port: Int,
    private val passwordProvider: () -> String,
    private val onRemoteCommand: (RemoteCommand, Any?) -> Unit,
    var onStreamConfigResolved: ((StreamConfig) -> Unit)? = null,
    var onRequestKeyframe: (() -> Unit)? = null,
    var onAudioFrameUp: ((ByteArray) -> Unit)? = null
) {
    companion object {
        private const val FRAME_QUEUE_CAPACITY = 60
        private const val SERVER_RESTART_BACKOFF_MS = 500L
        // Crash-hardening: protect low-end devices from reconnect storms.
        // When a Viewer is stuck reconnecting, sessions can accumulate briefly and stress the Primary.
        private const val MAX_ACTIVE_SESSIONS = 2
    }
    
    private val requestedConfigs = mutableMapOf<ViewerSession, StreamConfig>()
    private val resumeStates = mutableMapOf<String, ResumeState>()
    @Volatile private var lastCsd: Pair<ByteArray, ByteArray>? = null
    @Volatile var isRecordingProvider: (() -> Boolean)? = null
    @Volatile var cameraFacingProvider: (() -> Boolean)? = null
    @Volatile var communicationEnabledProvider: (() -> Boolean)? = null
    @Volatile var rotationProvider: (() -> Int?)? = null
    
    /**
     * Provider for actual encoder width/height (may differ from requested in Buffer Mode)
     * If set, STREAM_ACCEPTED will use actual encoder dimensions instead of requested
     */
    @Volatile var encoderWidthProvider: (() -> Int)? = null
    @Volatile var encoderHeightProvider: (() -> Int)? = null

    @Volatile
    private var activeConfig: StreamConfig? = null

    /**
     * Stream epoch - incremented whenever the stream configuration changes in a way that requires
     * the Viewer to reset decoder state (encoder restart/reconfigure).
     *
     * Contract:
     * - STREAM_ACCEPTED, CSD, and FRAME messages include epoch=<n>.
     * - Viewer must drop any CSD/FRAME that does not match the latest accepted epoch.
     *
     * Note:
     * - We start at 0 (unknown / not yet negotiated). The first negotiated config bumps to 1.
     * - This avoids an unnecessary early "epoch change" reset on the Viewer during AUTH_OK,
     *   before SET_STREAM has been processed.
     */
    @Volatile
    private var streamEpoch: Long = 0L

    private val sessions = CopyOnWriteArraySet<ViewerSession>()

    /**
     * Callback invoked whenever the authenticated session count changes.
     *
     * Notes:
     * - Sessions are added only after successful authentication.
     * - This is intended for service-level power / pipeline decisions.
     */
    @Volatile
    var onSessionCountChanged: ((Int) -> Unit)? = null

    @Volatile
    private var running = false

    private var serverSocket: ServerSocket? = null
    private val frameQueue = LinkedBlockingQueue<EncodedFrame>(FRAME_QUEUE_CAPACITY)
    @Volatile private var senderRunning = false

    // Thread management using Executors for proper lifecycle control
    private val acceptExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CCTV-Server-Accept").apply { isDaemon = true }
    }
    private val senderExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CCTV-Server-Sender").apply { isDaemon = true }
    }

    // ---- Synchronization lock ----
    private val configLock = Any()
    private val logFrom = "CCTV_SS"

    /**
     * Returns the current number of active viewer sessions.
     *
     * Intended for lifecycle decisions (e.g., avoid stopping the TCP server / service if a viewer
     * is still connected, even if capture is IDLE).
     */
    fun activeSessionCount(): Int = sessions.size

    fun start() {
        if (running) return
        running = true
        startSender()
        acceptExecutor.execute {
            /**
             * CRITICAL: ServerSocket lifecycle must be tied to Service lifetime, not camera/encoder health.
             *
             * Goal:
             * - Avoid ECONNREFUSED during background/foreground churn.
             * - Keep the port LISTENING even if camera stalls or sender thread has no frames.
             *
             * Strategy:
             * - Bind ServerSocket once and keep accepting as long as `running == true`.
             * - If accept throws unexpectedly while still running, retry accept (and only recreate the socket
             *   if it was actually closed / broken).
             */
            var boundOnce = false
            while (running) {
                try {
                    if (serverSocket == null || serverSocket?.isClosed == true) {
                        // (Re)bind listener with small backoff to avoid tight loops.
                        try {
                            Thread.sleep(SERVER_RESTART_BACKOFF_MS)
                        } catch (_: InterruptedException) {
                        }
                        serverSocket = ServerSocket(port).apply {
                            // Helps with rapid restart on some devices (best-effort).
                            try { reuseAddress = true } catch (_: Throwable) { }
                        }
                        boundOnce = true
                        Log.d(logFrom, "✅ [STREAM SERVER]  Listening on port $port (reuseAddress=true)")
                    } else if (!boundOnce) {
                        boundOnce = true
                        Log.d(logFrom, "✅ [STREAM SERVER] Listening on port $port")
                    }

                    // Accept loop lifetime = Service lifetime.
                    Log.d(logFrom, "[STREAM SERVER] Waiting for viewer connection…")
                    val socket = serverSocket!!.accept()
                    Log.d(logFrom, "[STREAM SERVER] Viewer connected from ${socket.inetAddress.hostAddress}")
                    try {
                        createSession(socket)
                    } catch (t: Throwable) {
                        // Never let a bad session creation kill the accept loop.
                        Log.e(logFrom, "❌ [STREAM SERVER] createSession() failed (closing client socket)", t)
                        try { socket.close() } catch (_: Throwable) { }
                    }
                } catch (e: SocketException) {
                    // Common on close() during shutdown. Only log as error if we didn't request stop.
                    if (!running) {
                        Log.d(logFrom, "[STREAM SERVER] Accept loop closed (shutdown)")
                        break
                    }
                    // If the listener was closed/broken unexpectedly, recreate it next iteration.
                    Log.w(logFrom, "⚠️ [STREAM SERVER] Accept socket error (will continue listening)", e)
                    try { serverSocket?.close() } catch (_: Throwable) { }
                    serverSocket = null
                } catch (e: Exception) {
                    if (!running) break
                    Log.e(logFrom, "❌ [STREAM SERVER] Accept loop error (will continue listening)", e)
                    // Best-effort: if the socket seems wedged, recreate.
                    try { serverSocket?.close() } catch (_: Throwable) { }
                    serverSocket = null
                }
            }

            // Shutdown cleanup: only when `running` becomes false.
            try { serverSocket?.close() } catch (_: Throwable) { }
            serverSocket = null
        }
    }

    fun stop() {
        // Idempotent shutdown (callable even if accept loop already failed).
        running = false
        senderRunning = false
        frameQueue.clear()
        Log.d(logFrom, "[STREAM SERVER] Stopping StreamServer")
        sessions.forEach {
            // ViewerSession will self-close and callback
            it.close()
        }
        sessions.clear()
        try {
            onSessionCountChanged?.invoke(0)
        } catch (_: Throwable) {
        }
        try {
            serverSocket?.close()
        } catch (_: Exception) {
            Log.e(logFrom, "[STREAM SERVER] Server stop failed")
        }
        serverSocket = null
        // Shutdown executors gracefully
        try {
            acceptExecutor.shutdownNow()
            senderExecutor.shutdownNow()
        } catch (_: Exception) {
            Log.w(logFrom, "[STREAM SERVER] Executor shutdown error (ignored)")
        }
    }

    /* ===============================
         * Encoder fan-out
         * =============================== *//* fun broadcastFrame(frame: EncodedFrame) {
         if (sessions.isEmpty()) return

         for (session in sessions) {
             session.enqueueFrame(frame)
         }
     }*/

    /* ===============================
         * Session management
         * =============================== */

    private fun createSession(socket: Socket) {
        Log.d(logFrom, "[STREAM SERVER] Incoming connection from ${socket.inetAddress.hostAddress}")
        lateinit var session: ViewerSession
        session =
            ViewerSession(
                socket = socket,
                passwordProvider = passwordProvider,
                onCommand = { session, cmd, payload ->
                    Log.d(logFrom, "[STREAM SERVER] Remote command from session: $cmd payload=$payload")
                    when (cmd) {
                        RemoteCommand.REQ_KEYFRAME -> {
                            // Help viewer resume quickly.
                            val requestTimeMs = System.currentTimeMillis()
                            Log.d(logFrom, "[STREAM SERVER] 🔵 [KEYFRAME REQUEST] REQ_KEYFRAME received from session: ${session.sessionId} at ${requestTimeMs}ms")
                            // Ensure session has the current epoch before re-sending config/CSD.
                            session.setStreamEpoch(streamEpoch)
                            lastCsd?.let { (sps, pps) -> 
                                Log.d(logFrom, "[STREAM SERVER] 🔵 [KEYFRAME REQUEST] Sending CSD to session: ${session.sessionId}")
                                session.sendCsd(sps, pps)
                            } ?: run {
                                Log.w(logFrom, "[STREAM SERVER] ⚠️ [KEYFRAME REQUEST] WARNING: lastCsd is NULL - cannot send CSD to session: ${session.sessionId}")
                            }
                            activeConfig?.let { cfg ->
                                Log.d(logFrom, "[STREAM SERVER] 🔵 [KEYFRAME REQUEST] Sending STREAM_ACCEPTED to session: ${session.sessionId} (${cfg.width}x${cfg.height}@${cfg.fps})")
                                session.sendControl(
                                    "STREAM_ACCEPTED|epoch=$streamEpoch|width=${cfg.width}|height=${cfg.height}|bitrate=${cfg.bitrate}|fps=${cfg.fps}|session=${session.sessionId}"
                                )
                                session.enableStreaming(streamEpoch)
                            } ?: run {
                                Log.w(logFrom, "[STREAM SERVER] ⚠️ [KEYFRAME REQUEST] WARNING: activeConfig is NULL - cannot send STREAM_ACCEPTED to session: ${session.sessionId}")
                            }
                            cameraFacingProvider?.let { session.sendControl("CAMERA|front=${it()}") }
                            // Send current rotation when viewer requests keyframe
                            rotationProvider?.let { provider ->
                                provider()?.let { rot ->
                                    session.sendEncoderRotation(rot)
                                }
                            }
                            Log.d(logFrom, "[STREAM SERVER] 🔵 [KEYFRAME REQUEST] Invoking onRequestKeyframe callback for session: ${session.sessionId}")
                            onRequestKeyframe?.invoke() ?: run {
                                Log.w(logFrom, "[STREAM SERVER] ⚠️ [KEYFRAME REQUEST] WARNING: onRequestKeyframe callback is NULL - keyframe may not be generated!")
                            }
                            Log.d(logFrom, "[STREAM SERVER] 🔵 [KEYFRAME REQUEST] REQ_KEYFRAME handling completed for session: ${session.sessionId}")
                        }
                        RemoteCommand.ADJUST_BITRATE -> {
                            // Seamless bitrate adjustment without encoder reset
                            val newBitrate = payload as? Int
                            if (newBitrate != null && newBitrate > 0) {
                                onRemoteCommand(cmd, newBitrate)
                            }
                        }
                        else -> onRemoteCommand(cmd, payload)
                    }
                },
                onAuthenticated = { s ->
                    // Add to active sessions list only after authentication
                    Log.i(logFrom, "[STREAM SERVER] Session authenticated (id=${s.sessionId})")
                    // Crash-hardening: keep session count bounded.
                    // Prefer keeping the newest authenticated session; close older ones if we exceed cap.
                    try {
                        if (sessions.size >= MAX_ACTIVE_SESSIONS) {
                            val toClose = sessions.filter { it !== s }.take((sessions.size - MAX_ACTIVE_SESSIONS) + 1)
                            toClose.forEach { old ->
                                try {
                                    Log.w(logFrom, "⚠️ [STREAM SERVER] Too many sessions (${sessions.size}); closing older session id=${old.sessionId}")
                                    old.close()
                                } catch (_: Throwable) {
                                }
                            }
                        }
                    } catch (_: Throwable) {
                    }
                    sessions.add(s)
                    Log.i(logFrom, "[STREAM SERVER] Session added (sessions=${sessions.size})")
                    Log.d(logFrom, "🔵 [STREAM SERVER] Session authenticated and added; active sessions=${sessions.size}, sessionId=${s.sessionId}")
                    try {
                        onSessionCountChanged?.invoke(sessions.size)
                    } catch (_: Throwable) {
                    }

                    // Ensure viewer can configure decoder immediately after auth (especially after reconnect).
                    Log.d(logFrom, "[STREAM SERVER] 🔍 [CSD DIAGNOSTIC] onAuthenticated callback invoked for session: ${s.sessionId}")
                    Log.d(logFrom, "[STREAM SERVER] 🔍 [CSD DIAGNOSTIC] lastCsd is ${if (lastCsd != null) "SET (sps=${lastCsd!!.first.size}, pps=${lastCsd!!.second.size})" else "NULL"}")
                    // Seed session epoch so early CSD/control uses the current epoch.
                    s.setStreamEpoch(streamEpoch)
                    lastCsd?.let { (sps, pps) -> 
                        Log.d(logFrom, "[STREAM SERVER] 🔍 [CSD DIAGNOSTIC] Sending saved CSD to newly authenticated session: ${s.sessionId}")
                        s.sendCsd(sps, pps)
                        Log.d(logFrom, "[STREAM SERVER] 🔍 [CSD DIAGNOSTIC] CSD sent to session: ${s.sessionId}")
                    } ?: run {
                        Log.w(logFrom, "[STREAM SERVER] 🔍 [CSD DIAGNOSTIC] WARNING: lastCsd is NULL - viewer will not receive CSD on authentication!")
                    }
                    isRecordingProvider?.let { s.sendRecordingState(it()) }
                    communicationEnabledProvider?.let { s.sendControl("COMM|enabled=${it()}") }
                    // CRITICAL: Always send STREAM_ACCEPTED on authentication, even if activeConfig is null.
                    // If activeConfig is null, use encoder dimensions (if available) or reasonable defaults.
                    // This ensures Viewer can configure decoder immediately after reconnect.
                    val cfg = activeConfig ?: run {
                        // No active config yet - use encoder dimensions if available, otherwise defaults
                        val encoderW = encoderWidthProvider?.invoke() ?: 720
                        val encoderH = encoderHeightProvider?.invoke() ?: 960
                        StreamConfig(encoderW, encoderH, 3_000_000, 30)
                    }
                    s.sendControl(
                        "STREAM_ACCEPTED|epoch=$streamEpoch|width=${cfg.width}|height=${cfg.height}|bitrate=${cfg.bitrate}|fps=${cfg.fps}|session=${s.sessionId}"
                    )
                    Log.i("STREAM SERVER", "[STREAM SERVER] Sending STREAM_ACCEPTED epoch=$streamEpoch")
                    s.enableStreaming(streamEpoch)
                    Log.d(logFrom, "[STREAM SERVER] 🔍 [CSD DIAGNOSTIC] Sent STREAM_ACCEPTED to newly authenticated session: ${s.sessionId} (${cfg.width}x${cfg.height}@${cfg.fps}, activeConfig was ${if (activeConfig != null) "SET" else "NULL - using defaults"})")
                    cameraFacingProvider?.let { s.sendControl("CAMERA|front=${it()}") }
                    // Send current rotation to new viewer so label displays correctly
                    rotationProvider?.let { provider ->
                        provider()?.let { rot ->
                            s.sendEncoderRotation(rot)
                        }
                    }
                    onRequestKeyframe?.invoke()
                },
                onResumeRequested = { s, requestedId ->
                handleResumeRequest(s, requestedId)
            },
                onStreamConfigRequested = { cfg ->
                Log.d(
                    logFrom,
                    "[STREAM SERVER] Stream config requested by session: ${cfg.width}x${cfg.height} @ ${cfg.bitrate}"
                )
                synchronized(configLock) {
                    requestedConfigs[session] = cfg
                    resolveEncoderConfigLocked()
                }
            },
                onSessionClosed = { closed ->
                synchronized(configLock) {
                    requestedConfigs.remove(closed)
                    val removed = sessions.remove(closed)
                    resolveEncoderConfigLocked()
                    if (removed) {
                        try {
                            onSessionCountChanged?.invoke(sessions.size)
                        } catch (_: Throwable) {
                        }
                    }
                }
                Log.d(logFrom, "[STREAM SERVER] Session removed; active sessions=${sessions.size}")
            },
                onAudioFrameUp = { data ->
                    onAudioFrameUp?.invoke(data)
                }
        )

    }
    fun handleResumeRequest(session: ViewerSession, requestedSessionId: String) {
        synchronized(configLock) {
            val cfg = resumeStates[requestedSessionId]?.config
            if (cfg != null) {
                session.sessionId = requestedSessionId
                requestedConfigs[session] = cfg
                resolveEncoderConfigLocked()
                session.sendControl("RESUME_OK")
                lastCsd?.let { (sps, pps) -> session.sendCsd(sps, pps) }
                isRecordingProvider?.let { session.sendRecordingState(it()) }
                communicationEnabledProvider?.let { session.sendControl("COMM|enabled=${it()}") }
                onRequestKeyframe?.invoke()
            } else {
                session.sendControl("RESUME_FAIL")
            }
        }
    }

    private fun resolveEncoderConfigLocked() {
        if (requestedConfigs.isEmpty()) return

        val winner = requestedConfigs.values.minBy {
            it.width * it.height * it.bitrate
        }

        // IMPORTANT:
        // We ultimately stream at the encoder's *actual* resolution, not necessarily the viewer-requested one.
        // If we compare `activeConfig` to `winner` (requested), we can get into an epoch-thrash loop where:
        // - activeConfig becomes "actual" (e.g. 720x960)
        // - winner remains "requested" (e.g. 1080x1440)
        // - resolveEncoderConfigLocked() is called again and thinks config changed -> bumps epoch again
        //
        // That exact loop matches the Viewer logs (epoch 1->2->3->4->5) and causes black screens.
        val actualWidth = encoderWidthProvider?.invoke() ?: winner.width
        val actualHeight = encoderHeightProvider?.invoke() ?: winner.height
        val actualCfg = StreamConfig(
            width = actualWidth,
            height = actualHeight,
            bitrate = winner.bitrate,
            fps = winner.fps
        )

        if (activeConfig == null || activeConfig != actualCfg) {
            // New active configuration requires decoder reset on Viewer: bump epoch.
            streamEpoch += 1L
            Log.d(
                logFrom,
                "[STREAM SERVER] 🔵 [EPOCH] Stream epoch incremented -> $streamEpoch (new config ${actualCfg.width}x${actualCfg.height} @ ${actualCfg.bitrate})"
            )

            // Reconfigure encoder/camera to match the requested "quality intent".
            // Encoder/camera code will clamp dimensions in Buffer Mode.
            try {
                onStreamConfigResolved?.invoke(winner)
            } catch (t: Throwable) {
                // Never let an encoder/camera reconfigure failure crash the TCP server thread.
                Log.e(logFrom, "❌ [STREAM SERVER] onStreamConfigResolved threw (ignored)", t)
            }
            Log.w(
                logFrom,
                "[STREAM SERVER] Encoder config arbitration result: " + "${winner.width}x${winner.height} @ ${winner.bitrate}"
            )

            activeConfig = actualCfg
            if (actualWidth != winner.width || actualHeight != winner.height) {
                Log.d(
                    logFrom,
                    "[STREAM SERVER] 🔵 [RESOLUTION] Override requested ${winner.width}x${winner.height} with actual encoder ${actualWidth}x${actualHeight}"
                )
            }

            val now = System.currentTimeMillis()
            sessions.forEach { session ->
                resumeStates[session.sessionId] = ResumeState(activeConfig!!, now)
                session.setStreamEpoch(streamEpoch)
                session.sendControl(
                    "STREAM_ACCEPTED|epoch=$streamEpoch|width=${actualWidth}|height=${actualHeight}|bitrate=${winner.bitrate}|fps=${winner.fps}|session=${session.sessionId}"
                )
                Log.i(logFrom, "[STREAM SERVER] Sending STREAM_ACCEPTED epoch=$streamEpoch (resolve)")
                session.enableStreaming(streamEpoch)
            }

            // IMPORTANT:
            // Immediately request a sync frame (IDR) after (re)negotiating a stream configuration.
            //
            // Rationale:
            // - Some encoders output a few non-key frames before their next scheduled IDR.
            // - Viewers cannot reliably decode those frames on first connect (often shows as green preview).
            // - Requesting an IDR here makes startup deterministic and reduces time-to-first-frame.
            try {
                Log.d(logFrom, "🔵 [STREAM SERVER] Requesting immediate keyframe after STREAM_ACCEPTED broadcast (${actualWidth}x${actualHeight})")
                onRequestKeyframe?.invoke()
            } catch (t: Throwable) {
                Log.w(logFrom, "⚠️ [STREAM SERVER] Failed to request immediate keyframe after STREAM_ACCEPTED (non-fatal)", t)
            }
        }
    }
    // 🔑 Called Externally
    fun enqueueFrame(frame: EncodedFrame) {
        // Always log keyframes and first 20 frames for diagnostics
        val queueSizeBefore = synchronized(this) { frameQueue.size }
        val sessionsCount = sessions.size
        val enqueueTimeMs = System.currentTimeMillis()
        val frameAgeMs = if (frame.captureEpochMs > 0) (enqueueTimeMs - frame.captureEpochMs).coerceAtLeast(0L) else -1L
        val ageInfo = if (frameAgeMs >= 0) {
            "age=${frameAgeMs}ms (capture→enqueue)"
        } else {
            "age=unknown"
        }
        val shouldLog = frame.isKeyFrame || queueSizeBefore < 5 || (System.currentTimeMillis() % 1000) < 100
        if (shouldLog) {
            Log.d(logFrom, "🔵 [STREAM SERVER] enqueueFrame called: size=${frame.data.size}, isKeyFrame=${frame.isKeyFrame}, running=$running, sessions=$sessionsCount, queueSizeBefore=$queueSizeBefore, pts=${frame.presentationTimeUs}us, $ageInfo")
        }
        if (!running) {
            if (shouldLog) {
                Log.w(logFrom, "🔵 [STREAM SERVER] enqueueFrame called but sender not running")
            }
            return
        }

        // Viewer-aware load shedding:
        // If there are no active sessions, do not enqueue frames at all.
        //
        // Intent:
        // - Reduce memory churn and queue pressure when nobody is watching.
        // - Avoid needless work in the sender thread (which would just drop frames anyway).
        //
        // Note:
        // - We still keep `lastCsd` and on connect we explicitly request an IDR (StreamServer already does),
        //   so skipping frame enqueue here does NOT harm join/reconnect behavior.
        if (sessionsCount <= 0) {
            if (shouldLog) {
                Log.d(logFrom, "🔵 [STREAM SERVER] No active sessions; skipping enqueue (size=${frame.data.size}, key=${frame.isKeyFrame})")
            }
            return
        }

        val offered = synchronized(this) {
            frameQueue.offer(frame)
        }
        val queueSizeAfter = synchronized(this) { frameQueue.size }
        if (!offered) {
            // Prefer keyframes under pressure: dropping IDR causes visible artifacts until the next keyframe.
            if (frame.isKeyFrame) {
                val cleared = synchronized(this) { frameQueue.size }.also {
                    try {
                        synchronized(this) {
                            frameQueue.clear()
                            frameQueue.offer(frame)
                        }
                    } catch (t: Throwable) {
                        Log.w(logFrom, "⚠️ [STREAM SERVER] Failed to prioritize keyframe under overload (non-fatal)", t)
                    }
                }
                Log.w(
                    logFrom,
                    "⚠️ [STREAM SERVER] Frame queue FULL - prioritized KEYFRAME by clearing $cleared stale frame(s): " +
                        "size=${frame.data.size}, queueSizeAfter=${synchronized(this) { frameQueue.size }}, age=${frameAgeMs}ms"
                )
            } else {
                Log.w(
                    logFrom,
                    "⚠️ [STREAM SERVER] Frame queue FULL (capacity=$FRAME_QUEUE_CAPACITY), dropping non-key frame: " +
                        "size=${frame.data.size}, queueSizeBefore=$queueSizeBefore, queueSizeAfter=$queueSizeAfter, age=${frameAgeMs}ms"
                )
            }
        } else if (shouldLog) {
            Log.d(logFrom, "🔵 [STREAM SERVER] Frame enqueued successfully: size=${frame.data.size}, isKeyFrame=${frame.isKeyFrame}, queueSizeBefore=$queueSizeBefore, queueSizeAfter=$queueSizeAfter, sessions=$sessionsCount, pts=${frame.presentationTimeUs}us, $ageInfo")
        }
    }

    private data class ResumeState(
        val config: StreamConfig, val timestampMs: Long
    )

    fun broadcastCsd(sps: ByteArray, pps: ByteArray) {
        lastCsd = sps to pps
        Log.d(logFrom, "🔍 [STREAM SERVER] [CSD DIAGNOSTIC] StreamServer.broadcastCsd() called: sps=${sps.size}, pps=${pps.size}, activeSessions=${sessions.size}")
        sessions.forEach { session ->
            Log.d(logFrom, "🔍[STREAM SERVER]  [CSD DIAGNOSTIC] Sending CSD to session: ${session.sessionId}")
            session.sendCsd(sps, pps)
        }
        if (sessions.isEmpty()) {
            Log.w(logFrom, "🔍 [STREAM SERVER] [CSD DIAGNOSTIC] CSD broadcast called but no active sessions - CSD saved for future viewers")
        }
    }
    
    /**
     * Update activeConfig with actual encoder resolution (may differ from requested in Buffer mode)
     * This ensures STREAM_ACCEPTED uses encoder dimensions, not requested dimensions
     */
    fun updateActiveConfigWithEncoderResolution(encoderWidth: Int, encoderHeight: Int) {
        synchronized(configLock) {
            val oldConfig = activeConfig
            if (oldConfig != null) {
                // Update activeConfig with actual encoder dimensions, keep bitrate/fps from requested
                activeConfig = StreamConfig(
                    width = encoderWidth,
                    height = encoderHeight,
                    bitrate = oldConfig.bitrate,
                    fps = oldConfig.fps
                )
                Log.d(logFrom, "[STREAM SERVER] 🔵 [RESOLUTION] Updated activeConfig from requested ${oldConfig.width}x${oldConfig.height} to actual encoder ${encoderWidth}x${encoderHeight}")
                
                // Broadcast updated STREAM_ACCEPTED to all active sessions
                val now = System.currentTimeMillis()
                sessions.forEach { session ->
                    resumeStates[session.sessionId] = ResumeState(activeConfig!!, now)
                    session.setStreamEpoch(streamEpoch)
                    session.sendControl("STREAM_ACCEPTED|epoch=$streamEpoch|width=${encoderWidth}|height=${encoderHeight}|bitrate=${activeConfig!!.bitrate}|fps=${activeConfig!!.fps}|session=${session.sessionId}")
                    Log.d(logFrom, "[STREAM SERVER] 🔵 [RESOLUTION] Sent STREAM_ACCEPTED with actual encoder resolution: ${encoderWidth}x${encoderHeight}")
                }
            } else {
                Log.w(logFrom, "[STREAM SERVER] updateActiveConfigWithEncoderResolution called but activeConfig is null (no viewer connected yet)")
            }
        }
    }

    fun broadcastEncoderRotation(deg: Int) {
        sessions.forEach { session ->
            session.sendEncoderRotation(deg)
        }
    }
    fun broadcastAudioDown(pcm: ByteArray, tsUs: Long, rate: Int, ch: Int, isCompressed: Boolean = false) {
        sessions.forEach { session ->
            session.sendAudioDown(pcm, tsUs, rate, ch, isCompressed)
        }
    }
    fun broadcastRecording(active: Boolean) {
        sessions.forEach { session ->
            session.sendRecordingState(active)
        }
    }
    fun broadcastCommunicationEnabled(enabled: Boolean) {
        sessions.forEach { session ->
            session.sendControl("COMM|enabled=$enabled")
        }
    }
    fun broadcastCameraFacing(front: Boolean) {
        sessions.forEach { session ->
            session.sendControl("CAMERA|front=$front")
        }
    }
    private fun startSender() {
        if (senderRunning) {
            Log.d(logFrom, "🔵 [STREAM SERVER] Sender thread already running")
            return
        }
        Log.d(logFrom, "🔵 [STREAM SERVER] Starting sender thread")
        senderRunning = true
        senderExecutor.execute {
            Log.d(logFrom, "🔵 [STREAM SERVER] Sender thread started")
            var lastPerformanceLogMs = System.currentTimeMillis()
            var lastFramesSent = 0L
            var totalFramesSent = 0L
            try {
                while (senderRunning) {
                    // Low-latency fan-out: always broadcast the *latest* frame to sessions.
                    // If the server sender falls behind, older frames build up here and cause viewer delay/jerks.
                    val queueSizeBefore = synchronized(this@StreamServer) { frameQueue.size }
                    val sessionsCount = sessions.size
                    // Only log when queue is empty and we have sessions, or for keyframes
                    if (queueSizeBefore == 0 && sessionsCount > 0) {
                        Log.d(logFrom, "🔵 [STREAM SERVER] Sender thread waiting for frame from queue (queueSize=0, sessions=$sessionsCount) - waiting for encoder to produce frames...")
                    }
                    var latest = frameQueue.take()
                    var latestKey: EncodedFrame? = if (latest.isKeyFrame) latest else null
                    var framesDrained = 1
                    while (true) {
                        val next = frameQueue.poll() ?: break
                        latest = next
                        if (next.isKeyFrame) latestKey = next
                        framesDrained++
                    }
                    val queueSizeAfter = synchronized(this@StreamServer) { frameQueue.size }
                    // IMPORTANT: under load (e.g. motion), dropping keyframes causes visible "distortion" artifacts
                    // until the next keyframe arrives. Prefer sending the newest keyframe if one exists in this batch.
                    val toSend = latestKey ?: latest
                    if (sessions.isEmpty()) {
                        // Only log keyframes when no sessions to avoid spam
                        if (toSend.isKeyFrame) {
                            Log.w(logFrom, "🔴 [STREAM SERVER] Sender thread processing keyframe but no active sessions - frame will be dropped (size=${toSend.data.size}, drained=$framesDrained frames, queueSizeAfter=$queueSizeAfter)")
                        }
                    } else {
                        // Always log keyframes and throttle other frames
                        val sendTimeMs = System.currentTimeMillis()
                        val frameAgeMs = if (toSend.captureEpochMs > 0) (sendTimeMs - toSend.captureEpochMs).coerceAtLeast(0L) else -1L
                        val shouldLog = toSend.isKeyFrame || (System.currentTimeMillis() % 1000) < 100
                        if (shouldLog) {
                            val ageInfo = if (frameAgeMs >= 0) {
                                "age=${frameAgeMs}ms (capture→send)"
                            } else {
                                "age=unknown"
                            }
                            Log.d(logFrom, "🔵 [STREAM SERVER] Sender thread consumed $framesDrained frame(s) from queue (queueSizeBefore=$queueSizeBefore, queueSizeAfter=$queueSizeAfter), broadcasting to ${sessions.size} session(s): size=${toSend.data.size}, isKeyFrame=${toSend.isKeyFrame}, pts=${toSend.presentationTimeUs}us, $ageInfo")
                        }
                        sessions.forEach { session ->
                            if (shouldLog) {
                                Log.d(logFrom, "🔵 [STREAM SERVER] Calling session.enqueueFrame for session: ${session.sessionId}")
                            }
                            session.enqueueFrame(toSend)
                        }
                        totalFramesSent++
                        
                        // Periodic performance metrics (every 5 seconds)
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - lastPerformanceLogMs >= 5000L) {
                            val elapsedSeconds = (nowMs - lastPerformanceLogMs) / 1000.0
                            val framesSentDelta = totalFramesSent - lastFramesSent
                            val sendFps = if (elapsedSeconds > 0) framesSentDelta / elapsedSeconds else 0.0
                            val currentQueueSize = synchronized(this@StreamServer) { frameQueue.size }
                            val activeSessions = sessions.size
                            
                            Log.i(
                                logFrom,
                                "[STREAM SERVER] 📊 [PERFORMANCE] StreamServer metrics (last ${elapsedSeconds.toInt()}s): framesSent=$totalFramesSent, sendFPS=${String.format("%.1f", sendFps)}, queueSize=$currentQueueSize, activeSessions=$activeSessions"
                            )
                            
                            lastPerformanceLogMs = nowMs
                            lastFramesSent = totalFramesSent
                        }
                    }
                }
            } catch (_: InterruptedException) {
                // shutdown
            } catch (e: Exception) {
                Log.e(logFrom, "[STREAM SERVER] Sender error", e)
            }
        }
    }
}







