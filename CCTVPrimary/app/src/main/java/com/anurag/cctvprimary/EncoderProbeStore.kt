package com.anurag.cctvprimary

import android.content.Context
import android.os.Build

/**
 * Persists “runtime probe” outcomes so we don’t repeat expensive/unstable experiments on every launch.
 *
 * This is intentionally simple (SharedPreferences + fingerprint) to keep service startup deterministic.
 */
internal object EncoderProbeStore {
    private const val PREFS = "cctv_primary_device_profile"
    private const val KEY_SURFACE_FAILED_PREFIX = "avc_surface_failed_"

    private fun key(): String {
        // Fingerprint changes across OTAs; that’s good: re-probe after firmware updates.
        val fp = Build.FINGERPRINT ?: "unknown"
        return KEY_SURFACE_FAILED_PREFIX + fp
    }

    fun wasSurfaceInputMarkedBad(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(key(), false)
    }

    fun markSurfaceInputBad(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key(), true)
            .apply()
    }
}

