package com.anurag.cctvprimary

import android.content.Context
import androidx.core.content.edit

/**
 * Lightweight settings store for user-configurable options that must be readable synchronously
 * from a foreground service.
 *
 * (DataStore would be ideal long-term, but SharedPreferences keeps service startup deterministic.)
 */
object AppSettings {
    private const val PREFS = "cctv_primary_settings"

    private const val KEY_PORT = "port"
    private const val KEY_PASSWORD = "access_password"
    private const val KEY_TALKBACK_ENABLED = "talkback_enabled"
    private const val KEY_FORCE_BUFFER_MODE = "force_buffer_mode"

    private const val KEY_VIDEO_RELATIVE_PATH = "video_relative_path"
    private const val KEY_VIDEO_TREE_URI = "video_tree_uri"
    private const val KEY_STORAGE_LIMIT_BYTES = "storage_limit_bytes"
    private const val KEY_FILE_ROTATION_ENABLED = "file_rotation_enabled"
    // Advanced diagnostics / probing (OFF by default for stability).
    private const val KEY_ENABLE_ACTIVE_PROBE_VIDEO_CAPTURE_COMBO = "enable_active_probe_videocapture_combo"

    const val DEFAULT_PORT: Int = 9090
    const val DEFAULT_PASSWORD: String = "123456"
    const val DEFAULT_VIDEO_RELATIVE_PATH: String = "Movies/CCTV"
    // Default: 600 MB (as per planned feature doc).
    const val DEFAULT_STORAGE_LIMIT_BYTES: Long = 600L * 1024 * 1024
    const val DEFAULT_FILE_ROTATION_ENABLED: Boolean = true
    // Default to ON so two-way audio works out of the box.
    // Users can disable it in settings if they don't want Viewer->Primary audio playback.
    const val DEFAULT_TALKBACK_ENABLED: Boolean = true
    const val DEFAULT_FORCE_BUFFER_MODE: Boolean = false  // Default: OFF (try Surface input first, fallback automatically)
    const val DEFAULT_ENABLE_ACTIVE_PROBE_VIDEO_CAPTURE_COMBO: Boolean = false

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getPort(context: Context): Int =
        prefs(context).getInt(KEY_PORT, DEFAULT_PORT).coerceIn(1, 65535)

    fun setPort(context: Context, port: Int) {
        prefs(context).edit { putInt(KEY_PORT, port.coerceIn(1, 65535)) }
    }

    fun getPassword(context: Context): String =
        prefs(context).getString(KEY_PASSWORD, DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD

    fun setPassword(context: Context, password: String) {
        prefs(context).edit { putString(KEY_PASSWORD, password.ifBlank { DEFAULT_PASSWORD }) }
    }

    fun isTalkbackEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TALKBACK_ENABLED, DEFAULT_TALKBACK_ENABLED)

    fun setTalkbackEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_TALKBACK_ENABLED, enabled) }
    }

    fun isForceBufferMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FORCE_BUFFER_MODE, DEFAULT_FORCE_BUFFER_MODE)

    fun setForceBufferMode(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_FORCE_BUFFER_MODE, enabled) }
    }

    fun getVideoRelativePath(context: Context): String =
        prefs(context).getString(KEY_VIDEO_RELATIVE_PATH, DEFAULT_VIDEO_RELATIVE_PATH)
            ?.trim()
            ?.ifBlank { DEFAULT_VIDEO_RELATIVE_PATH }
            ?: DEFAULT_VIDEO_RELATIVE_PATH

    fun setVideoRelativePath(context: Context, relativePath: String) {
        prefs(context).edit {
            putString(KEY_VIDEO_RELATIVE_PATH, relativePath.trim().ifBlank { DEFAULT_VIDEO_RELATIVE_PATH })
        }
    }

    fun getVideoTreeUri(context: Context): String? =
        prefs(context).getString(KEY_VIDEO_TREE_URI, null)?.trim()?.ifBlank { null }

    fun setVideoTreeUri(context: Context, uriString: String?) {
        prefs(context).edit {
            if (uriString.isNullOrBlank()) remove(KEY_VIDEO_TREE_URI) else putString(KEY_VIDEO_TREE_URI, uriString)
        }
    }

    fun getStorageLimitBytes(context: Context): Long =
        prefs(context).getLong(KEY_STORAGE_LIMIT_BYTES, DEFAULT_STORAGE_LIMIT_BYTES)
            .coerceAtLeast(5L * 1024 * 1024) // minimum 5MB

    fun setStorageLimitBytes(context: Context, bytes: Long) {
        prefs(context).edit { putLong(KEY_STORAGE_LIMIT_BYTES, bytes.coerceAtLeast(5L * 1024 * 1024)) }
    }

    fun isFileRotationEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FILE_ROTATION_ENABLED, DEFAULT_FILE_ROTATION_ENABLED)

    fun setFileRotationEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_FILE_ROTATION_ENABLED, enabled) }
    }

    /**
     * When enabled, the Primary will run an extra one-time “full combo” active probe that binds:
     * - encoder Surface (Preview use-case)
     * - ImageAnalysis
     * - VideoCapture
     *
     * This is heavier and can stress some OEM camera HALs; keep OFF unless explicitly needed.
     */
    fun isActiveProbeVideoCaptureComboEnabled(context: Context): Boolean =
        prefs(context).getBoolean(
            KEY_ENABLE_ACTIVE_PROBE_VIDEO_CAPTURE_COMBO,
            DEFAULT_ENABLE_ACTIVE_PROBE_VIDEO_CAPTURE_COMBO
        )

    fun setActiveProbeVideoCaptureComboEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLE_ACTIVE_PROBE_VIDEO_CAPTURE_COMBO, enabled) }
    }
}

