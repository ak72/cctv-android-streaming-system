package com.anurag.cctvprimary

import android.util.Log
import android.os.SystemClock
import org.json.JSONObject
import java.io.File

/** Set by CameraForegroundService.onCreate(); used by ViewerSession and StreamServer for debug log runId. */
object PrimaryDebugRunId {
    @Volatile var runId: String? = null
}

/**
 * NDJSON debug logging for root-cause analysis (background/foreground and crash).
 * Emit with tag CCTV_DEBUG so logs can be captured with: adb logcat -s CCTV_DEBUG
 * When file sink is installed, each log is also appended to that file (for adb pull to .cursor/debug.log).
 */
object DebugLog {
    private const val TAG = "CCTV_DEBUG"
    @Volatile private var fileSink: File? = null
    private val fileSinkLock = Any()

    /** Call from service onCreate to also write NDJSON to app storage; user can adb pull to .cursor/debug.log */
    fun installFileSink(file: File?) {
        fileSink = file
    }

    fun log(
        app: String,
        runId: String?,
        hypothesisId: String,
        location: String,
        message: String,
        data: Map<String, Any?>
    ) {
        try {
            val dataObj = JSONObject()
            data.forEach { (k, v) ->
                when (v) {
                    null -> dataObj.put(k, JSONObject.NULL)
                    is Number -> dataObj.put(k, v)
                    is Boolean -> dataObj.put(k, v)
                    else -> dataObj.put(k, v.toString())
                }
            }
            val obj = JSONObject().apply {
                put("timestamp", SystemClock.uptimeMillis())
                put("sessionId", "debug-session")
                put("app", app)
                put("runId", runId ?: "")
                put("hypothesisId", hypothesisId)
                put("location", location)
                put("message", message)
                put("data", dataObj)
            }
            val line = obj.toString()
            Log.d(TAG, line)
            // #region agent log
            val f = fileSink
            if (f != null) {
                synchronized(fileSinkLock) {
                    try { f.appendText("$line\n") } catch (_: Throwable) { }
                }
            }
            // #endregion
        } catch (t: Throwable) {
            Log.w(TAG, "DebugLog failed", t)
        }
    }
}
