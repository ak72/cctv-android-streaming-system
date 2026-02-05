package com.anurag.cctvprimary

import android.content.Context
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size

internal object DeviceProfileManager {
    private const val TAG = "CCTV_DEVICE_PROFILE"
    private const val VERSION = 4

    /**
     * Builds (or loads) a DeviceProfile for the currently selected camera.
     *
     * This is intentionally synchronous and lightweight:
     * - only reads CameraCharacteristics + StreamConfigurationMap
     * - only reads codec capabilities (MediaCodecList)
     */
    fun getOrCreate(context: Context, wantFrontCamera: Boolean): DeviceProfile? {
        val cameraId = Camera2Capabilities.findCameraId(context, wantFrontCamera) ?: return null
        val caps = Camera2Capabilities.read(context, cameraId) ?: return null
        Camera2Capabilities.logSummary(caps)

        DeviceProfileStore.load(context, caps.cameraId, caps.lensFacing)?.let { existing ->
            // Ensure we only reuse when fingerprint matches.
            if (existing.fingerprint == (Build.FINGERPRINT ?: "unknown") && existing.version == VERSION) {
                return existing
            }
        }

        val encCaps = CodecCapabilities.readAvcEncoderCaps()
        val cachedSurfaceBad = EncoderProbeStore.wasSurfaceInputMarkedBad(context)
        val preferBuffer = CameraHardwareLevelPolicy.preferBufferFromHardwareLevel(caps.hardwareLevel) ||
            !encCaps.hasSurfaceInput || cachedSurfaceBad
        val allowFpsGovernor = CameraHardwareLevelPolicy.allowFpsGovernor(caps.hardwareLevel)
        val allowDynamicBitrate = CameraHardwareLevelPolicy.allowDynamicBitrate(caps.hardwareLevel)

        // Choose fixed FPS ceiling:
        // Prefer 30, then 24, then 15; else fall back to highest fixed.
        val chosenFixed = chooseFixedFps(caps.fixedFps)
        val chosenRange = chooseBestRange(caps.allFpsRanges, chosenFixed)

        // Surface combination validation (practical version for our pipeline):
        // We require a 3:4 portrait size that is supported by:
        // - SurfaceTexture (Preview + encoder Surface use case)
        // - YUV_420_888 (ImageAnalysis for ByteBuffer + CustomRecorder)
        // - MediaRecorder sizes (best-effort; if empty, ignore)
        val candidates = run {
            val base = arrayListOf<List<Size>>(caps.previewSizes, caps.yuvSizes)
            if (caps.recorderSizes.isNotEmpty()) base.add(caps.recorderSizes)
            SizeSelector.intersect4by3Portrait(*base.toTypedArray())
        }
        val relaxedCandidates = if (candidates.isNotEmpty()) candidates else SizeSelector.intersect4by3Portrait(caps.previewSizes, caps.yuvSizes)
        val size1080 = SizeSelector.pickBestAtOrBelow(relaxedCandidates, 1080, 1440)
        val size720 = SizeSelector.pickBestAtOrBelow(relaxedCandidates, 720, 960)
        val size480 = SizeSelector.pickBestAtOrBelow(relaxedCandidates, 480, 640)

        val chosenSize = when {
            preferBuffer -> (size720 ?: size480 ?: size1080 ?: Size(720, 960))
            else -> (size1080 ?: size720 ?: size480 ?: Size(720, 960))
        }

        // Stall-aware bitrate cap (very conservative):
        // If the selected YUV size has a non-zero output stall duration, keep bitrate modest.
        val stallMs = Camera2Capabilities.outputStallMs(
            caps.outputMap,
            android.graphics.ImageFormat.YUV_420_888,
            chosenSize
        )
        val perfClass = if (Build.VERSION.SDK_INT >= 31) Build.VERSION.MEDIA_PERFORMANCE_CLASS else 0
        val encoderMax = encCaps.bitrateRangeBps?.last
        val maxRecommendedBitrate = computeMaxRecommendedBitrate(perfClass, stallMs, encoderMax)

        val ladder = buildLadder(
            preferBuffer = preferBuffer,
            size1080 = size1080,
            size720 = size720,
            size480 = size480,
            baseFps = chosenFixed,
            maxBr = maxRecommendedBitrate
        )

        val profile = DeviceProfile(
            version = VERSION,
            fingerprint = Build.FINGERPRINT ?: "unknown",
            cameraId = caps.cameraId,
            lensFacing = caps.lensFacing,
            hardwareLevel = caps.hardwareLevel,
            activeArraySize = caps.activeArray?.let { "${it.left},${it.top},${it.right},${it.bottom}" },
            availableFixedFps = caps.fixedFps,
            chosenFixedFps = chosenFixed,
            chosenAeFpsRange = chosenRange,
            supportsConcurrentCameras = Camera2Capabilities.supportsConcurrentCameras(context),
            common4by3Size = chosenSize,
            common4by3Candidates = relaxedCandidates.take(12), // keep small
            hasAvcSurfaceInput = encCaps.hasSurfaceInput,
            encoderBitrateRangeBps = encCaps.bitrateRangeBps,
            preferBufferMode = preferBuffer,
            allowFpsGovernor = allowFpsGovernor,
            allowDynamicBitrate = allowDynamicBitrate,
            maxRecommendedBitrateBps = maxRecommendedBitrate,
            qualityLadder = ladder,
            activeProbeSelectedConfig = null,
            activeProbeSelectedPreferBufferMode = null,
            activeProbeVerifiedVideoCaptureCombo = null
        )

        DeviceProfileStore.save(context, profile)
        Log.w(
            TAG,
            "Built DeviceProfile: cameraId=${profile.cameraId} facing=${profile.lensFacing} hw=${profile.hardwareLevel} " +
                "size=${profile.common4by3Size.width}x${profile.common4by3Size.height} fps=${profile.chosenFixedFps} " +
                "preferBuffer=${profile.preferBufferMode} maxBr=${profile.maxRecommendedBitrateBps} stallMs=$stallMs perfClass=$perfClass"
        )
        return profile
    }

