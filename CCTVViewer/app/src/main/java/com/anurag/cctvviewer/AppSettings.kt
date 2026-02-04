package com.anurag.cctvviewer

import android.content.Context
import androidx.core.content.edit

object AppSettings {
    private const val PREFS = "cctv_viewer"

    private const val KEY_PORT = "port"
    private const val KEY_SHOW_TIMESTAMP = "show_timestamp"
    private const val KEY_PASSWORD = "password"
    private const val KEY_IMAGE_TREE_URI = "image_tree_uri"
    private const val KEY_USE_SURFACE_VIEW = "use_surface_view"

    const val DEFAULT_PORT: Int = 9090
    const val DEFAULT_PASSWORD: String = "123456"
    // Default image folder when SAF is not selected (MediaStore RELATIVE_PATH / legacy fallback).
    const val DEFAULT_IMAGE_RELATIVE_PATH: String = "DCIM/Images/CCTV"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getPort(context: Context): Int =
        prefs(context).getInt(KEY_PORT, DEFAULT_PORT).coerceIn(1, 65535)

    fun setPort(context: Context, port: Int) {
        prefs(context).edit { putInt(KEY_PORT, port.coerceIn(1, 65535)) }
    }

    fun isTimestampEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_TIMESTAMP, false)

    fun setTimestampEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_SHOW_TIMESTAMP, enabled) }
    }

    fun getPassword(context: Context): String =
        prefs(context).getString(KEY_PASSWORD, DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD

    fun setPassword(context: Context, password: String) {
        prefs(context).edit { putString(KEY_PASSWORD, password.ifBlank { DEFAULT_PASSWORD }) }
    }

    fun getImageTreeUri(context: Context): String? =
        prefs(context).getString(KEY_IMAGE_TREE_URI, null)?.trim()?.ifBlank { null }

    fun setImageTreeUri(context: Context, uriString: String?) {
        prefs(context).edit {
            if (uriString.isNullOrBlank()) remove(KEY_IMAGE_TREE_URI) else putString(KEY_IMAGE_TREE_URI, uriString)
        }
    }

    fun isUseSurfaceViewEnabled(context: Context, defaultValue: Boolean): Boolean =
        prefs(context).getBoolean(KEY_USE_SURFACE_VIEW, defaultValue)

    fun setUseSurfaceViewEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_USE_SURFACE_VIEW, enabled) }
    }
}

