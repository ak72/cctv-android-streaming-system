package com.anurag.cctvprimary

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Helpers for a mixed text+binary protocol over a single TCP stream.
 *
 * Key rule: do NOT mix BufferedReader/Reader-based line reading with raw InputStream reads,
 * because Reader implementations can buffer/prefetch bytes and corrupt binary framing.
 */
object ProtocolIO {
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

