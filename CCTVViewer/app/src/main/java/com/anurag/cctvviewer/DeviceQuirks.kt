package com.anurag.cctvviewer

import android.os.Build

/**
 * Central registry for *confirmed* vendor/device bugs on the Viewer side.
 *
 * Policy:
 * - Prefer capability detection and runtime signals.
 * - Keep manufacturer/model checks small and isolated (not scattered across the codebase).
 */
internal object DeviceQuirks {

    /**
     * Some Samsung Exynos devices can decode into vendor color formats that render black on SurfaceView.
     * Force TextureView on these known-bad devices.
     */
    fun forceTextureViewForBlackSurfaceView(): Boolean {
        return try {
            val manu = Build.MANUFACTURER?.lowercase() ?: ""
            val model = Build.MODEL?.lowercase() ?: ""
            manu.contains("samsung") && (
                model.contains("sm-m307") ||
                    model.contains("m30s") ||
                    model.contains("m30") ||
                    model.contains("m31") ||
                    model.contains("m32")
                )
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * OnePlus Nord CE4: occasional green/uninitialized decoder output on first render.
     * Keep this isolated so the rest of the pipeline stays model-agnostic.
     */
    fun needsGreenFrameWarmupMitigation(): Boolean {
        return try {
            val manu = Build.MANUFACTURER?.lowercase() ?: ""
            val model = Build.MODEL?.lowercase() ?: ""
            manu.contains("oneplus") && (model.contains("cph261") || model.contains("nord ce4"))
        } catch (_: Throwable) {
            false
        }
    }
}

