package com.anurag.cctvprimary

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class ProtocolIOTest {
    @Test
    fun readLineUtf8_doesNotConsumeBinaryPayload() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val bytes = ("AUDIO_FRAME|size=4\n").toByteArray(Charsets.UTF_8) + payload
        val input = ByteArrayInputStream(bytes)

        val line = ProtocolIO.readLineUtf8(input)
        assertEquals("AUDIO_FRAME|size=4", line)

        val readPayload = ByteArray(4)
        ProtocolIO.readFullyExact(input, readPayload)
        assertArrayEquals(payload, readPayload)

        // EOF now
        assertNull(ProtocolIO.readLineUtf8(input))
    }

    @Test
    fun readLineUtf8_stripsCRLF() {
        val input = ByteArrayInputStream("PING\r\n".toByteArray(Charsets.UTF_8))
        val line = ProtocolIO.readLineUtf8(input)
        assertEquals("PING", line)
        assertNull(ProtocolIO.readLineUtf8(input))
    }
}

