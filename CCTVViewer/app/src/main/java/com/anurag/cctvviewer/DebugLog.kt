package com.anurag.cctvviewer

import android.util.Log
import android.os.SystemClock
import org.json.JSONObject

/**
 * NDJSON debug logging for root-cause analysis (background/foreground and crash).
 * Emit with tag CCTV_DEBUG so logs can be captured with: adb logcat -s CCTV_DEBUG
 */
object DebugLog {
    private const val TAG = "CCTV_DEBUG"

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
                put("ts", SystemClock.uptimeMillis())
                put("app", app)
                put("runId", runId ?: "")
                put("hypothesisId", hypothesisId)
                put("location", location)
                put("message", message)
                put("data", dataObj)
            }
            val line = obj.toString()
            Log.d(TAG, line)
        } catch (t: Throwable) {
            Log.w(TAG, "DebugLog failed", t)
        }
    }
}
