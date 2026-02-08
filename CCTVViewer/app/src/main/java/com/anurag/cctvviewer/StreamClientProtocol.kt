package com.anurag.cctvviewer

/**
 * Parses "CMD|k=v|k2=v2" style protocol lines into a map of params.
 * The first token ("CMD") is ignored.
 */
internal fun parseParams(line: String): Map<String, String> =
    line.split("|").drop(1).mapNotNull {
        val trimmed = it.trim()
        val p = trimmed.split("=", limit = 2)
        if (p.size == 2) p[0].trim() to p[1].trim() else null
    }.toMap()

/** Parsed STREAM_STATE: code (1=ACTIVE, 2=RECONFIGURING, 3=PAUSED, 4=STOPPED), epoch. */
internal data class StreamStateParsed(val code: Int, val epoch: Long?)

/** Parsed FRAME header (epoch, seq, size, isKeyFrame). Binary payload is read separately. */
internal data class FrameHeaderParsed(
    val epoch: Long,
    val seq: Long,
    val size: Int,
    val isKeyFrame: Boolean
)

/** Parsed CSD header (epoch, spsSize, ppsSize). Binary payload is read separately. */
internal data class CsdHeaderParsed(val epoch: Long, val spsSize: Int, val ppsSize: Int)

/** STREAM_STATE codes. */
internal const val STREAM_STATE_ACTIVE = 1
internal const val STREAM_STATE_RECONFIGURING = 2
internal const val STREAM_STATE_PAUSED = 3
internal const val STREAM_STATE_STOPPED = 4

/**
 * Parses STREAM_STATE|{code}|epoch={n}.
 * Returns null if malformed (e.g. code not a number).
 */
internal fun parseStreamState(line: String): StreamStateParsed? {
    if (!line.startsWith("STREAM_STATE|")) return null
    val codeStr = line.substringAfter("STREAM_STATE|").substringBefore("|")
    val code = codeStr.toIntOrNull() ?: return null
    val params = parseParams(line)
    val epoch = params["epoch"]?.toLongOrNull()
    return StreamStateParsed(code, epoch)
}

/**
 * Parses FRAME|epoch=...|seq=...|size=...|key=... header.
 * Returns null if malformed or size invalid.
 */
internal fun parseFrameHeader(line: String): FrameHeaderParsed? {
    if (!line.startsWith("FRAME|")) return null
    val params = parseParams(line)
    val epoch = params["epoch"]?.toLongOrNull() ?: 0L
    val seq = params["seq"]?.toLongOrNull() ?: -1L
    val size = params["size"]?.toIntOrNull() ?: 0
    val isKeyFrame = params["key"]?.toBooleanStrictOrNull() ?: false
    return FrameHeaderParsed(epoch, seq, size, isKeyFrame)
}

/**
 * Parses CSD|epoch=...|sps=...|pps=... header.
 * Returns null if malformed or sizes invalid.
 */
internal fun parseCsdHeader(line: String): CsdHeaderParsed? {
    if (!line.startsWith("CSD|")) return null
    val params = parseParams(line)
    val epoch = params["epoch"]?.toLongOrNull() ?: 0L
    val spsSize = params["sps"]?.toIntOrNull() ?: 0
    val ppsSize = params["pps"]?.toIntOrNull() ?: 0
    if (spsSize <= 0 || ppsSize <= 0) return null
    return CsdHeaderParsed(epoch, spsSize, ppsSize)
}

/**
 * Epoch gating: should a frame with msgEpoch be dropped when current stream epoch is currentEpoch?
 * Matches StreamClient logic: drop when both > 0 and msgEpoch != currentEpoch.
 */
internal fun shouldDropFrameByEpoch(currentEpoch: Long, msgEpoch: Long): Boolean =
    currentEpoch > 0L && msgEpoch > 0L && msgEpoch != currentEpoch

/**
 * Stale STREAM_STATE check: should a STREAM_STATE with msgEpoch be ignored when current is currentEpoch?
 * Matches StreamClient: ignore when msgEpoch is valid (>0) and < currentEpoch.
 */
internal fun shouldIgnoreStreamStateByEpoch(currentEpoch: Long, msgEpoch: Long?): Boolean =
    msgEpoch != null && msgEpoch > 0L && msgEpoch < currentEpoch

/**
 * Maps STREAM_STATE code to Viewer action. STOPPED (4) means disconnect with no auto-reconnect.
 */
internal fun streamStateCodeToConnectionState(code: Int): ConnectionState? = when (code) {
    STREAM_STATE_ACTIVE -> ConnectionState.STREAMING
    STREAM_STATE_RECONFIGURING -> ConnectionState.RECOVERING
    STREAM_STATE_PAUSED -> ConnectionState.CONNECTED
    STREAM_STATE_STOPPED -> ConnectionState.IDLE
    else -> null
}

