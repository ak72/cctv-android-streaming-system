package com.anurag.cctvviewer

import android.os.Process
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Centralized executor manager for StreamClient.
 * Owns all 7 single-thread executors and encapsulates create/recreate/shutdown lifecycle.
 */
internal class StreamClientExecutors {

    enum class Role(val threadName: String, val setPriority: Boolean = false) {
        CONNECT("CCTV-Connect"),
        DECODE("CCTV-Decode", setPriority = true),
        HEARTBEAT("CCTV-Heartbeat"),
        SENDER("CCTV-Client-Sender"),
        AUDIO_RECORD("CCTV-Audio-Talk"),
        AUDIO_PLAYBACK("CCTV-Audio-Playback"),
        RECONNECT("CCTV-Reconnect")
    }

    private val lock = Any()
    private val executors = mutableMapOf<Role, ExecutorService>()
    private val senderClosed = AtomicBoolean(false)

    /**
     * Run [block] on the executor for [role].
     * For SENDER, no-op if sender is closed (avoids RejectedExecutionException).
     */
    fun execute(role: Role, block: () -> Unit) {
        if (role == Role.SENDER && senderClosed.get()) return
        val exec = getOrCreate(role)
        if (role == Role.SENDER && senderClosed.get()) return
        exec.execute(block)
    }

    private fun getOrCreate(role: Role): ExecutorService {
        synchronized(lock) {
            var exec = executors[role]
            if (exec == null || exec.isShutdown) {
                exec = createExecutor(role)
                executors[role] = exec
            }
            return exec
        }
    }

    private fun createExecutor(role: Role): ExecutorService {
        val factory = when (role) {
            Role.DECODE -> java.util.concurrent.ThreadFactory { r ->
                Thread {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
                    r.run()
                }.apply {
                    name = role.threadName
                    isDaemon = true
                }
            }
            else -> java.util.concurrent.ThreadFactory { r ->
                Thread(r, role.threadName).apply { isDaemon = true }
            }
        }
        return Executors.newSingleThreadExecutor(factory)
    }

    /**
     * Shut down SENDER executor only (called from closeSocket/disconnect).
     */
    fun closeSender() {
        if (senderClosed.compareAndSet(false, true)) {
            synchronized(lock) {
                executors[Role.SENDER]?.let { if (!it.isShutdown) it.shutdownNow() }
                executors.remove(Role.SENDER)
            }
        }
    }

    /**
     * Ensure all 7 executors exist; recreate any that are shutdown (used at connect time).
     * Resets senderClosed so SENDER can accept work after a previous shutdown.
     */
    fun ensureAll() {
        synchronized(lock) {
            Role.entries.forEach { getOrCreate(it) }
            senderClosed.set(false)
        }
    }

    /**
     * Shut down all executors (called from shutdown()).
     */
    fun shutdownAll() {
        synchronized(lock) {
            executors.values.forEach { if (!it.isShutdown) it.shutdownNow() }
            executors.clear()
        }
    }
}
