package com.anurag.cctvprimary

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Helpers for a mixed text+binary protocol over a single TCP stream.
 *
 * Key rule: do NOT mix BufferedReader/Reader-based line reading with raw InputStream reads,
 * because Reader implementations can buffer/prefetch bytes and corrupt binary framing.
 *
 * Framed transport (protocol version >= 3): video frames are sent as [marker][header-payload]
 * so the viewer reads exact bytes with no text parsing. Header: epoch (4B), flags (4B), size (4B), big-endian.
 */
object ProtocolIO {
    /** Single byte before a binary frame so the viewer can distinguish from text lines (control messages are ASCII). */
    const val BINARY_FRAME_MARKER: Int = 0x00

    /** Fixed header size: marker (1) + epoch (4) + flags (4) + size (4) = 13 bytes. */
    const val BINARY_FRAME_HEADER_SIZE: Int = 13

    private val headerBuffer =
        ByteBuffer.allocate(BINARY_FRAME_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)

    /**
     * Write a binary video frame: [marker][epoch][flags][size][payload].
     * Viewer reads exactly BINARY_FRAME_HEADER_SIZE bytes then exactly size bytes.
     * Flags: bit0 = keyframe (1), else 0.
     */
    @Synchronized
    fun writeBinaryVideoFrame(
        out: OutputStream,
        epoch: Long,
        keyframe: Boolean,
        payload: ByteArray
    ) {
        headerBuffer.clear()
        headerBuffer.put(BINARY_FRAME_MARKER.toByte())
        headerBuffer.putInt(epoch.toInt())
        headerBuffer.putInt(if (keyframe) 1 else 0)
        headerBuffer.putInt(payload.size)
        out.write(headerBuffer.array(), 0, headerBuffer.position())
        out.write(payload)
    }

    /**
     * Read the binary frame header (after the marker byte). Caller must have already read the marker.
     * Returns (epoch, flags, size). Uses the shared header buffer; not thread-safe for concurrent reads.
     */
    /* readBinaryFrameHeader is the read-side API for binary framing (protocol v3), reserved for future Viewer support.*/
    @Suppress("unused")
    fun readBinaryFrameHeader(input: InputStream): Triple<Long, Int, Int> {
        val buf = ByteArray(12)
        readFullyExact(input, buf)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN)
        val epoch = bb.int.toLong() and 0xFFFF_FFFFL
        val flags = bb.int
        val size = bb.int
        return Triple(epoch, flags, size)
    }

    fun readLineUtf8(input: InputStream): String? {
        val sb = StringBuilder(128)
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (sb.isEmpty()) null else sb.toString().trimEnd('\r')
            }
            if (b == '\n'.code) {
                return sb.toString().trimEnd('\r')
            }
            sb.append(b.toChar())
        }
    }

    fun writeLineUtf8(out: OutputStream, line: String) {
        out.write(line.toByteArray(Charsets.UTF_8))
        out.write('\n'.code)
    }

    fun readFullyExact(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) throw EOFException("EOF while reading ${buf.size} bytes")
            off += r
        }
    }
}

