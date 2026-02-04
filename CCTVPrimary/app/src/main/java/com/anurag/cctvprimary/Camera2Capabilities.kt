package com.anurag.cctvprimary

import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size
import android.graphics.SurfaceTexture
import android.graphics.ImageFormat

internal data class Camera2Caps(
    val cameraId: String,
    val lensFacing: Int,
    val hardwareLevel: Int,
    val activeArray: Rect?,
    val fixedFps: List<Int>,
    val allFpsRanges: List<Range<Int>>,
    val previewSizes: List<Size>,   // SurfaceTexture
    val yuvSizes: List<Size>,       // YUV_420_888
    val recorderSizes: List<Size>,  // MediaRecorder (best-effort)
    val outputMap: StreamConfigurationMap?
)

internal object Camera2Capabilities {
    private const val TAG = "CAMERA2CAP"

    fun findCameraId(context: Context, wantFront: Boolean): String? {
        val mgr = context.getSystemService(CameraManager::class.java) ?: return null
        val wanted = if (wantFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        return mgr.cameraIdList.firstOrNull { id ->
            val chars = runCatching { mgr.getCameraCharacteristics(id) }.getOrNull() ?: return@firstOrNull false
            val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: return@firstOrNull false
            facing == wanted
        }
    }

    fun read(context: Context, cameraId: String): Camera2Caps? {
        val mgr = context.getSystemService(CameraManager::class.java) ?: return null
        val chars = runCatching { mgr.getCameraCharacteristics(cameraId) }.getOrNull() ?: return null

        val lensFacing = chars.get(CameraCharacteristics.LENS_FACING) ?: -1
        val hw = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1
        val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        val fpsRangesArr = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        val fpsRanges = (fpsRangesArr ?: emptyArray()).toList()
        val fixed = fpsRanges.filter { it.lower == it.upper }.map { it.upper }.distinct().sortedDescending()

        fun sizesForSurfaceTexture(): List<Size> {
            val out = map?.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()
            return out.sortedByDescending { it.width.toLong() * it.height.toLong() }
        }

        fun sizesForYuv(): List<Size> {
            val out = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList() ?: emptyList()
            return out.sortedByDescending { it.width.toLong() * it.height.toLong() }
        }

        fun sizesForRecorder(): List<Size> {
            val out = try {
                // Some devices only provide sizes via MediaRecorder class query.
                map?.getOutputSizes(MediaRecorder::class.java)?.toList()
            } catch (_: Throwable) {
                null
            } ?: emptyList()
            return out.sortedByDescending { it.width.toLong() * it.height.toLong() }
        }

        return Camera2Caps(
            cameraId = cameraId,
            lensFacing = lensFacing,
            hardwareLevel = hw,
            activeArray = activeArray,
            fixedFps = fixed,
            allFpsRanges = fpsRanges,
            previewSizes = sizesForSurfaceTexture(),
            yuvSizes = sizesForYuv(),
            recorderSizes = sizesForRecorder(),
            outputMap = map
        )
    }

    fun supportsConcurrentCameras(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val mgr = context.getSystemService(CameraManager::class.java) ?: return false
        return runCatching {
            mgr.concurrentCameraIds.isNotEmpty()
        }.getOrDefault(false)
    }

    fun outputStallMs(map: StreamConfigurationMap?, format: Int, size: Size): Long {
        if (map == null) return -1L
        return try {
            // Returns duration in nanoseconds.
            val ns = map.getOutputStallDuration(format, size)
            if (ns <= 0L) 0L else ns / 1_000_000L
        } catch (_: Throwable) {
            -1L
        }
    }

    fun logSummary(caps: Camera2Caps) {
        try {
            Log.d(
                TAG,
                "Camera2Caps cameraId=${caps.cameraId} facing=${caps.lensFacing} hw=${caps.hardwareLevel} fixedFps=${caps.fixedFps} " +
                    "previewSizes=${caps.previewSizes.take(3)} yuvSizes=${caps.yuvSizes.take(3)} recorderSizes=${caps.recorderSizes.take(3)}"
            )
        } catch (_: Throwable) {
        }
    }
}

