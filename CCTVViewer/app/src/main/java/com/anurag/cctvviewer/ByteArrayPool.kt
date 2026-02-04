package com.anurag.cctvviewer

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Thread-safe pool for reusing ByteArrays to reduce GC churn.
 * Uses a bucketed strategy based on powers of 2.
 */
object ByteArrayPool {
    private const val MAX_POOL_SIZE = 50 // Max buffers per bucket to prevent bloating
    
    // Buckets for sizes 2^0 to 2^21 (2MB)
    // Index i stores buffers of size 2^i
    private val buckets = Array(22) { ConcurrentLinkedQueue<ByteArray>() }

    private val totalBytes = java.util.concurrent.atomic.AtomicInteger(0)
    private const val MAX_POOL_BYTES = 8 * 1024 * 1024 // 8MB soft cap

    /**
     * Get a byte array with at least minSize capacity.
     * returned array size will be a power of 2.
     */
    fun get(minSize: Int): ByteArray {
        if (minSize <= 0) return ByteArray(0)

        val index = getBucketIndex(minSize)
        
        // If huge allocation requested (> 2MB), don't pool it
        if (index >= buckets.size) {
            // Log.w(TAG, "Allocating huge buffer outside pool: $minSize bytes")
            // Allocate exact size or power of 2? Power of 2 is safer for future logic consistency
            // but exact size saves memory for one-offs.
            // Let's allocation power of 2 to be consistent with pool behavior
            val cap = 1 shl index
            return ByteArray(cap)
        }

        val bucket = buckets[index]
        val pooled = bucket.poll()
        if (pooled != null) {
            totalBytes.addAndGet(-(pooled.size))
            return pooled
        }

        // Allocate new power-of-2 buffer
        val capacity = 1 shl index
        return ByteArray(capacity)
    }

    /**
     * Recycle a byte array back into the pool.
     */
    fun recycle(buf: ByteArray?) {
        if (buf == null || buf.isEmpty()) return

        val size = buf.size
        // Check if size is power of 2
        if (!isPowerOfTwo(size)) {
            // Log.w(TAG, "Not recycling buffer of non-power-of-2 size: $size")
            return
        }

        val index = getBucketIndex(size)
        // Note: getBucketIndex returns index where 2^index >= size.
        // Since size IS power of 2, 2^index == size.
        
        if (index < buckets.size) {
            // Soft Cap Check: Drop if we exceed max pool bytes
            val current = totalBytes.get()
            if (current + size > MAX_POOL_BYTES) {
                return // Drop oversized/excess buffer
            }

            val bucket = buckets[index]
            // Limit pool size
            if (bucket.size < MAX_POOL_SIZE) {
                bucket.offer(buf)
                totalBytes.addAndGet(size)
            }
        }
    }

    private fun getBucketIndex(size: Int): Int {
        if (size <= 1) return 0
        // Find next power of 2
        // 32 - numberOfLeadingZeros(size - 1) gives ceil(log2(size))
        return 32 - Integer.numberOfLeadingZeros(size - 1)
    }

    private fun isPowerOfTwo(n: Int): Boolean {
        return (n > 0) && ((n and (n - 1)) == 0)
    }
    

}
