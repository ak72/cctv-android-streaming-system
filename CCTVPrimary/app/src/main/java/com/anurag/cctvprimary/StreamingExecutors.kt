package com.anurag.cctvprimary

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Shared executors for streaming and capture to avoid thread/executor explosion.
 * Reuse everywhere instead of creating new SingleThreadExecutors per component/session.
 * Improves thermal behavior and reduces scheduler cost.
 *
 * - [ioExecutor]: accept loop (StreamServer), other I/O
 * - [senderExecutor]: StreamServer fan-out sender loop
 * - [controlExecutor]: CommandBus consumer loop, camera bind/unbind (single thread; do not use for recording — CommandBus blocks it)
 * - [recordingExecutor]: start/stop recording only (separate so it is not starved by CommandBus)
 * - [encodingExecutor]: ImageAnalysis and other encoding-related work (kept separate from control to avoid stalls)
 * - [sessionPool]: fixed pool for ViewerSession runnables (listener, video sender, audio sender, heartbeat); caps total session threads
 *
 * Do not shut down these executors from component stop() — they are process-wide.
 */
object StreamingExecutors {
    private fun singleThreadExecutor(name: String): ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, name).apply { isDaemon = true }
        }

    val ioExecutor: ExecutorService = singleThreadExecutor("CCTV-IO")
    val senderExecutor: ExecutorService = singleThreadExecutor("CCTV-Sender")
    val controlExecutor: ExecutorService = singleThreadExecutor("CCTV-Control")
    val recordingExecutor: ExecutorService = singleThreadExecutor("CCTV-Recording")
    val encodingExecutor: ExecutorService = singleThreadExecutor("CCTV-Encoding")

    /** Fixed pool for per-session work (listener, video sender, audio sender, heartbeat). Supports up to 4 concurrent viewers. */
    val sessionPool: ExecutorService = Executors.newFixedThreadPool(16) { r ->
        Thread(r, "CCTV-Session").apply { isDaemon = true }
    }
}
