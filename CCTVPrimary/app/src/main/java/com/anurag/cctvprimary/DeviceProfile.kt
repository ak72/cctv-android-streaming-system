package com.anurag.cctvprimary

import android.util.Range
import android.util.Size

/**
 * Persisted, capability-driven snapshot of what this device/camera can do reliably.
 *
 * Key ideas (per your suggestion):
 * - Prefer capability detection (Camera2 + codec) over model hardcoding.
 * - Persist results keyed by firmware fingerprint so we don’t re-probe every launch.
 */
internal data class DeviceProfile(
    val version: Int,
    val fingerprint: String,
    val cameraId: String,
    val lensFacing: Int, // CameraCharacteristics.LENS_FACING_*

    // ---- Camera2 capability signals ----
    val hardwareLevel: Int, // CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_*
    val activeArraySize: String?, // "l,t,r,b" (stringified to keep it lightweight)
    val availableFixedFps: List<Int>, // fixed fps values (e.g. [30,24,15])
    val chosenFixedFps: Int,
    val chosenAeFpsRange: Range<Int>?, // best-effort chosen range
    val supportsConcurrentCameras: Boolean,

    // ---- Output sizes (intersection-validated) ----
    val common4by3Size: Size, // chosen size that is supported across relevant targets
    val common4by3Candidates: List<Size>, // ordered best->worst (debug/persisted)

    // ---- Encoder capability signals ----
    val hasAvcSurfaceInput: Boolean,
    val encoderBitrateRangeBps: IntRange?, // best-effort

    // ---- Policy outcomes ----
    val preferBufferMode: Boolean, // should skip Surface attempt for stability
    val maxRecommendedBitrateBps: Int,
    val qualityLadder: List<StreamConfig>, // descending quality choices already mapped to supported sizes

    // ---- Active runtime probe results (persisted) ----
    // If present, this is the empirically verified “best starting rung” for this firmware + camera.
    val activeProbeSelectedConfig: StreamConfig? = null,
    // Whether the empirically verified pipeline required buffer mode (or avoided surface mode).
    val activeProbeSelectedPreferBufferMode: Boolean? = null,
    // Whether we verified the heavier “VideoCapture combo” binding path in active probe.
    val activeProbeVerifiedVideoCaptureCombo: Boolean? = null
)

