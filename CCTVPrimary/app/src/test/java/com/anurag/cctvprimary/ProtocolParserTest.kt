package com.anurag.cctvprimary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for protocol parser robustness.
 * Malformed input must never throw; invalid values return null so callers can send ERROR responses.
 */
class ProtocolParserTest {

    // --- parseParams ---

    @Test
    fun parseParams_validKeyValuePairs() {
        val params = ProtocolParser.parseParams("CAPS|maxWidth=1920|maxHeight=1080|maxBitrate=2000000")
        assertEquals("1920", params["maxWidth"])
        assertEquals("1080", params["maxHeight"])
        assertEquals("2000000", params["maxBitrate"])
    }

    @Test
    fun parseParams_malformedSegmentWithoutEquals_skipped() {
        val params = ProtocolParser.parseParams("CAPS|maxWidth=1920|maxHeight|maxBitrate=2000000")
        assertEquals("1920", params["maxWidth"])
        assertNull(params["maxHeight"])
        assertEquals("2000000", params["maxBitrate"])
    }

    @Test
    fun parseParams_emptyLine_returnsEmpty() {
        val params = ProtocolParser.parseParams("CAPS|")
        assertEquals(emptyMap<String, String>(), params)
    }

    @Test
    fun parseParams_noPipe_returnsEmpty() {
        val params = ProtocolParser.parseParams("HELLO")
        assertEquals(emptyMap<String, String>(), params)
    }

    @Test
    fun parseParams_emptyValue_allowed() {
        val params = ProtocolParser.parseParams("FOO|key=")
        assertEquals("", params["key"])
    }

    // --- parseCaps ---

    @Test
    fun parseCaps_valid() {
        val params = mapOf("maxWidth" to "1920", "maxHeight" to "1080", "maxBitrate" to "2000000")
        val caps = ProtocolParser.parseCaps(params)
        assertEquals(1920, caps!!.maxWidth)
        assertEquals(1080, caps.maxHeight)
        assertEquals(2000000, caps.maxBitrate)
    }

    @Test
    fun parseCaps_nonNumeric_returnsNull() {
        assertNull(ProtocolParser.parseCaps(mapOf("maxWidth" to "abc", "maxHeight" to "1080", "maxBitrate" to "2000000")))
        assertNull(ProtocolParser.parseCaps(mapOf("maxWidth" to "1920", "maxHeight" to "x", "maxBitrate" to "2000000")))
        assertNull(ProtocolParser.parseCaps(mapOf("maxWidth" to "1920", "maxHeight" to "1080", "maxBitrate" to "")))
    }

    @Test
    fun parseCaps_missingKey_returnsNull() {
        assertNull(ProtocolParser.parseCaps(mapOf("maxWidth" to "1920", "maxHeight" to "1080")))
        assertNull(ProtocolParser.parseCaps(mapOf("maxHeight" to "1080", "maxBitrate" to "2000000")))
    }

    @Test
    fun parseCaps_zeroOrNegative_returnsNull() {
        assertNull(ProtocolParser.parseCaps(mapOf("maxWidth" to "0", "maxHeight" to "1080", "maxBitrate" to "2000000")))
        assertNull(ProtocolParser.parseCaps(mapOf("maxWidth" to "1920", "maxHeight" to "-1", "maxBitrate" to "2000000")))
    }

    @Test
    fun parseCaps_hostileInput_returnsNull() {
        assertNull(ProtocolParser.parseCaps(mapOf("maxWidth" to "999999999", "maxHeight" to "1080", "maxBitrate" to "2000000")))
        assertNull(ProtocolParser.parseCaps(mapOf("maxWidth" to "1920", "maxHeight" to "1080", "maxBitrate" to "overflow")))
    }

    // --- parseSetStream ---

    @Test
    fun parseSetStream_valid() {
        val params = mapOf("width" to "1280", "height" to "720", "bitrate" to "1000000", "fps" to "30")
        val cfg = ProtocolParser.parseSetStream(params)
        assertEquals(1280, cfg!!.width)
        assertEquals(720, cfg.height)
        assertEquals(1000000, cfg.bitrate)
        assertEquals(30, cfg.fps)
    }

    @Test
    fun parseSetStream_nonNumeric_returnsNull() {
        assertNull(ProtocolParser.parseSetStream(mapOf("width" to "abc", "height" to "720", "bitrate" to "1000000", "fps" to "30")))
        assertNull(ProtocolParser.parseSetStream(mapOf("width" to "1280", "height" to "720", "bitrate" to "1e6", "fps" to "30")))
    }

    @Test
    fun parseSetStream_missingKey_returnsNull() {
        assertNull(ProtocolParser.parseSetStream(mapOf("width" to "1280", "height" to "720", "bitrate" to "1000000")))
    }

    @Test
    fun parseSetStream_fpsOutOfRange_returnsNull() {
        assertNull(ProtocolParser.parseSetStream(mapOf("width" to "1280", "height" to "720", "bitrate" to "1000000", "fps" to "0")))
        assertNull(ProtocolParser.parseSetStream(mapOf("width" to "1280", "height" to "720", "bitrate" to "1000000", "fps" to "121")))
    }

    @Test
    fun parseSetStream_zeroOrNegative_returnsNull() {
        assertNull(ProtocolParser.parseSetStream(mapOf("width" to "0", "height" to "720", "bitrate" to "1000000", "fps" to "30")))
    }

    // --- parseHelloVersion ---

    @Test
    fun parseHelloVersion_valid() {
        assertEquals(2, ProtocolParser.parseHelloVersion(mapOf("version" to "2")))
        assertEquals(3, ProtocolParser.parseHelloVersion(mapOf("version" to "3")))
        assertEquals(2, ProtocolParser.parseHelloVersion(mapOf("version" to "1"))) // coerced to 2
        assertEquals(3, ProtocolParser.parseHelloVersion(mapOf("version" to "99"))) // coerced to 3
    }

    @Test
    fun parseHelloVersion_missingOrInvalid_defaultsTo2() {
        assertEquals(2, ProtocolParser.parseHelloVersion(emptyMap()))
        assertEquals(2, ProtocolParser.parseHelloVersion(mapOf("version" to "abc")))
        assertEquals(2, ProtocolParser.parseHelloVersion(mapOf("version" to "")))
    }

    // --- integration: parseParams + parseCaps/parseSetStream (never throws) ---

    @Test
    fun fullLineParseCaps_malformed_doesNotThrow() {
        val params = ProtocolParser.parseParams("CAPS|maxWidth=bad|maxHeight=1080|maxBitrate=2000000")
        val caps = ProtocolParser.parseCaps(params)
        assertNull(caps)
    }

    @Test
    fun fullLineParseSetStream_malformed_doesNotThrow() {
        val params = ProtocolParser.parseParams("SET_STREAM|width=xyz|height=720|bitrate=1000000|fps=30")
        val cfg = ProtocolParser.parseSetStream(params)
        assertNull(cfg)
    }

    @Test
    fun fullLineParseParams_malformedSegments_skipped_doesNotThrow() {
        val params = ProtocolParser.parseParams("CAPS|maxWidth=1920|nokey|maxHeight=1080|maxBitrate=2000000")
        assertEquals("1920", params["maxWidth"])
        assertEquals("1080", params["maxHeight"])
        assertEquals("2000000", params["maxBitrate"])
        val caps = ProtocolParser.parseCaps(params)
        assertEquals(1920, caps!!.maxWidth)
    }
}
