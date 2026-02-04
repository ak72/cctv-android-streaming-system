package com.anurag.cctvprimary

import android.os.Build

/**
 * Central registry for *confirmed* vendor/device bugs.
 *
 * Policy:
 * - Prefer capability detection + runtime probing.
 * - Only keep small, well-justified model/manufacturer checks here (not scattered).
 */
internal object DeviceQuirks {

    /**
     * Some devices are known to behave badly with CameraX -> MediaCodec Surface input pipelines
     * (e.g. multi-surface issues, HAL deadlocks, or silent stalls).
     *
     * Keep this list SMALL and only for confirmed production bugs.
     */
    fun forceBufferInputMode(): Boolean {
        val manu = (Build.MANUFACTURER ?: "").lowercase()
        val model = Build.MODEL ?: ""

        // Historically problematic examples (keep minimal):
        // - Samsung M30s (Exynos) had encoder/profile issues and unstable surface pipelines.
        if (manu.contains("samsung") && (model.contains("SM-M307", ignoreCase = true) || model.contains("M30", ignoreCase = true))) {
            return true
        }

        // - OnePlus Nord CE family: some units have surface pipeline flakiness (confirmed earlier).
        if (manu.contains("oneplus") && model.contains("Nord CE", ignoreCase = true)) {
            return true
        }

        return false
    }
}

