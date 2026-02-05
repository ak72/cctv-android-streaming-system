package com.anurag.cctvprimary

import java.util.concurrent.LinkedBlockingQueue

/**
 * Lock-free broadcast hub for encoded frames. Encoder publishes via [publish] (non-blocking);
 * a single consumer (e.g. StreamServer sender loop) drains via [take]. Decouples encoder from
 * sessions so the encoder thread never blocks on session or socket work.
 *
 * Contract: encoder must only call [publish]; consumer runs on a dedicated thread and calls [take]/[poll].
 */
class FrameBus(capacity: Int) {
    private val queue = LinkedBlockingQueue<EncodedFrame>(capacity)

    /**
     * Non-blocking offer. Returns true if the frame was enqueued. Caller should drop or prioritize
     * (e.g. keyframe) when false.
     */
    fun publish(frame: EncodedFrame): Boolean = queue.offer(frame)

    /** Blocking take for the sender loop. */
    fun take(): EncodedFrame = queue.take()

    /** Non-blocking poll. */
    fun poll(): EncodedFrame? = queue.poll()

    /** Current size (for metrics and load-shedding). */
    fun size(): Int = queue.size

    /** Clear all pending frames (e.g. when prioritizing a keyframe under overload). */
    fun clear() {
        queue.clear()
    }
}
