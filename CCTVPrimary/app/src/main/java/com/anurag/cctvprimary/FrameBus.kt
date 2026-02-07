package com.anurag.cctvprimary

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Lock-free broadcast hub for encoded frames. Encoder publishes via [publish] (non-blocking);
 * a single consumer (e.g. StreamServer sender loop) drains via [pollWithTimeout] so that
 * stop() can interrupt the drain cleanly (loop checks senderRunning when poll times out).
 *
 * Contract: encoder must only call [publish]; consumer runs on a dedicated thread and calls [pollWithTimeout]/[poll].
 *
 * Drop semantics:
 * - Bounded queue; [publish] returns false when full. The caller must check the return value and handle.
 * - The single producer (StreamServer.enqueueFrame) applies **DROP_NON_KEYFRAME_ON_FULL**: when the bus is full,
 *   if the frame is a keyframe, the queue is cleared and the keyframe is re-offered; if non-keyframe, the frame
 *   is dropped. Publish failures are not silent—StreamServer logs and prioritizes as above.
 */
class FrameBus(capacity: Int) {
    private val queue = LinkedBlockingQueue<EncodedFrame>(capacity)

    /**
     * Non-blocking offer. Returns true if the frame was enqueued. Caller should drop or prioritize
     * (e.g. keyframe) when false.
     */
    fun publish(frame: EncodedFrame): Boolean = queue.offer(frame)

    /**
     * Blocking poll with timeout. Returns null if no frame within timeoutMs.
     * Allows the sender loop to check senderRunning and exit when stop() is called.
     */
    fun pollWithTimeout(timeoutMs: Long): EncodedFrame? =
        queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

    /** Non-blocking poll. */
    fun poll(): EncodedFrame? = queue.poll()

    /** Current size (for metrics and load-shedding). */
    fun size(): Int = queue.size

    /** Clear all pending frames (e.g. when prioritizing a keyframe under overload). */
    fun clear() {
        queue.clear()
    }
}
