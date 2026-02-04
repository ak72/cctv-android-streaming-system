package com.anurag.cctvprimary

import android.content.Context
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class ProbeOutcome(
    val selectedConfig: StreamConfig,
    val preferBufferMode: Boolean,
    val notes: String
)

/**
 * Actively probes the real camera->encoder pipeline.
 *
 * Design goals:
 * - Minimal churn: few binds, short timeouts, and always unbind/stop.
 * - Deterministic: probes a small quality ladder in descending order.
 * - Safe: never probes while recording.
 *
 * Success condition (for both modes):
 * - receive at least 1 encoded output frame within timeout.
 */
internal object ActivePipelineProber {
    private const val TAG = "CCTV_ACTIVE_PROBE"

    fun probe(
        context: Context,
        provider: ProcessCameraProvider,
        owner: LifecycleOwner,
        wantFrontCamera: Boolean,
        candidates: List<StreamConfig>,
        displayRotation: Int,
        verifyVideoCaptureCombo: Boolean,
        maxAttempts: Int = 3,
        perAttemptTimeoutMs: Long = 2500L
    ): ProbeOutcome? {
        if (candidates.isEmpty()) return null

        val selector = if (wantFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val analysisExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "CCTV-Probe-Analysis").apply { isDaemon = true }
        }

        fun cleanUp(encoder: VideoEncoder?) {
            try {
                provider.unbindAll()
            } catch (_: Throwable) {
            }
            try {
                encoder?.stop()
            } catch (_: Throwable) {
            }
            try {
                analysisExecutor.shutdownNow()
            } catch (_: Throwable) {
            }
        }

        try {
            val preferBufferByPolicy = VideoEncoder.shouldPreferBufferMode(context, AppSettings.isForceBufferMode(context))

            // Try Surface pipeline first (if policy allows).
            if (!preferBufferByPolicy) {
                for (cfg in candidates.take(maxAttempts)) {
                    val ok = if (verifyVideoCaptureCombo) {
                        trySurfaceComboOnce(
                            context = context,
                            provider = provider,
                            owner = owner,
                            selector = selector,
                            cfg = cfg,
                            displayRotation = displayRotation,
                            timeoutMs = perAttemptTimeoutMs,
                            mainExecutor = mainExecutor
                        )
                    } else {
                        trySurfaceOnce(
                            context = context,
                            provider = provider,
                            owner = owner,
                            selector = selector,
                            cfg = cfg,
                            displayRotation = displayRotation,
                            timeoutMs = perAttemptTimeoutMs,
                            mainExecutor = mainExecutor
                        )
                    }
                    if (ok != null) {
                        return ok
                    }
                }
            }

            // Then try Buffer pipeline (validated size ladder, but encoder will be ByteBuffer mode).
            for (cfg in candidates.take(maxAttempts)) {
                val ok = if (verifyVideoCaptureCombo) {
                    tryBufferComboOnce(
                        context = context,
                        provider = provider,
                        owner = owner,
                        selector = selector,
                        cfg = cfg,
                        displayRotation = displayRotation,
                        timeoutMs = perAttemptTimeoutMs,
                        analysisExecutor = analysisExecutor
                    )
                } else {
                    tryBufferOnce(
                        context = context,
                        provider = provider,
                        owner = owner,
                        selector = selector,
                        cfg = cfg,
                        displayRotation = displayRotation,
                        timeoutMs = perAttemptTimeoutMs,
                        analysisExecutor = analysisExecutor
                    )
                }
                if (ok != null) {
                    return ok
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Active probe failed (non-fatal)", t)
        } finally {
            cleanUp(null)
        }

        return null
    }

    private fun selectorFor(cfg: StreamConfig): ResolutionSelector {
        // Service uses Size(height,width) for portrait targets; mirror that.
        val target = Size(cfg.height, cfg.width)
        return ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                AspectRatioStrategy(
                    androidx.camera.core.AspectRatio.RATIO_4_3,
                    AspectRatioStrategy.FALLBACK_RULE_AUTO
                )
            )
            .setResolutionStrategy(
                ResolutionStrategy(
                    target,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                )
            )
            .build()
    }

