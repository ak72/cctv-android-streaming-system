package com.anurag.cctvprimary

import android.hardware.camera2.CameraCharacteristics

/**
 * Branch behavior on Camera2 hardware level instead of brand/model checks.
 * Scale across devices automatically; no "if Samsung -> hack".
 *
 * Uses INFO_SUPPORTED_HARDWARE_LEVEL: LEGACY, LIMITED, FULL, LEVEL_3 (and EXTERNAL).
 */
internal object CameraHardwareLevelPolicy {

    /** FPS cap for LIMITED/LEGACY (e.g. 24) for lower pipeline stress and brighter exposure. */
    const val FPS_CAP_LOW_TIER: Int = 24

    /**
     * Prefer buffer (ByteBuffer) input over Surface for encoder when hardware is LEGACY or LIMITED.
     * Reduces pipeline stress and HAL issues on lower-tier devices.
     */
    fun preferBufferFromHardwareLevel(hardwareLevel: Int): Boolean =
        hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY ||
            hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED

    /**
     * Allow FPS governor (dynamic FPS tuning) only on FULL or LEVEL_3.
     * On LEGACY/LIMITED use a fixed cap (e.g. 24) instead.
     */
    fun allowFpsGovernor(hardwareLevel: Int): Boolean =
        hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL ||
            hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3

    /**
     * Allow dynamic bitrate recovery (ramp-up after backpressure). Disable on LEGACY to avoid instability.
     */
    fun allowDynamicBitrate(hardwareLevel: Int): Boolean =
        hardwareLevel != CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
}
