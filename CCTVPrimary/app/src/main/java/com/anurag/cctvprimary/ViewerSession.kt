package com.anurag.cctvprimary

import android.util.Log
import android.os.SystemClock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicLong
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val TAG = "CCTV_SESSION"
class ViewerSession(
    private val socket: Socket,
    private val passwordProvider: () -> String,
    private val onCommand: (ViewerSession, RemoteCommand, Any?) -> Unit,
    private val onAuthenticated: (ViewerSession) -> Unit,
    private val onResumeRequested: (ViewerSession, String) -> Unit,
    private val onStreamConfigRequested: (StreamConfig) -> Unit,
    private val onSessionClosed: (ViewerSession) -> Unit,
    private val onAudioFrameUp: (ByteArray) -> Unit
) {
    companion object {
        // Heartbeat timeout must tolerate brief background scheduling stalls on the Viewer.
        // Using too small a timeout (e.g., 6s) causes false disconnects when the viewer app is backgrounded.
        // Increased to tolerate device-specific sender stalls (prevents connect loops on some devices).
        private const val HEARTBEAT_TIMEOUT_MS = 60_000L
        private const val SOCKET_BUFFER_SIZE_BYTES = 256 * 1024
        private const val FRAME_QUEUE_CAPACITY = 30
        /** Per-session backpressure: if queue exceeds this (e.g. 2 keyframes worth), drop non-keyframes until next keyframe. Streaming favors freshness over completeness. */
        private const val DROP_POLICY_QUEUE_THRESHOLD = 12
        private const val CONTROL_QUEUE_CAPACITY = 60
        /** Socket read/write timeout (ms). Prevents a stuck reader or slow writer from holding the connection indefinitely. */
        private const val SOCKET_TIMEOUT_MS = 20_000
        private const val HEARTBEAT_CHECK_INTERVAL_MS = 2_000L

        /** Protocol stream state codes (authoritative; viewer must obey). 1=ACTIVE, 2=RECONFIGURING, 3=PAUSED, 4=STOPPED */
        private const val STREAM_STATE_ACTIVE = 1
        private const val STREAM_STATE_RECONFIGURING = 2
        private const val STREAM_STATE_PAUSED = 3
        private const val STREAM_STATE_STOPPED = 4
    }


    enum class StreamState {
        CONNECTING, DISCONNECTED, AUTHENTICATED, STREAMING, RECONFIGURING
    }

    data class ViewerCaps(
        val maxWidth: Int, val maxHeight: Int, val maxBitrate: Int
    )

    // --- Transport ---
    // IMPORTANT: use a single buffered stream for both line-based control and binary payloads.
    // Mixing BufferedReader + raw InputStream reads can corrupt framing because BufferedReader may prefetch bytes.
    private val input = BufferedInputStream(socket.getInputStream(), SOCKET_BUFFER_SIZE_BYTES)
    private val output = socket.getOutputStream()
    private val out = BufferedOutputStream(output)
    private val writeLock = Any()
    private val authSalt = ByteArray(32).apply { SecureRandom().nextBytes(this) }.joinToString("") { "%02x".format(it) }


    // --- Session state ---
    private var state = StreamState.CONNECTING
    private var viewerCaps: ViewerCaps? = null
    private var pendingConfig: StreamConfig? = null
    var sessionId: String = java.util.UUID.randomUUID().toString()

    /** Negotiated protocol version from HELLO. 2 = text line + payload; 3 = binary framed [header][payload] for video. */
    @Volatile
    private var protocolVersion: Int = 2

    /**
     * Stream epoch for this session.
     *
     * Contract:
     * - StreamServer increments epoch whenever the active stream configuration changes (encoder restart/reconfigure).
     * - Viewer MUST drop CSD/FRAME messages that do not match the latest STREAM_ACCEPTED epoch.
     * - This prevents mixing old frames with new config during reconfigure/reconnect.
     */
    @Volatile
    private var streamEpoch: Long = 0L

    /**
     * Monotonic video frame sequence number for this session.
     *
     * Why:
     * - Epoch handles *configuration eras* (encoder restart/reconfigure)
     * - seq provides *per-frame ordering* inside an epoch, enabling:
     *   - instant gap detection (loss vs late)
     *   - better diagnostics and future NACK support
     *
     * Contract:
     * - seq resets to 0 when epoch changes (new stream era).
     * - seq increments by 1 for each FRAME header emitted.
     */
    private val frameSeq: AtomicLong = AtomicLong(0L)

    @Volatile
    private var lastSeqEpoch: Long = -1L

    private val authenticated = AtomicBoolean(false)
    private val stateWrapper = AtomicBoolean(false) // For idempotent close

    /**
     * Whether we have sent STREAM_STATE|ACTIVE for the current epoch.
     * ACTIVE is sent only after the first keyframe is transmitted (per STATE_MACHINE.md).
     */
    @Volatile
    private var activeSentForThisEpoch = false

    /**
     * Set the current stream epoch for this session.
     *
     * Intended to be called by StreamServer immediately before (or together with) sending STREAM_ACCEPTED.
     */
    fun setStreamEpoch(epoch: Long) {
        val prevEpoch = streamEpoch
        streamEpoch = epoch
        // Reset seq ONLY when the epoch actually changes.
        // Avoid spurious seq resets when StreamServer re-sends STREAM_ACCEPTED for the same epoch.
        if (epoch != lastSeqEpoch) {
            lastSeqEpoch = epoch
            frameSeq.set(0L)
        }
        // New epoch: we must send ACTIVE again after first keyframe of this epoch.
        if (epoch != prevEpoch) activeSentForThisEpoch = false
    }

    // --- Heartbeat ---
    @Volatile
    // Use uptime, not wall-clock: avoids false timeouts on clock changes / deep sleep.
    private var lastPingUptimeMs = SystemClock.uptimeMillis()

    // --- Sender ---
    private val frameQueue = LinkedBlockingQueue<EncodedFrame>(FRAME_QUEUE_CAPACITY)
    private sealed class ControlItem {
        data class Line(val msg: String) : ControlItem()
        class Csd(val sps: ByteArray, val pps: ByteArray) : ControlItem()
    }
    private val controlQueue = LinkedBlockingQueue<ControlItem>(CONTROL_QUEUE_CAPACITY)
    /** STREAM_STATE (code, epoch) queue. ViewerSession is the single authority that emits STREAM_STATE to the wire (StreamServer must only trigger via session.sendStreamState*(), never send STREAM_STATE directly). Sender thread drains and writes so ordering cannot regress. */
    private val streamStateQueue = LinkedBlockingQueue<Pair<Int, Long>>(32)
    /** Atomic control batches: written in one task with one flush to prevent reorder/coalesce on some OEM kernels (e.g. STREAM_ACCEPTED + STREAM_STATE|2). Drained first in sender loop. */
    private val atomicBatchQueue = LinkedBlockingQueue<List<String>>(16)
    private data class AudioItem(val pcm: ByteArray, val tsUs: Long, val rate: Int, val ch: Int, val isCompressed: Boolean) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as AudioItem

            if (!pcm.contentEquals(other.pcm)) return false
            if (tsUs != other.tsUs) return false
            if (rate != other.rate) return false
            if (ch != other.ch) return false
            if (isCompressed != other.isCompressed) return false

            return true
        }

        override fun hashCode(): Int {
            var result = pcm.contentHashCode()
            result = 31 * result + tsUs.hashCode()
            result = 31 * result + rate
            result = 31 * result + ch
            result = 31 * result + isCompressed.hashCode()
            return result
        }
    }
    private val audioQueue = LinkedBlockingQueue<AudioItem>(CONTROL_QUEUE_CAPACITY)

    @Volatile
    private var senderRunning = false
    
    // Use shared session pool to cap total session threads (StreamingExecutors.sessionPool)
    private val sessionPool: ExecutorService get() = StreamingExecutors.sessionPool
    


    init {
        Log.d(TAG, "[VIEWER SESSION] Session created for ${socket.inetAddress.hostAddress}")
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = SOCKET_TIMEOUT_MS
            try { socket.sendBufferSize = SOCKET_BUFFER_SIZE_BYTES } catch (_: Throwable) {}
            try { socket.receiveBufferSize = SOCKET_BUFFER_SIZE_BYTES } catch (_: Throwable) {}
        } catch (_: Throwable) { }
        startSender()
        listen()
        startHeartbeatWatchdog()
    }

    /* ===============================
     * Protocol receive loop
     * =============================== */
    private fun listen() {
        sessionPool.execute {
            try {
                while (true) {
                    val line = ProtocolIO.readLineUtf8(input) ?: break
                    handleCommand(line)
                }
            } catch (e: Exception) {
                Log.e(TAG, "[VIEWER SESSION] Session error", e)
            } finally {
                close()
            }
        }
    }

    private fun handleCommand(line: String) {
        Log.d(TAG, "[VIEWER SESSION] RECV: $line")

        try {
            // Treat ANY inbound traffic as a liveness signal.
            // Some devices may temporarily fail to emit PINGs under load, but they still send CAPS/SET_STREAM/etc.
            // Updating here prevents false disconnects and CE4->M30s reconnect loops.
            lastPingUptimeMs = SystemClock.uptimeMillis()
            when {
                line.startsWith("HELLO") -> {
                    protocolVersion = 2
                    if (line.contains("|")) {
                        val params = parseParams(line)
                        protocolVersion = params["version"]?.toIntOrNull()?.coerceIn(2, 3) ?: 2
                    }
                    sendControl("AUTH_CHALLENGE|v=2|salt=$authSalt")
                }

                line.startsWith("AUTH_RESPONSE|") -> {
                    val params = parseParams(line)
                    val clientHash = params["hash"] ?: ""
                    val expectedHash = hmacSha256(passwordProvider(), authSalt)
                    
                    if (clientHash.isNotEmpty() && clientHash.equals(expectedHash, ignoreCase = true)) {
                        sendControl("AUTH_OK")
                        Log.i("VIEWER SESSION", "AUTH_OK")
                        handleAuthSuccess()
                    } else {
                        Log.w(TAG, "[VIEWER SESSION] AUTH_FAIL: Hash mismatch from ${socket.inetAddress.hostAddress}")
                        sendControl("AUTH_FAIL")
                        close()
                    }
                }

                // Legacy fallback (optional - reject for security hardening)
                line.startsWith("AUTH|") -> {
                     Log.w(TAG, "[VIEWER SESSION] Rejecting legacy AUTH (plaintext) from ${socket.inetAddress.hostAddress}")
                     sendControl("AUTH_FAIL|reason=legacy_auth_deprecated")
                     close()
                }

                line.startsWith("CAPS|") -> {
                    val params = parseParams(line)
                    viewerCaps = ViewerCaps(
                        maxWidth = params["maxWidth"]!!.toInt(),
                        maxHeight = params["maxHeight"]!!.toInt(),
                        maxBitrate = params["maxBitrate"]!!.toInt()
                    )
                    sendControl("CAPS_OK")
                }
                line.startsWith("RESUME|") -> {
                    val id = line.substringAfter("session=")
                    Log.d(TAG, "[VIEWER SESSION] Resume requested for session=$id")
                    onResumeRequested(this, id)
                }

                line.startsWith("SET_STREAM|") -> {
                    Log.i("VIEWER SESSION", "SET_STREAM received")
                    // IMPORTANT:
                    // Viewer must send CAPS before SET_STREAM so we can validate the request.
                    // If CAPS hasn't arrived yet, do not silently drop this message; reply with an error so
                    // the Viewer can retry deterministically (prevents "stuck on Starting stream").
                    val caps = viewerCaps
                    if (caps == null) {
                        Log.w(TAG, "[VIEWER SESSION] SET_STREAM received before CAPS - rejecting to force retry: $line")
                        sendControl("ERROR|reason=caps_required")
                        return
                    }
                    val params = parseParams(line)

                    val cfg = StreamConfig(
                        width = params["width"]!!.toInt(),
                        height = params["height"]!!.toInt(),
                        bitrate = params["bitrate"]!!.toInt(),
                        fps = params["fps"]!!.toInt()

                    )

                    if (cfg.width > caps.maxWidth || cfg.height > caps.maxHeight || cfg.bitrate > caps.maxBitrate) {
                        sendControl("STREAM_REJECTED|reason=unsupported")
                        return
                    }

                    pendingConfig = cfg
                    state = StreamState.RECONFIGURING
                    scheduleStreamState(STREAM_STATE_RECONFIGURING, streamEpoch)
                    // IMPORTANT:
                    // Do NOT send STREAM_ACCEPTED here.
                    // StreamServer is the single source of truth for accepted config because it can:
                    // - arbitrate between multiple viewers
                    // - override to actual encoder resolution (buffer mode)
                    // - include fps + session id consistently
                    //
                    // Emitting a second, partial STREAM_ACCEPTED here causes duplicate/conflicting accepts on the Viewer.

                    onStreamConfigRequested(cfg)
                }
                line == "PING" || line.startsWith("PING|") -> {
                    // Already updated at top for all commands; keep explicit branch for protocol response.
                    val params = if (line.startsWith("PING|")) parseParams(line) else emptyMap()
                    val tsMs = params["tsMs"]?.toLongOrNull()
                    val srvMs = System.currentTimeMillis()
                    if (tsMs != null) {
                        sendControl("PONG|tsMs=$tsMs|srvMs=$srvMs")
                    } else {
                        sendControl("PONG")
                    }
                }

                line == "START_RECORDING" -> {
                    Log.d(TAG, "[VIEWER SESSION] 🔴 [RECORDING] Received START_RECORDING command from viewer")
                    onCommand(this, RemoteCommand.START_RECORDING, null)
                }

                line == "STOP_RECORDING" -> {
                    Log.d(TAG, "[VIEWER SESSION] 🔴 [RECORDING] Received STOP_RECORDING command from viewer")
                    onCommand(this, RemoteCommand.STOP_RECORDING, null)
                }

                line == "REQ_KEYFRAME" -> onCommand(this, RemoteCommand.REQ_KEYFRAME, null)

                line == "SWITCH_CAMERA" -> onCommand(this, RemoteCommand.SWITCH_CAMERA, null)

                line.startsWith("ZOOM|") -> {
                    val params = parseParams(line)
                    val ratio = params["ratio"]?.toFloatOrNull()
                    if (ratio != null) {
                        onCommand(this, RemoteCommand.ZOOM, ratio)
                    }
                }

                line.startsWith("AUDIO_FRAME|") -> {
                    val params = parseParams(line)
                    val size = params["size"]?.toIntOrNull() ?: 0
                    if (size > 0) {
                        val buffer = ByteArray(size)
                        ProtocolIO.readFullyExact(input, buffer)
                        onAudioFrameUp(buffer)
                    }
                }

                line == "BACKPRESSURE" -> {
                    state = StreamState.RECONFIGURING
                    scheduleStreamState(STREAM_STATE_RECONFIGURING, streamEpoch)
                    onCommand(this, RemoteCommand.BACKPRESSURE, null)
                }

                line == "PRESSURE_CLEAR" -> {
                    state = StreamState.STREAMING
                    onCommand(this, RemoteCommand.PRESSURE_CLEAR, null)
                }
                
                line.startsWith("ADJUST_BITRATE|") -> {
                    val params = parseParams(line)
                    val bitrate = params["bitrate"]?.toIntOrNull()
                    if (bitrate != null && bitrate > 0) {
                        onCommand(this, RemoteCommand.ADJUST_BITRATE, bitrate)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[VIEWER SESSION] Error handling command: $line", e)
             // Don't close session on single command error, unless it's critical?
             // Best effort: keep session alive.
        }
    }

    /* ===============================
     * Frame sending
     * =============================== *//* fun enqueueFrame(frame: EncodedFrame) {
         if (state == StreamState.DISCONNECTED) return
         frameQueue.offer(frame)
     }*/
    fun enqueueFrame(frame: EncodedFrame) {
        if (!authenticated.get()) {
            Log.d(TAG, "[VIEWER SESSION] dropping frame — not active (authenticated=${authenticated.get()})")
            return
        }

        // Accept frames in both STREAMING and RECONFIGURING. RECONFIGURING exits only when a keyframe is sent;
        // if we dropped frames here during RECONFIGURING, no keyframe would ever reach the sender → deadlock.
        if (state != StreamState.STREAMING && state != StreamState.RECONFIGURING) {
            Log.d(TAG, "[VIEWER SESSION] dropping frame — state=$state")
            return
        }

        if (frameQueue.remainingCapacity() == 0) {
            Log.w(TAG, "🔴 [VIEWER SESSION] enqueueFrame called but session is DISCONNECTED - dropping frame")
            return
        }
        // Per-session drop policy: if this viewer's queue is already backed up, drop non-keyframes until next keyframe (favors freshness over completeness).
        if (!frame.isKeyFrame && state == StreamState.STREAMING && frameQueue.size >= DROP_POLICY_QUEUE_THRESHOLD) {
            return
        }
        // Under load (e.g. motion), never silently drop keyframes; it causes visible decoder artifacts
        // until the next keyframe arrives.
        if (frame.isKeyFrame) {
            // Prefer flushing stale P-frames so the viewer can decode cleanly ASAP.
            val cleared = frameQueue.size
            frameQueue.clear()
            frameQueue.offer(frame)
            if (cleared > 0) {
                Log.d(TAG, "🔵 [VIEWER SESSION] Keyframe received - cleared $cleared stale frames from queue")
            }
            Log.d(TAG, "🔵 [VIEWER SESSION] Keyframe enqueued: size=${frame.data.size}, queueSize=${frameQueue.size}, state=$state, senderRunning=$senderRunning")
            return
        }
        if (!frameQueue.offer(frame)) {
            // Drop oldest to keep latency bounded, then enqueue.
            frameQueue.poll()
            frameQueue.offer(frame)
        }
        // Log every 30th frame to track frame flow
        if (frameQueue.size % 30 == 0) {
            Log.d(TAG, "🔵 [VIEWER SESSION] Frame enqueued: size=${frame.data.size}, queueSize=${frameQueue.size}, state=$state, senderRunning=$senderRunning")
        }
    }

    private fun startSender() {
        if (senderRunning) return
        senderRunning = true

        sessionPool.execute {
            try {
                while (senderRunning) {
                    // Check if socket is still connected
                    if (socket.isClosed || state == StreamState.DISCONNECTED) break

                    // 0. Drain atomic batches first — critical sequences (e.g. STREAM_ACCEPTED + STREAM_STATE|2) written in one flush to avoid reorder on some devices
                    while (true) {
                        val batch = atomicBatchQueue.poll() ?: break
                        try {
                            synchronized(writeLock) {
                                if (socket.isClosed) throw java.net.SocketException("Closed")
                                batch.forEach { writeLine(it) }
                                out.flush()
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "[VIEWER SESSION] Atomic batch send error: ${e.message}")
                            throw e
                        }
                    }

                    // 1. Drain STREAM_STATE queue (single authority: only this thread writes state to wire)
                    while (true) {
                        val stateMsg = streamStateQueue.poll() ?: break
                        try {
                            synchronized(writeLock) {
                                if (socket.isClosed) throw java.net.SocketException("Closed")
                                writeLine("STREAM_STATE|${stateMsg.first}|epoch=${stateMsg.second}")
                                out.flush()
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "[VIEWER SESSION] STREAM_STATE send error: ${e.message}")
                            throw e
                        }
                    }

                    // 2. Process Control Messages (High Priority)
                    while (true) {
                        val ctl = controlQueue.poll() ?: break
                        try {
                            when (ctl) {
                                is ControlItem.Line -> {
                                    synchronized(writeLock) {
                                        if (socket.isClosed) throw java.net.SocketException("Closed")
                                        writeLine(ctl.msg)
                                        out.flush()
                                    }
                                }
                                is ControlItem.Csd -> {
                                    synchronized(writeLock) {
                                        if (socket.isClosed) throw java.net.SocketException("Closed")
                                        writeLine("CSD|epoch=$streamEpoch|sps=${ctl.sps.size}|pps=${ctl.pps.size}")
                                        out.write(ctl.sps)
                                        out.write(ctl.pps)
                                        out.flush()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "[VIEWER SESSION] Control send error: ${e.message}")
                            throw e 
                        }
                    }

                    // 3. Process Video Frames
                    var frame = frameQueue.poll(10, TimeUnit.MILLISECONDS) ?: continue
                    
                    // Skip stale frames logic...
                    var latest = frame
                    var latestKey: EncodedFrame? = if (frame.isKeyFrame) frame else null
                    while (true) {
                        val next = frameQueue.poll() ?: break
                        latest = next
                        if (next.isKeyFrame) latestKey = next
                    }
                    frame = if (state == StreamState.RECONFIGURING) (latestKey ?: latest) else latest
                    
                    if (state == StreamState.RECONFIGURING && !frame.isKeyFrame) continue

                    // Send ACTIVE only after first keyframe is transmitted (STATE_MACHINE.md). We're on sender thread so offer directly.
                    if (frame.isKeyFrame && !activeSentForThisEpoch) {
                        streamStateQueue.offer(Pair(STREAM_STATE_ACTIVE, streamEpoch))
                        activeSentForThisEpoch = true
                    }

                    try {
                        synchronized(writeLock) {
                            if (socket.isClosed) throw java.net.SocketException("Closed")
                            if (protocolVersion >= 3) {
                                ProtocolIO.writeBinaryVideoFrame(out, streamEpoch, frame.isKeyFrame, frame.data)
                                out.flush()
                            } else {
                                val srvMs = System.currentTimeMillis()
                                val ageMs = if (frame.captureEpochMs > 0) (srvMs - frame.captureEpochMs).coerceAtLeast(0L) else -1L
                                val seq = frameSeq.getAndIncrement()
                                val frameLine = "FRAME|epoch=$streamEpoch|seq=$seq|size=${frame.data.size}|key=${frame.isKeyFrame}|tsUs=${frame.presentationTimeUs}|srvMs=$srvMs|capMs=${frame.captureEpochMs}|ageMs=$ageMs"
                                writeLine(frameLine)
                                out.write(frame.data)
                                out.flush()
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "[VIEWER SESSION] Frame send error: ${e.message}")
                        throw e
                    }
                    
                    if (state == StreamState.RECONFIGURING && frame.isKeyFrame) {
                        state = StreamState.STREAMING
                    }
                }
                }catch (e: Exception) {
                Log.i(TAG, "[VIEWER SESSION] Video Sender stopped: ${e.message}")
            } finally {
                close()
            }
        }
        
        // --- AUDIO SENDER LOOP ---
        sessionPool.execute {
            try {
                while (senderRunning) {
                    if (socket.isClosed || state == StreamState.DISCONNECTED) break
                    
                    val audio = audioQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    
                    try {
                        synchronized(writeLock) {
                            if (socket.isClosed) throw java.net.SocketException("Closed")
                            
                            val formatStr = if (audio.isCompressed) "format=aac|" else ""
                            writeLine("AUDIO_FRAME|dir=down|${formatStr}tsUs=${audio.tsUs}|size=${audio.pcm.size}|rate=${audio.rate}|ch=${audio.ch}")
                            out.write(audio.pcm)
                            out.flush()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "[VIEWER SESSION] Audio send error: ${e.message}")
                        // Don't close session on audio error immediately?
                        // Actually, if socket is broken, we should close.
                        throw e
                    }
                }

            }  catch (e: Exception) {
                 Log.i(TAG, "[VIEWER SESSION] Audio Sender stopped: ${e.message}")
                 // If audio sender dies, we might want to kill the session too
                 close()
            }
        }
    }

    /* ===============================
     * Cleanup
     * =============================== */
    fun close() {
        // #region agent log
        DebugLog.log("primary", PrimaryDebugRunId.runId, "C", "ViewerSession.close", "session", mapOf("event" to "session_close_start", "sessionId" to sessionId))
        // #endregion
        if (!stateWrapper.compareAndSet(false, true)) return // Idempotent check

        Log.d(TAG, "[VIEWER SESSION] Closing session ${socket.inetAddress.hostAddress}")
        state = StreamState.DISCONNECTED
        senderRunning = false
        frameQueue.clear()
        controlQueue.clear()
        streamStateQueue.clear()
        atomicBatchQueue.clear()

        try {
            socket.close()
        } catch (_: Exception) {
        }
        // Do not shut down sessionPool — it is shared (StreamingExecutors)
        onSessionClosed(this)
    }

    /* ===============================
     * Helpers
     * =============================== */
    private fun parseParams(line: String): Map<String, String> {
        return line.substringAfter("|")
            .split("|")
            .associate {
                val (k, v) = it.split("=")
                k to v
            }
    }

    private fun handleAuthSuccess() {
        if (authenticated.compareAndSet(false, true)) {
            Log.d(TAG, "[VIEWER SESSION] Authentication successful — activating session")
            state = StreamState.AUTHENTICATED
            // Full state snapshot after AUTH in one atomic batch so order cannot regress on some OEM kernels (STATE_MACHINE.md).
            sendControlAtomic(
                "SESSION|id=$sessionId",
                "PROTO|version=$protocolVersion",
                "STREAM_STATE|$STREAM_STATE_RECONFIGURING|epoch=$streamEpoch"
            )
            // Sender NOT started here - waiting for enableStreaming()
            onAuthenticated(this)
        } else {
            Log.w(TAG, "[VIEWER SESSION] HandleAuthSuccess called but already authenticated (ignored)")
        }
    }

    /**
     * Enable streaming for this session.
     * Must be called ONLY after the server has sent STREAM_ACCEPTED.
     * We stay in RECONFIGURING until the first keyframe is sent; then we send STREAM_STATE|ACTIVE (STATE_MACHINE.md).
     */
    fun enableStreaming(epoch: Long) {
        if (state == StreamState.STREAMING) {
            Log.w(TAG, "[VIEWER SESSION] enableStreaming called but already STREAMING (ignored)")
            return
        }
        Log.i("VIEWER SESSION", "enableStreaming(epoch=$epoch)")
        setStreamEpoch(epoch)
        activeSentForThisEpoch = false
        state = StreamState.RECONFIGURING
        Log.i("VIEWER SESSION", "state -> RECONFIGURING (ACTIVE sent after first keyframe)")
    }

    private fun startHeartbeatWatchdog() {
        sessionPool.execute {
            try {
                while (state != StreamState.DISCONNECTED) {
                    Thread.sleep(HEARTBEAT_CHECK_INTERVAL_MS)

                    val nowUptime = SystemClock.uptimeMillis()
                    // Server-side keepalive:
                    // Viewer expects PONGs to confirm the connection is alive. If the viewer heartbeat sender is
                    // starved (OEM scheduling), we still want to avoid triggering reconnect storms.
                    // PONG is safe for older clients too (they already handle PONG lines).
                    try {
                        sendControl("PONG|srvMs=${System.currentTimeMillis()}")
                    } catch (_: Throwable) {
                    }
                    if (nowUptime - lastPingUptimeMs > HEARTBEAT_TIMEOUT_MS) {
                        Log.w(
                            TAG,
                            "[VIEWER SESSION] Heartbeat timeout from ${socket.inetAddress.hostAddress}"
                        )
                        close()
                        break
                    }
                }
            } catch (_: InterruptedException) {
                // Normal shutdown
            }
        }
    }
    fun sendControl(msg: String) {
        // Never write to the socket on the caller thread (caller can be the UI thread).
        controlQueue.offer(ControlItem.Line(msg))
    }

    /**
     * Send multiple control lines in one batch with one flush. Use for critical sequences (e.g. STREAM_ACCEPTED + STREAM_STATE|2)
     * so they cannot be reordered or interleaved with frames on some OEM kernels. Sender thread drains atomicBatchQueue first.
     */
    fun sendControlAtomic(vararg messages: String) {
        if (messages.isEmpty()) return
        atomicBatchQueue.offer(messages.toList())
    }

    /**
     * Schedule STREAM_STATE to be sent from the sender thread only. Ensures a single authority path so ordering cannot regress
     * (e.g. ACTIVE then late RECONFIGURING). Call from listener or from outside (e.g. broadcast); sender thread drains and writes.
     */
    private fun scheduleStreamState(code: Int, epoch: Long) {
        sessionPool.execute { streamStateQueue.offer(Pair(code, epoch)) }
    }

    /**
     * Send STREAM_STATE|STOPPED so the viewer can distinguish "stream intentionally ended" from "network lost".
     * Mandatory terminal state. Funneled through sender thread like all state.
     */
    fun sendStreamStateStopped() {
        val epoch = streamEpoch
        sessionPool.execute { streamStateQueue.offer(Pair(STREAM_STATE_STOPPED, epoch)) }
    }

    fun sendCsd(sps: ByteArray, pps: ByteArray) {
        // Never write to the socket on the caller thread (caller can be the UI thread).
        controlQueue.offer(ControlItem.Csd(sps, pps))
    }

    fun sendEncoderRotation(deg: Int) {
        // Informational only: how many degrees the Primary's encoder is rotating pixels by (0/90/180/270).
        // Viewers should NOT apply an extra rotation transform if the stream is already rotated.
        sendControl("ENC_ROT|deg=$deg")
    }

    private fun hmacSha256(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key.toByteArray(), "HmacSHA256")
        mac.init(secretKey)
        return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }


    fun sendRecordingState(active: Boolean) {
        sendControl("RECORDING|active=$active")
    }

    fun sendAudioDown(pcm: ByteArray, tsUs: Long, rate: Int, ch: Int, isCompressed: Boolean = false) {
        audioQueue.offer(AudioItem(pcm, tsUs, rate, ch, isCompressed))
    }

    private fun writeLine(s: String) {
        // Use shared ProtocolIO utility to avoid code duplication
        ProtocolIO.writeLineUtf8(out, s)
    }


}