    private fun trySurfaceOnce(
        context: Context,
        provider: ProcessCameraProvider,
        owner: LifecycleOwner,
        selector: CameraSelector,
        cfg: StreamConfig,
        displayRotation: Int,
        timeoutMs: Long,
        mainExecutor: java.util.concurrent.Executor
    ): ProbeOutcome? {
        var encoder: VideoEncoder? = null
        try {
            provider.unbindAll()

            val latch = CountDownLatch(1)
            encoder = VideoEncoder(
                width = cfg.width,
                height = cfg.height,
                bitrate = cfg.bitrate,
                frameRate = cfg.fps,
                iFrameInterval = 1,
                context = context,
                forceBufferMode = false
            ) { _, _, _ -> }
            encoder.setEncodedFrameListener(object : EncodedFrameListener {
                override fun onEncodedFrame(frame: EncodedFrame) {
                    // Any output frame is enough to prove pipeline liveness.
                    latch.countDown()
                }
            })
            encoder.start()

            if (!encoder.useSurfaceInput) {
                // It chose Buffer mode (policy/cached) — not a surface success.
                return null
            }
            val surface = encoder.getInputSurface() ?: return null

            val selectorCfg = selectorFor(cfg)
            val preview = Preview.Builder()
                .setResolutionSelector(selectorCfg)
                .setTargetRotation(displayRotation)
                .build()
                .apply {
                    setSurfaceProvider { request ->
                        // Provide encoder surface (zero-copy).
                        request.provideSurface(surface, mainExecutor) { }
                    }
                }

            provider.bindToLifecycle(owner, selector, preview)

            val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (ok) {
                Log.w(TAG, "✅ Surface probe OK: ${cfg.width}x${cfg.height}@${cfg.fps} br=${cfg.bitrate}")
                return ProbeOutcome(cfg, preferBufferMode = false, notes = "surface_ok")
            }

            Log.w(TAG, "❌ Surface probe timeout: ${cfg.width}x${cfg.height}@${cfg.fps} br=${cfg.bitrate}")
            return null
        } catch (t: Throwable) {
            Log.w(TAG, "❌ Surface probe failed: ${cfg.width}x${cfg.height}@${cfg.fps} br=${cfg.bitrate}", t)
            return null
        } finally {
            try {
                provider.unbindAll()
            } catch (_: Throwable) {
            }
            try {
                encoder?.stop()
            } catch (_: Throwable) {
            }
        }
    }