    private fun chooseFixedFps(fixed: List<Int>): Int {
        if (fixed.contains(30)) return 30
        if (fixed.contains(24)) return 24
        if (fixed.contains(15)) return 15
        return fixed.firstOrNull() ?: 30
    }

    private fun chooseBestRange(ranges: List<Range<Int>>, target: Int): Range<Int>? {
        // Prefer fixed exactly.
        ranges.firstOrNull { it.lower == target && it.upper == target }?.let { return it }
        // else pick smallest range that contains target.
        val containing = ranges.filter { it.lower <= target && it.upper >= target }
        if (containing.isNotEmpty()) {
            return containing.minByOrNull { (it.upper - it.lower) }
        }
        return null
    }

    private fun computeMaxRecommendedBitrate(perfClass: Int, stallMs: Long, encoderMax: Int?): Int {
        // Conservative heuristic:
        // - perf class >= 31: allow higher bitrates
        // - if stall is large, keep lower to reduce pressure
        val base = when {
            perfClass >= 31 -> 6_000_000
            else -> 4_000_000
        }
        val stallPenalty = when {
            stallMs < 0 -> 0
            stallMs >= 200 -> 2_000_000
            stallMs >= 100 -> 1_000_000
            else -> 0
        }
        val capped = (base - stallPenalty).coerceAtLeast(2_000_000)
        return if (encoderMax != null) capped.coerceAtMost(encoderMax) else capped
    }

    private fun buildLadder(
        preferBuffer: Boolean,
        size1080: Size?,
        size720: Size?,
        size480: Size?,
        baseFps: Int,
        maxBr: Int
    ): List<StreamConfig> {
        // Runtime probing ladder (descending quality), mapped to *validated* sizes.
        val fps30orLess = minOf(baseFps, 30)
        val fps24orLess = minOf(baseFps, 24)
        val fps15orLess = minOf(baseFps, 15)

        // Bitrate plan (caps-aware, conservative).
        val brHigh = maxBr
        val brMed = (maxBr * 0.7).toInt().coerceAtLeast(2_000_000)
        val brLow = (maxBr * 0.5).toInt().coerceAtLeast(1_200_000)

        val out = ArrayList<StreamConfig>(4)

        // For buffer-preferred devices, start at 720 (or 480) to avoid stressing the pipeline.
        if (!preferBuffer) {
            size1080?.let { out.add(StreamConfig(it.width, it.height, brHigh, fps30orLess)) }
        }
        (size720 ?: size1080)?.let { out.add(StreamConfig(it.width, it.height, brMed, fps30orLess)) }
        (size720 ?: size480 ?: size1080)?.let { out.add(StreamConfig(it.width, it.height, brMed, fps24orLess)) }
        (size480 ?: size720 ?: size1080)?.let { out.add(StreamConfig(it.width, it.height, brLow, fps15orLess)) }

        // De-dupe (same w/h/fps/br repeats).
        return out.distinct()
    }
}

