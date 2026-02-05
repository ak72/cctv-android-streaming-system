package com.anurag.cctvprimary

import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue

private const val TAG = "CCTV_CommandBus"

/**
 * Single-consumer command bus. All remote commands and keyframe requests are posted here;
 * the consumer runs on the control thread and executes them, so no session/accept thread
 * ever holds encoder or camera locks (zero-deadlock thread model).
 */
class CommandBus(
    private val executor: ExecutorService,
    private val handler: (StreamCommand) -> Unit
) {
    private val queue = LinkedBlockingQueue<StreamCommand>()
    @Volatile
    private var consumerStarted = false

    /**
     * Post a command to be executed on the control thread. Non-blocking; safe to call from any thread.
     */
    fun post(cmd: StreamCommand): Boolean {
        if (!queue.offer(cmd)) {
            Log.w(TAG, "Command bus queue full, dropping command: $cmd")
            return false
        }
        return true
    }

    /**
     * Start the single consumer loop on the control executor. Idempotent.
     */
    fun start() {
        if (consumerStarted) return
        consumerStarted = true
        executor.execute {
            try {
                while (true) {
                    val cmd = queue.take()
                    try {
                        handler(cmd)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Command handler failed for $cmd", t)
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