    private fun tryBufferOnce(
        context: Context,
        provider: ProcessCameraProvider,
        owner: LifecycleOwner,
        selector: CameraSelector,
        cfg: StreamConfig,
        displayRotation: Int,
        timeoutMs: Long,
        analysisExecutor: java.util.concurrent.ExecutorService
    ): ProbeOutcome? {
        var encoder: VideoEncoder? = null
        try {
            provider.unbindAll()

            val latch = CountDownLatch(1)

            // In ByteBuffer mode, we must respect the app’s established 4:3 fixed encoder sizes.
            val portrait = cfg.width < cfg.height
            val encW = if (portrait) 720 else 960
            val encH = if (portrait) 960 else 720

            encoder = VideoEncoder(
                width = encW,
                height = encH,
                bitrate = cfg.bitrate,
                frameRate = cfg.fps,
                iFrameInterval = 1,
                context = context,
                forceBufferMode = true
            ) { _, _, _ -> }
            encoder.setEncodedFrameListener(object : EncodedFrameListener {
                override fun onEncodedFrame(frame: EncodedFrame) {
                    latch.countDown()
                }
            })
            encoder.start()

            val selectorCfg = selectorFor(StreamConfig(encW, encH, cfg.bitrate, cfg.fps))
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(selectorCfg)
                .setTargetRotation(displayRotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
            analysis.setAnalyzer(analysisExecutor) { image ->
                try {
                    encoder.encode(image, closeImage = true)
                } catch (_: Throwable) {
                    try {
                        image.close()
                    } catch (_: Throwable) {
                    }
                }
            }

            provider.bindToLifecycle(owner, selector, analysis)

            val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (ok) {
                val selected = StreamConfig(encW, encH, cfg.bitrate, cfg.fps)
                Log.w(TAG, "✅ Buffer probe OK: ${selected.width}x${selected.height}@${selected.fps} br=${selected.bitrate}")
                return ProbeOutcome(selected, preferBufferMode = true, notes = "buffer_ok")
            }

            Log.w(TAG, "❌ Buffer probe timeout: ${encW}x${encH}@${cfg.fps} br=${cfg.bitrate}")
            return null
        } catch (t: Throwable) {
            Log.w(TAG, "❌ Buffer probe failed: ${cfg.width}x${cfg.height}@${cfg.fps} br=${cfg.bitrate}", t)
            return null
        } finally {
            try {
                provider.unbindAll()
            } catch (_: Throwable) {
            }
            try {
                encoder?.stop()
            } catch (_: Throwable) {
            }
        }
    }

    private fun createVideoCapture(): VideoCapture<Recorder> {
        val recorder = Recorder.Builder().setQualitySelector(
            QualitySelector.fromOrderedList(
                listOf(Quality.SD, Quality.HD, Quality.FHD),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
            )
        ).build()
        return VideoCapture.withOutput(recorder)
    }

    private fun buildViewport(displayRotation: Int): ViewPort {
        return ViewPort.Builder(Rational(3, 4), displayRotation)
            .setScaleType(ViewPort.FIT)
            .build()
    }

    private fun trySurfaceComboOnce(
        context: Context,
        provider: ProcessCameraProvider,
        owner: LifecycleOwner,
        selector: CameraSelector,
        cfg: StreamConfig,
        displayRotation: Int,
        timeoutMs: Long,
        mainExecutor: java.util.concurrent.Executor
    ): ProbeOutcome? {
        var encoder: VideoEncoder? = null
        try {
            provider.unbindAll()

            val latch = CountDownLatch(1)
            encoder = VideoEncoder(
                width = cfg.width,
                height = cfg.height,
                bitrate = cfg.bitrate,
                frameRate = cfg.fps,
                iFrameInterval = 1,
                context = context,
                forceBufferMode = false
            ) { _, _, _ -> }
            encoder.setEncodedFrameListener(object : EncodedFrameListener {
                override fun onEncodedFrame(frame: EncodedFrame) {
                    latch.countDown()
                }
            })
            encoder.start()

            if (!encoder.useSurfaceInput) return null
            val surface: Surface = encoder.getInputSurface() ?: return null

            val selectorCfg = selectorFor(cfg)
            val encoderPreview = Preview.Builder()
                .setResolutionSelector(selectorCfg)
                .setTargetRotation(displayRotation)
                .build()
                .apply {
                    setSurfaceProvider { request ->
                        request.provideSurface(surface, mainExecutor) { }
                    }
                }

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(selectorCfg)
                .setTargetRotation(displayRotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply {
                    val drainExec = Executors.newSingleThreadExecutor { r ->
                        Thread(r, "CCTV-Probe-Drain").apply { isDaemon = true }
                    }
                    setAnalyzer(drainExec) { image ->
                        try {
                            image.close()
                        } catch (_: Throwable) {
                        }
                    }
                }

            val videoCapture = createVideoCapture()

            val group = UseCaseGroup.Builder()
                .setViewPort(buildViewport(displayRotation))
                .addUseCase(encoderPreview)
                .addUseCase(analysis)
                .addUseCase(videoCapture)
                .build()

            provider.bindToLifecycle(owner, selector, group)

            val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (ok) {
                Log.w(TAG, "✅ Surface+Analysis+VideoCapture probe OK: ${cfg.width}x${cfg.height}@${cfg.fps} br=${cfg.bitrate}")
                return ProbeOutcome(cfg, preferBufferMode = false, notes = "surface_combo_ok")
            }
            Log.w(TAG, "❌ Surface+Analysis+VideoCapture probe timeout: ${cfg.width}x${cfg.height}@${cfg.fps} br=${cfg.bitrate}")
            return null
        } catch (t: Throwable) {
            Log.w(TAG, "❌ Surface combo probe failed: ${cfg.width}x${cfg.height}@${cfg.fps} br=${cfg.bitrate}", t)
            return null
        } finally {
            try {
                provider.unbindAll()
            } catch (_: Throwable) {
            }
            try {
                encoder?.stop()
            } catch (_: Throwable) {
            }
        }
    }

    private fun tryBufferComboOnce(
        context: Context,
        provider: ProcessCameraProvider,
        owner: LifecycleOwner,
        selector: CameraSelector,
        cfg: StreamConfig,
        displayRotation: Int,
        timeoutMs: Long,
        analysisExecutor: java.util.concurrent.ExecutorService
    ): ProbeOutcome? {
        var encoder: VideoEncoder? = null
        try {
            provider.unbindAll()

            val latch = CountDownLatch(1)

            val portrait = cfg.width < cfg.height
            val encW = if (portrait) 720 else 960
            val encH = if (portrait) 960 else 720
            val resolved = StreamConfig(encW, encH, cfg.bitrate, cfg.fps)

            encoder = VideoEncoder(
                width = resolved.width,
                height = resolved.height,
                bitrate = resolved.bitrate,
                frameRate = resolved.fps,
                iFrameInterval = 1,
                context = context,
                forceBufferMode = true
            ) { _, _, _ -> }
            encoder.setEncodedFrameListener(object : EncodedFrameListener {
                override fun onEncodedFrame(frame: EncodedFrame) {
                    latch.countDown()
                }
            })
            encoder.start()

            val selectorCfg = selectorFor(resolved)
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(selectorCfg)
                .setTargetRotation(displayRotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
            analysis.setAnalyzer(analysisExecutor) { image ->
                try {
                    encoder.encode(image, closeImage = true)
                } catch (_: Throwable) {
                    try {
                        image.close()
                    } catch (_: Throwable) {
                    }
                }
            }

            val videoCapture = createVideoCapture()

            val group = UseCaseGroup.Builder()
                .setViewPort(buildViewport(displayRotation))
                .addUseCase(analysis)
                .addUseCase(videoCapture)
                .build()

            provider.bindToLifecycle(owner, selector, group)

            val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (ok) {
                Log.w(TAG, "✅ Buffer+VideoCapture combo probe OK: ${resolved.width}x${resolved.height}@${resolved.fps} br=${resolved.bitrate}")
                return ProbeOutcome(resolved, preferBufferMode = true, notes = "buffer_combo_ok")
            }
            Log.w(TAG, "❌ Buffer+VideoCapture combo probe timeout: ${resolved.width}x${resolved.height}@${resolved.fps} br=${resolved.bitrate}")
            return null
        } catch (t: Throwable) {
            Log.w(TAG, "❌ Buffer combo probe failed: ${cfg.width}x${cfg.height}@${cfg.fps} br=${cfg.bitrate}", t)
            return null
        } finally {
            try {
                provider.unbindAll()
            } catch (_: Throwable) {
            }
            try {
                encoder?.stop()
            } catch (_: Throwable) {
            }
        }
    }
}

