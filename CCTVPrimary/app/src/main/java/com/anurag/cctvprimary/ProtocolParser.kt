package com.anurag.cctvprimary

/**
 * Robust protocol parameter parsing for ViewerSession inbound messages.
 * Never throws; malformed input returns null or empty map so callers can send ERROR responses.
 */
object ProtocolParser {

    /**
     * Parse pipe-separated key=value params from the part after the first "|".
     * Malformed segments (no "=") are skipped. Never throws.
     */
    fun parseParams(line: String): Map<String, String> {
        val afterPipe = line.indexOf('|').let { if (it < 0) return emptyMap(); line.substring(it + 1) }
        return afterPipe.split("|").mapNotNull { segment ->
            val eq = segment.indexOf('=')
            if (eq < 0) null else segment.substring(0, eq) to segment.substring(eq + 1)
        }.toMap()
    }

    /**
     * Parse and validate CAPS params. Returns null if malformed or invalid (non-positive).
     */
    fun parseCaps(params: Map<String, String>): ViewerSession.ViewerCaps? {
        val maxWidth = params["maxWidth"]?.toIntOrNull() ?: return null
        val maxHeight = params["maxHeight"]?.toIntOrNull() ?: return null
        val maxBitrate = params["maxBitrate"]?.toIntOrNull() ?: return null
        if (maxWidth <= 0 || maxHeight <= 0 || maxBitrate <= 0) return null
        return ViewerSession.ViewerCaps(maxWidth = maxWidth, maxHeight = maxHeight, maxBitrate = maxBitrate)
    }

    /**
     * Parse and validate SET_STREAM params. Returns null if malformed or invalid.
     * fps must be in 1..120.
     */
    fun parseSetStream(params: Map<String, String>): StreamConfig? {
        val width = params["width"]?.toIntOrNull() ?: return null
        val height = params["height"]?.toIntOrNull() ?: return null
        val bitrate = params["bitrate"]?.toIntOrNull() ?: return null
        val fps = params["fps"]?.toIntOrNull() ?: return null
        if (width <= 0 || height <= 0 || bitrate <= 0 || fps <= 0 || fps > 120) return null
        return StreamConfig(width = width, height = height, bitrate = bitrate, fps = fps)
    }

    /**
     * Parse HELLO version, coerced to 2..3. Returns 2 if missing/invalid.
     */
    fun parseHelloVersion(params: Map<String, String>): Int =
        params["version"]?.toIntOrNull()?.coerceIn(2, 3) ?: 2
}
