package com.anurag.cctvprimary

import android.os.Build

/**
 * Legacy registry for vendor/device display only.
 *
 * Pipeline behavior is driven by [CameraHardwareLevelPolicy] (hardware level + capabilities), not brand.
 * This quirk is only used for UI "device forces buffer" display; encoder/profile do not call it.
 */
internal object DeviceQuirks {

    /**
     * Used by UI only (e.g. to show "forced by device" on known models).
     * Pipeline uses DeviceProfile.preferBufferMode (from hardware level), not this.
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

