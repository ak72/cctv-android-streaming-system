package com.anurag.cctvprimary

import android.content.Context
import android.util.Log

/**
 * Runtime probing (safe, minimal version):
 *
 * We already have a strong runtime probe signal for Surface input:
 * - Surface mode attempt throws -> we fall back to ByteBuffer and persist the failure.
 *
 * For resolution/FPS/bitrate, the “probe” here means:
 * - choose a *validated common size* (Camera2 intersection) once
 * - choose a stable fixed FPS once
 * - choose a conservative max bitrate (codec cap + perf class + stall penalty)
 *
 * This avoids expensive active probing during service start while still being data-driven.
 */
internal object RuntimeProbe {
    private const val TAG = "CCTV_PROBE"

    fun ensureProfile(context: Context, wantFrontCamera: Boolean): DeviceProfile? {
        val p = DeviceProfileManager.getOrCreate(context, wantFrontCamera)
        if (p == null) {
            Log.w(TAG, "DeviceProfile unavailable; falling back to legacy defaults")
        }
        return p
    }
}

