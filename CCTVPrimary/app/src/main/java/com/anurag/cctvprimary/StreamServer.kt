package com.anurag.cctvprimary

import android.util.Log
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicLong
enum class RemoteCommand {
    START_RECORDING, STOP_RECORDING, BACKPRESSURE, PRESSURE_CLEAR, REQ_KEYFRAME, SWITCH_CAMERA, ZOOM, ADJUST_BITRATE
}

data class StreamConfig(
    val width: Int, val height: Int, val bitrate: Int, val fps: Int
)

class StreamServer(
    private val port: Int,
    private val passwordProvider: () -> String,
    private val commandBus: CommandBus,
    private val frameBus: FrameBus,
    var onAudioFrameUp: ((ByteArray) -> Unit)? = null
) {
    companion object {
        const val FRAME_QUEUE_CAPACITY = 60
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
     * - We include epoch inside STREAM_ACCEPTED (even though we send it in STREAM_STATE too) so that
     *   under load, if control messages reorder, the accept is unambiguously tied to an epoch—
     *   bundling reduces ambiguity (production trick).
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
    @Volatile private var senderRunning = false

    /** Frames dropped at FrameBus (non-keyframe when full). Reset each metrics log interval. */
    private val droppedFramesSinceLastLog = AtomicLong(0L)

    // Use shared executors to avoid thread explosion (StreamingExecutors)
    private val acceptExecutor: ExecutorService get() = StreamingExecutors.ioExecutor
    private val senderExecutor: ExecutorService get() = StreamingExecutors.senderExecutor

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
        frameBus.clear()
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
        // Do not shut down acceptExecutor/senderExecutor — they are shared (StreamingExecutors)
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
                                session.sendControlAtomic(
                                    "STREAM_ACCEPTED|epoch=$streamEpoch|width=${cfg.width}|height=${cfg.height}|bitrate=${cfg.bitrate}|fps=${cfg.fps}|session=${session.sessionId}",
                                    "STREAM_STATE|2|epoch=$streamEpoch"
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
                            Log.d(logFrom, "[STREAM SERVER] 🔵 [KEYFRAME REQUEST] Posting RequestKeyframe to command bus for session: ${session.sessionId}")
                            commandBus.post(StreamCommand.RequestKeyframe)
                            Log.d(logFrom, "[STREAM SERVER] 🔵 [KEYFRAME REQUEST] REQ_KEYFRAME handling completed for session: ${session.sessionId}")
                        }
                        RemoteCommand.ADJUST_BITRATE -> {
                            val newBitrate = payload as? Int
                            if (newBitrate != null && newBitrate > 0) {
                                commandBus.post(StreamCommand.AdjustBitrate(newBitrate))
                            }
                        }
                        else -> commandBus.post(remoteToStreamCommand(cmd, payload))
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
                    s.sendControlAtomic(
                        "STREAM_ACCEPTED|epoch=$streamEpoch|width=${cfg.width}|height=${cfg.height}|bitrate=${cfg.bitrate}|fps=${cfg.fps}|session=${s.sessionId}",
                        "STREAM_STATE|2|epoch=$streamEpoch"
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
                    commandBus.post(StreamCommand.RequestKeyframe)
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
                    val activeAfter = sessions.size
                    resolveEncoderConfigLocked()
                    if (removed) {
                        try {
                            onSessionCountChanged?.invoke(activeAfter)
                        } catch (_: Throwable) {
                        }
                    }
                    // #region agent log
                    DebugLog.log("primary", PrimaryDebugRunId.runId, "C", "StreamServer.onSessionClosed", "session", mapOf("event" to "session_closed", "sessionId" to closed.sessionId, "activeSessionsAfter" to activeAfter))
                    // #endregion
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
                commandBus.post(StreamCommand.RequestKeyframe)
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

        // IMPORTANT — Epoch thrash mitigation:
        // We ultimately stream at the encoder's *actual* resolution, not necessarily the viewer-requested one.
        // If we compared `activeConfig` to `winner` (requested), we could get an infinite epoch loop:
        //   activeConfig gets set to "actual" (e.g. 720x960); winner stays "requested" (e.g. 1080x1440);
        //   next resolveEncoderConfigLocked() sees activeConfig != winner -> bump epoch again; repeat.
        // By comparing activeConfig to actualCfg (hardware-constrained: encoder dimensions + winner bitrate/fps),
        // after one update we have activeConfig == actualCfg, so the next call does not bump. Loop broken.
        val actualWidth = encoderWidthProvider?.invoke() ?: winner.width
        val actualHeight = encoderHeightProvider?.invoke() ?: winner.height
        val actualCfg = StreamConfig(
            width = actualWidth,
            height = actualHeight,
            bitrate = winner.bitrate,
            fps = winner.fps
        )

        // Only bump epoch when current active differs from actual (avoids thrash: after this, activeConfig == actualCfg).
        if (activeConfig == null || activeConfig != actualCfg) {
            // New active configuration requires decoder reset on Viewer: bump epoch.
            streamEpoch += 1L
            Log.d(
                logFrom,
                "[STREAM SERVER] 🔵 [EPOCH] Stream epoch incremented -> $streamEpoch (new config ${actualCfg.width}x${actualCfg.height} @ ${actualCfg.bitrate})"
            )

            // Reconfigure encoder/camera to match the requested "quality intent" (via command bus).
            commandBus.post(StreamCommand.ReconfigureStream(winner))
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
                session.sendControlAtomic(
                    "STREAM_ACCEPTED|epoch=$streamEpoch|width=${actualWidth}|height=${actualHeight}|bitrate=${winner.bitrate}|fps=${winner.fps}|session=${session.sessionId}",
                    "STREAM_STATE|2|epoch=$streamEpoch"
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
            Log.d(logFrom, "🔵 [STREAM SERVER] Posting keyframe request after STREAM_ACCEPTED broadcast (${actualWidth}x${actualHeight})")
            commandBus.post(StreamCommand.RequestKeyframe)
        }
    }
    // 🔑 Called Externally (encoder pushes to FrameBus via this; sender loop drains from frameBus)
    fun enqueueFrame(frame: EncodedFrame) {
        val queueSizeBefore = frameBus.size()
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

        // DROP_NON_KEYFRAME_ON_FULL: see FrameBus class doc. Caller checks return value and prioritizes keyframe or drops.
        val offered = frameBus.publish(frame)
        val queueSizeAfter = frameBus.size()
        if (!offered) {
            if (frame.isKeyFrame) {
                val cleared = frameBus.size()
                try {
                    frameBus.clear()
                    frameBus.publish(frame)
                } catch (t: Throwable) {
                    Log.w(logFrom, "⚠️ [STREAM SERVER] Failed to prioritize keyframe under overload (non-fatal)", t)
                }
                Log.w(
                    logFrom,
                    "⚠️ [STREAM SERVER] FrameBus FULL - prioritized KEYFRAME by clearing $cleared stale frame(s): " +
                        "size=${frame.data.size}, queueSizeAfter=${frameBus.size()}, age=${frameAgeMs}ms"
                )
            } else {
                droppedFramesSinceLastLog.incrementAndGet()
                Log.w(
                    logFrom,
                    "⚠️ [STREAM SERVER] FrameBus FULL (capacity=$FRAME_QUEUE_CAPACITY), dropping non-key frame: " +
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
                // Skip broadcast when dimensions unchanged to avoid viewer state flicker:
                // STREAMING -> RECONFIGURING (code 2) right after first keyframe makes UI show "Recovering" while video plays.
                if (oldConfig.width == encoderWidth && oldConfig.height == encoderHeight) {
                    Log.d(logFrom, "[STREAM SERVER] 🔵 [RESOLUTION] Encoder resolution unchanged ${encoderWidth}x${encoderHeight} - skipping STREAM_ACCEPTED broadcast")
                    return
                }
                // Update activeConfig with actual encoder dimensions, keep bitrate/fps from requested
                activeConfig = StreamConfig(
                    width = encoderWidth,
                    height = encoderHeight,
                    bitrate = oldConfig.bitrate,
                    fps = oldConfig.fps
                )
                Log.d(logFrom, "[STREAM SERVER] 🔵 [RESOLUTION] Updated activeConfig from ${oldConfig.width}x${oldConfig.height} to actual encoder ${encoderWidth}x${encoderHeight}")
                
                // Broadcast updated STREAM_ACCEPTED + RECONFIGURING atomically so viewer never sees reorder on some OEM kernels
                val now = System.currentTimeMillis()
                sessions.forEach { session ->
                    resumeStates[session.sessionId] = ResumeState(activeConfig!!, now)
                    session.setStreamEpoch(streamEpoch)
                    session.sendControlAtomic(
                        "STREAM_ACCEPTED|epoch=$streamEpoch|width=${encoderWidth}|height=${encoderHeight}|bitrate=${activeConfig!!.bitrate}|fps=${activeConfig!!.fps}|session=${session.sessionId}",
                        "STREAM_STATE|2|epoch=$streamEpoch"
                    )
                    session.enableStreaming(streamEpoch)
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

    /**
     * Broadcast STREAM_STATE|STOPPED to all sessions so viewers can distinguish "stream intentionally ended" from "network lost".
     * Call when capture/encoder is stopped (e.g. stopCapture).
     * IMPORTANT: Only ViewerSession emits STREAM_STATE to the wire; we must not send STREAM_STATE directly from StreamServer (single authority, no split-brain).
     */
    fun broadcastStreamStateStopped() {
        sessions.forEach { session ->
            try {
                session.sendStreamStateStopped()
            } catch (t: Throwable) {
                Log.w(logFrom, "[STREAM SERVER] broadcastStreamStateStopped failed for session (ignored)", t)
            }
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
                    val queueSizeBefore = frameBus.size()
                    val sessionsCount = sessions.size
                    if (queueSizeBefore == 0 && sessionsCount > 0) {
                        Log.d(logFrom, "🔵 [STREAM SERVER] Sender thread waiting for frame from FrameBus (queueSize=0, sessions=$sessionsCount) - waiting for encoder to produce frames...")
                    }
                    // Use timeout so stop() (senderRunning=false) interrupts the drain cleanly; take() would block forever.
                    var latest = frameBus.pollWithTimeout(500L)
                    if (latest == null) continue
                    var latestKey: EncodedFrame? = if (latest.isKeyFrame) latest else null
                    var framesDrained = 1
                    while (true) {
                        val next = frameBus.poll() ?: break
                        latest = next
                        if (next.isKeyFrame) latestKey = next
                        framesDrained++
                    }
                    val queueSizeAfter = frameBus.size()
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
                            val currentQueueSize = frameBus.size()
                            val activeSessions = sessions.size
                            val droppedInPeriod = droppedFramesSinceLastLog.getAndSet(0L)
                            Log.i(
                                logFrom,
                                "[STREAM SERVER] 📊 [PERFORMANCE] StreamServer metrics (last ${elapsedSeconds.toInt()}s): framesSent=$totalFramesSent, sendFPS=${String.format("%.1f", sendFps)}, queueSize=$currentQueueSize, activeSessions=$activeSessions, droppedFramesSinceLastLog=$droppedInPeriod"
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







