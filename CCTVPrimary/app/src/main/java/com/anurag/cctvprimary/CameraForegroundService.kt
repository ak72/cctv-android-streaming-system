package com.anurag.cctvprimary

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.content.pm.ServiceInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.AspectRatio
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.CameraState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.Observer
import androidx.core.content.edit
import android.os.PowerManager
import android.util.Rational
import android.util.Size
import android.view.Surface
import androidx.documentfile.provider.DocumentFile
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import java.io.File
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import android.media.AudioAttributes
import android.media.AudioFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Range
import androidx.core.net.toUri
import java.util.Locale

/* ------ Service-owned capture state --------------- */
enum class ServiceCaptureState {
    IDLE, PREVIEW, RECORDING
}

@OptIn(ExperimentalCamera2Interop::class)
@Suppress("OPT_IN_ARGUMENT_IS_NOT_MARKER")
class CameraForegroundService : LifecycleService() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "cctv_camera"
        private const val ACTION_STOP_CAPTURE = "com.anurag.cctvprimary.ACTION_STOP_CAPTURE"

        // Encoder bitrate floor used throughout the app (keep consistent with existing clamps).
        private const val MIN_BITRATE_BPS = 300_000
    }

    /* ---------------- CameraX ---------------- */
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var lastSurfaceProvider: Preview.SurfaceProvider? = null
    private var lastViewPort: ViewPort? = null
    private var lastDisplayRotation: Int = Surface.ROTATION_0

    @Volatile
    private var bindToken: Long = 0L

    /* ---------- Encoder ---------- */
    private var videoEncoder: VideoEncoder? = null

    // Capability-driven profile (Camera2 + codec), persisted by firmware fingerprint.
    @Volatile private var deviceProfile: DeviceProfile? = null
    @Volatile private var activeProbeRunning: Boolean = false

    /* ---------- Custom Recorder ---------- */
    private var customRecorder: CustomRecorder? = null

    /* ---------------- Streaming ---------------- */
    private var streamServer: StreamServer? = null

    /* ---------------- Audio Source Engine ---------------- */
    private val audioSourceEngine = AudioSourceEngine.getInstance()
    private var streamAudioSender: StreamAudioSender? = null

    /* -------- Audio Talkback -------- */
    @Volatile
    private var talkbackTrack: android.media.AudioTrack? = null

    // Use 48kHz to match Viewer TalkBack capture rate and avoid resampling overhead
    private val talkbackPlaybackSampleRate = 48000  // Fullband (48kHz) to match incoming audio
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val audioChannelOut = AudioFormat.CHANNEL_OUT_MONO
    private val audioFrameMs = 20
    private val talkbackBytesPerFrame = (talkbackPlaybackSampleRate / 1000) * audioFrameMs * 2

    /* -------- Camera controls state -------- */
    @Volatile
    var currentCamera: androidx.camera.core.Camera? = null

    // Observe camera state to recover from HAL/device errors.
    @Volatile
    private var cameraStateObserver: Observer<CameraState>? = null

    @Volatile
    private var lastCameraErrorRebindUptimeMs: Long = 0L

    @Volatile
    var aeCompValue: Float = 0f

    @Volatile
    var torchEnabled: Boolean = false

    /* ---------- Binder ---------- */
    inner class LocalBinder : android.os.Binder() {
        fun getService(): CameraForegroundService = this@CameraForegroundService
    }

    /* ---------- Power ---------- */
    private lateinit var powerManager: PowerManager

    private val binder = LocalBinder()

    /* ---------- State ---------- */
    private var serviceState = ServiceCaptureState.IDLE

    @Volatile
    private var recordingWithAudio: Boolean = false  // Track if current recording has audio

    // ---- UI State ----
    @Volatile
    private var primaryUiVisible: Boolean = false

    // --- Idle / Low Power Mode ---
    @Volatile
    private var activeViewerSessions: Int = 0

    @Volatile
    private var lowPowerIdleMode: Boolean = false

    @Volatile
    private var lastNonIdleConfig: StreamConfig? = null

    // Some devices cannot bind our Surface-mode "double Preview + ImageAnalysis" combination.
    // If we detect that, force Buffer mode for the remainder of this process lifetime.
    @Volatile
    private var forceBufferModeDueToCameraXCombo: Boolean = false

    private fun isRecordingActive(): Boolean {
        // Include transitional states to avoid entering idle mid stop/start.
        return serviceState == ServiceCaptureState.RECORDING ||
            recordingRequested ||
            customRecorder != null ||
            stopRecordingInProgress
    }

    private fun updateIdleState(reason: String) {
        val idleNow = (activeViewerSessions <= 0) && !isRecordingActive() && !primaryUiVisible
        if (idleNow == lowPowerIdleMode) return

        lowPowerIdleMode = idleNow

        if (idleNow) {
            // Snapshot the current (active) operating point so we can restore it when a viewer connects
            // or recording/UI becomes active again.
            lastNonIdleConfig = StreamConfig(
                width = currentWidth,
                height = currentHeight,
                bitrate = currentBitrate,
                fps = currentFPS
            )

            val portrait = currentWidth < currentHeight
            val lowW = if (portrait) 480 else 640
            val lowH = if (portrait) 640 else 480

            currentWidth = lowW
            currentHeight = lowH
            currentFPS = 15
            // Bitrate is a strong heat/battery driver; keep it modest in idle.
            currentBitrate = currentBitrate.coerceAtMost(900_000).coerceAtLeast(MIN_BITRATE_BPS)

            // Ensure camera controls (AE FPS range) never exceed low-power ceiling.
            cameraFpsGovernorCap = 15
            cameraFpsCeiling = 15
            cameraFpsRange = null
            cameraFpsComputedForW = 0
            cameraFpsComputedForH = 0

            Log.w(
                logFrom,
                "🟢 [LOW POWER] Entering idle low power mode ($reason): sessions=$activeViewerSessions ui=$primaryUiVisible rec=${isRecordingActive()} -> ${currentWidth}x${currentHeight}@${currentFPS} br=$currentBitrate"
            )
        } else {
            // Restore last known active config when leaving idle.
            val restore = lastNonIdleConfig
            if (restore != null) {
                currentWidth = restore.width
                currentHeight = restore.height
                currentBitrate = restore.bitrate
                currentFPS = restore.fps
            } else {
                // Fall back to profile-derived defaults if we have no previous active config.
                ensureDeviceProfile("idle_exit")
            }

            // Restore FPS ceiling from profile when available; otherwise default to 30.
            val prof = deviceProfile
            val restoredCap = (prof?.chosenFixedFps ?: 30).coerceIn(15, 30)
            cameraFpsGovernorCap = restoredCap
            cameraFpsCeiling = restoredCap
            cameraFpsRange = prof?.chosenAeFpsRange
            cameraFpsComputedForW = 0
            cameraFpsComputedForH = 0

            Log.w(
                logFrom,
                "🟢 [LOW POWER] Exiting idle low power mode ($reason): sessions=$activeViewerSessions ui=$primaryUiVisible rec=${isRecordingActive()} -> ${currentWidth}x${currentHeight}@${currentFPS} br=$currentBitrate"
            )
        }

        // Only rebind if capture is actually running. If service is IDLE, do not start camera/encoder here.
        if (serviceState != ServiceCaptureState.IDLE) {
            requestCameraRebind(
                reason = "low_power_mode_${if (idleNow) "enter" else "exit"}_$reason",
                includePreview = primaryUiVisible
            )
        }
    }

    @Volatile
    private var rebindWantsPreview: Boolean = true

    // ---- Active stream parameters ----
    private var currentWidth = 1080  // 4:3 portrait target (width x height)
    private var currentHeight = 1440
    private var currentBitrate = 5_000_000
    private var currentFPS = 30

    // ---- Camera2 FPS tuning (ceiling) ----
    // IMPORTANT:
    // - Camera FPS should be a stable ceiling (rarely changed) for sensor/AE stability.
    // - Encoder/streaming may adapt by dropping frames or reducing bitrate.
    // - We strongly prefer FIXED FPS ranges: [30-30] -> [24-24] -> [15-15]
    @Volatile
    private var cameraFpsCeiling: Int = 30

    @Volatile
    private var cameraFpsRange: Range<Int>? = null

    @Volatile
    private var cameraFpsComputedForW: Int = 0

    @Volatile
    private var cameraFpsComputedForH: Int = 0

    // Governor: downgrade-only ceiling cap (never auto-increase).
    // This cap is applied on top of resolution constraints and available fixed ranges.
    @Volatile
    private var cameraFpsGovernorCap: Int = 30

    @Volatile
    private var cameraFpsLastChangeUptimeMs: Long = 0L

    // Grace period after a downgrade: ignore metrics briefly so AE/encoder can stabilize.
    @Volatile
    private var cameraFpsIgnoreMetricsUntilUptimeMs: Long = 0L

    // Cooldown between camera-FPS changes (avoid frequent sensor reconfiguration).
    @Volatile
    private var cameraFpsChangeCooldownMs: Long = 30_000L

    // Backpressure tracking (proxy for sustained network / receiver stress).
    @Volatile
    private var lastBackpressureUptimeMs: Long = 0L

    @Volatile
    private var backpressureStreakStartUptimeMs: Long = 0L

    @Volatile
    private var backpressureEventCountInStreak: Int = 0

    // Bitrate floor tracking (encoder already at min).
    @Volatile
    private var atMinBitrateSinceUptimeMs: Long = 0L

    // Latest measured analysis FPS (delivered by camera pipeline).
    @Volatile
    private var lastMeasuredAnalysisFps: Double = 0.0

    // Talkback diagnostics (rate-limited).
    @Volatile
    private var lastTalkbackDbgUptimeMs: Long = 0L

    // Governor scheduler
    @Volatile
    private var cameraFpsGovernorRunning: Boolean = false
    private val cameraFpsGovernorTickMs: Long = 2_000L

    private fun ensureDeviceProfile(reason: String) {
        try {
            val p = RuntimeProbe.ensureProfile(this, wantFrontCamera = useFrontCamera)
            if (p != null) {
                deviceProfile = p
                // Apply defaults only when not actively recording.
                if (serviceState != ServiceCaptureState.RECORDING) {
                    applyProfileDefaults(p, reason)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun applyProfileDefaults(p: DeviceProfile, reason: String) {
        try {
            val top = p.activeProbeSelectedConfig ?: p.qualityLadder.firstOrNull() ?: return
            currentWidth = top.width
            currentHeight = top.height
            currentFPS = top.fps
            currentBitrate = top.bitrate.coerceAtMost(p.maxRecommendedBitrateBps)

            // Prefer fixed FPS from profile to keep sensor/AE stable.
            cameraFpsGovernorCap = p.chosenFixedFps
            cameraFpsCeiling = p.chosenFixedFps
            cameraFpsRange = p.chosenAeFpsRange
            // Force recompute if resolution changes later.
            cameraFpsComputedForW = 0
            cameraFpsComputedForH = 0

            Log.w(
                logFrom,
                "✅ [DEVICE PROFILE] Applied defaults ($reason): size=${currentWidth}x${currentHeight} fps=$currentFPS bitrate=$currentBitrate preferBuffer=${p.preferBufferMode}"
            )
        } catch (_: Throwable) {
        }
    }

    private fun maybeRunActiveProbe(provider: ProcessCameraProvider, displayRotation: Int) {
        val p = deviceProfile ?: return
        if (activeProbeRunning) return
        if (serviceState == ServiceCaptureState.RECORDING) return
        if (p.activeProbeSelectedConfig != null) return

        activeProbeRunning = true
        try {
            Log.w(logFrom, "🧪 [ACTIVE PROBE] Starting active pipeline probe (cameraFront=$useFrontCamera)…")
            val outcome = ActivePipelineProber.probe(
                context = this,
                provider = provider,
                owner = this,
                wantFrontCamera = useFrontCamera,
                candidates = p.qualityLadder,
                displayRotation = displayRotation,
                verifyVideoCaptureCombo = AppSettings.isActiveProbeVideoCaptureComboEnabled(this)
            )
            if (outcome != null) {
                val updated = p.copy(
                    activeProbeSelectedConfig = outcome.selectedConfig,
                    activeProbeSelectedPreferBufferMode = outcome.preferBufferMode,
                    activeProbeVerifiedVideoCaptureCombo = outcome.notes.contains("_combo_")
                )
                DeviceProfileStore.save(this, updated)
                deviceProfile = updated
                applyProfileDefaults(updated, "active_probe")
                Log.w(logFrom, "🧪 [ACTIVE PROBE] Completed: selected=${outcome.selectedConfig} preferBuffer=${outcome.preferBufferMode} notes=${outcome.notes}")
            } else {
                Log.w(logFrom, "🧪 [ACTIVE PROBE] No probe success; leaving capability-derived defaults unchanged")
            }
        } catch (t: Throwable) {
            Log.w(logFrom, "🧪 [ACTIVE PROBE] Failed (ignored)", t)
        } finally {
            activeProbeRunning = false
        }
    }

    private fun resolveRequestedConfigUsingProfile(cfg: StreamConfig): StreamConfig {
        val p = deviceProfile ?: return cfg
        // Always pick a size from the validated common sensor mode list.
        // This is the core fix to avoid FOV/crop mismatches across use-cases.
        val size = p.common4by3Size
        val fps = cfg.fps.coerceIn(15, p.chosenFixedFps)
        val br = cfg.bitrate.coerceIn(MIN_BITRATE_BPS, p.maxRecommendedBitrateBps)
        return StreamConfig(size.width, size.height, br, fps)
    }

    /* ---------- Storage ---------- */
    @Volatile
    private var maxStorageBytes: Long = AppSettings.DEFAULT_STORAGE_LIMIT_BYTES

    @Volatile
    private var videoRelativePath: String = AppSettings.DEFAULT_VIDEO_RELATIVE_PATH

    @Volatile
    private var fileRotationEnabled: Boolean = AppSettings.DEFAULT_FILE_ROTATION_ENABLED

    @Volatile
    private var serverPort: Int = AppSettings.DEFAULT_PORT

    @Volatile
    private var videoTreeUri: Uri? = null

    /**********Debug Log Name***********/
    private val logFrom = "CCTV_CFS"
    private var imageAnalysis: ImageAnalysis? = null
    private var sustainedPressureCount = 0
    private val encoderLock = Any()


    @Volatile
    private var lastRotationDegrees: Int? = null

    @Volatile
    private var useFrontCamera: Boolean = false

    @Volatile
    private var lastAnalysisLogMs: Long = 0L

    // Track latest ImageAnalysis frame characteristics for recording optimization.
    // CRITICAL: In ByteBuffer mode, if recording dimensions don't match analysis frame size,
    // CustomRecorder must scale/rotate in Kotlin which is expensive and reduces FPS.
    @Volatile
    private var lastAnalysisWidth: Int = 0

    @Volatile
    private var lastAnalysisHeight: Int = 0

    @Volatile
    private var lastAnalysisRotationDegrees: Int = 0

    // Track last time we actually received an ImageAnalysis frame.
    // Used to avoid starting recording before rotation metadata is available (would force expensive pixel rotation).
    @Volatile
    private var lastAnalysisFrameUptimeMs: Long = 0L

    // Delivered-FPS diagnostics (trust sensor timestamps, not requested values).
    @Volatile
    private var analysisFpsWindowStartNs: Long = 0L

    @Volatile
    private var analysisFpsFrameCount: Int = 0

    @Volatile
    private var analysisLastTimestampNs: Long = 0L

    @Volatile
    private var lastAnalysisFpsLogUptimeMs: Long = 0L

    // Thermal state snapshot for governor decisions.
    @Volatile
    private var lastThermalStatus: Int = 0 // PowerManager.THERMAL_STATUS_NONE (API 29+)

    // Recording lifecycle hint for camera controls.
    // CRITICAL: serviceState becomes RECORDING only after CustomRecorder invokes onRecordingStarted,
    // but we want recording-optimized camera controls (FPS/anti-banding) to apply immediately when user taps record.
    @Volatile
    private var recordingRequested: Boolean = false
    private val rebindHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var rebindToken: Long = 0L

    // Camera FPS governor runner (uses the service's main handler for determinism).
    private val cameraFpsGovernorRunnable = object : Runnable {
        override fun run() {
            if (!cameraFpsGovernorRunning) return
            try {
                evaluateCameraFpsGovernorTick()
            } catch (t: Throwable) {
                // Never let a governor crash capture; just log and keep running.
                Log.w(logFrom, "⚠️ [CAMERA FPS GOV] Tick failed (non-fatal)", t)
            }
            if (cameraFpsGovernorRunning) {
                rebindHandler.postDelayed(this, cameraFpsGovernorTickMs)
            }
        }
    }

    // AIMD Bitrate Controller
    @Volatile
    private var bitrateMax: Int = 3_000_000

    @Volatile
    private var lastCongestionUptimeMs: Long = 0L

    @Volatile
    private var bitrateGovernorRunning: Boolean = false
    private val BITRATE_INC_STEP = 250_000
    private val BITRATE_INC_INTERVAL_MS = 2000L

    // Rate limit manual/AIMD adjustments to prevent oscillation/storms
    @Volatile
    private var lastBitrateChangeUptimeMs: Long = 0L
    private val BITRATE_CHANGE_MIN_INTERVAL_MS = 2000L

    private val bitrateRecoveryRunnable = object : Runnable {
        override fun run() {
            if (!bitrateGovernorRunning) return
            try {
                attemptBitrateRecovery()
            } catch (t: Throwable) {
                Log.w(logFrom, "AIMD recovery attempt failed", t)
            }
            if (bitrateGovernorRunning) {
                rebindHandler.postDelayed(this, BITRATE_INC_INTERVAL_MS)
            }
        }
    }

    private fun startBitrateRecoveryGovernor() {
        if (bitrateGovernorRunning) return
        bitrateGovernorRunning = true
        Log.d(logFrom, "AIMD: Starting bitrate recovery governor")
        rebindHandler.postDelayed(bitrateRecoveryRunnable, BITRATE_INC_INTERVAL_MS)
    }

    private fun stopBitrateRecoveryGovernor() {
        bitrateGovernorRunning = false
        rebindHandler.removeCallbacks(bitrateRecoveryRunnable)
        Log.d(logFrom, "AIMD: Stopping bitrate recovery governor")
    }

    private fun attemptBitrateRecovery() {
        val now = android.os.SystemClock.uptimeMillis()
        // Wait at least 5 seconds after congestion before trying to recover
        if (now - lastCongestionUptimeMs < 5000L) return

        // Also respect backpressure streaks (FPS governor input)
        if (now - lastBackpressureUptimeMs < 3000L) return

        if (currentBitrate < bitrateMax) {
            // Check Camera FPS stability? If we are currently restricted by Thermal/FPS governor, maybe don't increase bitrate?
            // Actually, bitrate increase might help quality if FPS is low.
            // But if we are thermally throttled, increasing bitrate increases heat.
            if (lastThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) return

            val next = (currentBitrate + BITRATE_INC_STEP).coerceAtMost(bitrateMax)
            if (next != currentBitrate) {
                // Rate Limit Check
                val nowUptime = android.os.SystemClock.uptimeMillis()
                if (nowUptime - lastBitrateChangeUptimeMs < BITRATE_CHANGE_MIN_INTERVAL_MS + 200) {
                    // Add small buffer to avoid exact-interval conflicts
                    return
                }

                Log.d(
                    logFrom,
                    "AIMD: Recovering bitrate: ${currentBitrate / 1000}k -> ${next / 1000}k (max ${bitrateMax / 1000}k)"
                )
                adjustBitrate(next) // adjustBitrate updates currentBitrate
            }
        }
    }

    // Fix for rotation label getting stuck: use explicit OrientationEventListener
    // instead of relying solely on CameraX ImageAnalysis metadata (which depends on TargetRotation).
    private var orientationListener: android.view.OrientationEventListener? = null

    // Audio Streaming (legacy flag - kept for compatibility checks)
    @Volatile
    private var isStreamingAudio = false

    private lateinit var cameraExecutor: java.util.concurrent.ExecutorService

    // Dedicated executor for ImageAnalysis analyzer work.
    // CRITICAL: Avoid running analyzer on the shared cameraExecutor to prevent occasional stalls
    // (which show up as 10/15fps gaps in recording even when camera is configured for 30fps).
    private lateinit var analysisExecutor: java.util.concurrent.ExecutorService

    // CRITICAL: stopRecording() can block (draining codecs + muxer.stop), so never run it on UI thread.
    private val recordingControlExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "CFS-RecordingControl").apply { isDaemon = true }
        }

    @Volatile
    private var stopRecordingInProgress: Boolean =
        false/*Disabled as the fun runCameraXOpAsync using these values is itself disabled */
    // CameraX operations must not block the UI thread. We schedule all bind/unbind work on cameraExecutor.
    //private val cameraXOpInProgress = AtomicBoolean(false)
    // @Volatile
    //  private var cameraXOpName: String = ""
    // @Volatile
    // private var cameraXOpStartUptimeMs: Long = 0L

    /**
     * Schedule a CameraX graph mutation on the application's MAIN thread.
     *
     * Correctness rule:
     * - CameraX API calls that mutate the use-case graph MUST run on the main thread.
     *   (e.g. Preview.setSurfaceProvider, bind/unbind, bindToLifecycle)
     *
     * Stability rule:
     * - Binder calls from the UI (Compose lifecycle) must return quickly.
     *   We therefore *post* the work onto the main thread and add a timeout log.
     *
     * Note:
     * - This does not eliminate the possibility that CameraX itself blocks the main thread under HAL stress.
     *   It does ensure we never crash due to thread-affinity violations, and the watchdog makes stalls visible.
     *//* DISABLED BECAUSE it has been replaced by direct usage of rebindHandler*//*  private fun runCameraXOpAsync(opName: String, timeoutMs: Long = 2_000L, block: () -> Unit) {
          try {
              cameraXOpName = opName
              cameraXOpStartUptimeMs = android.os.SystemClock.uptimeMillis()
              cameraXOpInProgress.set(true)

              // Watchdog: log if the operation appears stuck.
              rebindHandler.postDelayed({
                  try {
                      if (cameraXOpInProgress.get()) {
                          val now = android.os.SystemClock.uptimeMillis()
                          val dur = now - cameraXOpStartUptimeMs
                          if (dur >= timeoutMs) {
                              Log.w(
                                  logFrom,
                                  "⚠️ [CAMERAX] Operation '$cameraXOpName' taking ${dur}ms (timeout=${timeoutMs}ms). " + "If ANRs occur, this is a prime suspect."
                              )
                          }
                      }
                  } catch (_: Throwable) {
                  }
              }, timeoutMs)

              rebindHandler.post {
                  try {
                      block()
                  } catch (t: Throwable) {
                      Log.w(logFrom, "⚠️ [CAMERAX] Operation '$opName' failed (ignored)", t)
                  } finally {
                      cameraXOpInProgress.set(false)
                  }
              }
          } catch (t: Throwable) {
              cameraXOpInProgress.set(false)
              Log.w(logFrom, "⚠️ [CAMERAX] Failed to schedule '$opName' (ignored)", t)
          }
      }*/

    /**
     * Expose stop-recording progress for UI.
     *
     * Intended use: disable Stop Recording button while stop is running, to prevent double taps
     * and avoid UI "crash"/ANR perception while codecs/muxer are draining in background.
     */
    fun isStopRecordingInProgress(): Boolean = stopRecordingInProgress
    private val prefs by lazy {
        getSharedPreferences("cctv_service", MODE_PRIVATE)
    }

    @Volatile
    private var accessPassword: String = AppSettings.DEFAULT_PASSWORD

    @Volatile
    var enableTalkback: Boolean = AppSettings.DEFAULT_TALKBACK_ENABLED
        set(value) {
            field = value
            AppSettings.setTalkbackEnabled(this, value)
            // TalkBack controls only two-way communication (Viewer -> Primary audio)
            // Streaming audio (Primary -> Viewer) is independent and always active during preview
            // No need to start/stop streaming audio here - it's controlled by video capture state
            try {
                streamServer?.broadcastCommunicationEnabled(value)
            } catch (_: Throwable) {
            }
        }

    fun setAccessPassword(password: String) {
        accessPassword = password.ifBlank { AppSettings.DEFAULT_PASSWORD }
        AppSettings.setPassword(this, accessPassword)
    }

    // --- UI/Client Helpers (Restored for CctvScreen compatibility) ---

    // Note: isRecordingWithAudio, isUsingFrontCamera, attachPreview, detachPreview, requestSwitchCamera
    // are already defined elsewhere in this file. We only keep unique helpers here.

    fun setPrimaryUiVisible(visible: Boolean) {
        primaryUiVisible = visible
        // If UI becomes visible, we might want to trigger a rebind or ensure preview execution,
        // but current logic relies on helper attachPreview/detachPreview calls from UI.
        try {
            updateIdleState("ui_visible")
        } catch (_: Throwable) {
        }
    }

    private fun reloadUserSettings() {
        serverPort = AppSettings.getPort(this)
        accessPassword = AppSettings.getPassword(this)
        enableTalkback = AppSettings.isTalkbackEnabled(this)
        videoRelativePath = AppSettings.getVideoRelativePath(this)
        videoTreeUri =
            AppSettings.getVideoTreeUri(this)?.let { runCatching { it.toUri() }.getOrNull() }
        maxStorageBytes = AppSettings.getStorageLimitBytes(this)
        fileRotationEnabled = AppSettings.isFileRotationEnabled(this)
    }

    /**
     * Public method to reload user settings from AppSettings.
     * Called when settings are changed in MainActivity to update the service immediately.
     */
    fun refreshUserSettings() {
        reloadUserSettings()
        // Refresh profile-driven caps (e.g., bitrate ceiling / fps policy) after settings changes.
        ensureDeviceProfile("refresh_user_settings")
    }

    /* ---------------- Lifecycle ---------------- */
    override fun onCreate() {
        super.onCreate()

        // Initialize AudioSourceEngine with context
        audioSourceEngine.setContext(this)

        powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.addThermalStatusListener(thermalListener)
        }


        Log.d(logFrom, "CameraForegroundService created")
        cameraExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        analysisExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread({
                try {
                    // Favor smooth camera frame processing; safe no-op if it fails.
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
                } catch (_: Throwable) {
                }
                r.run()
            }, "CFS-ImageAnalysis").apply {
                isDaemon = true
            }
        }
        // Foreground service start (Android 14+/targetSdk 34+ enforcement):
        // If we claim microphone FGS type without meeting eligibility + permissions, Android throws
        // a SecurityException and the process crashes. Video streaming must remain stable, so we
        // start as CAMERA-only here.
        //
        // Audio/talkback remains optional; it can be handled separately with an explicit mic-FGS
        // (only after user action + permission checks) if needed.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // FOREGROUND_SERVICE_TYPE_CAMERA requires API 30 (R)
                @Suppress("InlinedApi") startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
                Log.d(logFrom, "✅ Foreground service started with type=CAMERA")
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
                Log.d(logFrom, "✅ Foreground service started (legacy, no type)")
            }
        } catch (se: SecurityException) {
            Log.e(
                logFrom,
                "❌ Foreground service start failed due to SecurityException. Stopping service to avoid crash loop.",
                se
            )
            stopSelf()
            return
        } catch (t: Throwable) {
            Log.e(logFrom, "❌ Foreground service start failed (unexpected). Stopping service.", t)
            stopSelf()
            return
        }

        serviceState = ServiceCaptureState.valueOf(
            prefs.getString("service_state", ServiceCaptureState.IDLE.name)!!
        )
        // Crash/process-death hardening:
        // SharedPreferences may retain "RECORDING" if the process dies before onDestroy() / stopRecording()
        // completes. On a fresh service instance we have no CustomRecorder session to resume (FD/muxer state
        // is not persisted), so treating ourselves as RECORDING would be inconsistent (UI/Viewer may think
        // we're recording while no file is written). Downgrade to PREVIEW safely.
        if (serviceState == ServiceCaptureState.RECORDING) {
            Log.w(
                logFrom,
                "⚠️ [RECORDING] Restored service_state=RECORDING from prefs on fresh start; no resumable session. Downgrading to PREVIEW."
            )
            serviceState = ServiceCaptureState.PREVIEW
            recordingRequested = false
            recordingWithAudio = false
            persistState()
        }

        // Load user settings that influence networking, talkback, and storage.
        reloadUserSettings()
        // Build/load capability-driven device profile early so initial capture uses validated sizes/fps.
        ensureDeviceProfile("service_create")

        //Streaming
        streamServer = StreamServer(
            port = serverPort,
            passwordProvider = { accessPassword },
            onRemoteCommand = { command, payload ->
                onCommand(command, payload)
            }).apply {
            onSessionCountChanged = { count ->
                activeViewerSessions = count
                try {
                    updateIdleState("sessions")
                } catch (_: Throwable) {
                }
            }
            rotationProvider = { lastRotationDegrees }
            onStreamConfigResolved = { cfg ->
                Log.d(logFrom, "Resolved stream config: $cfg")
                reconfigureEncoderIfNeeded(cfg)
            }
            onRequestKeyframe = {
                synchronized(encoderLock) {
                    videoEncoder?.requestSyncFrame()
                }
            }
            isRecordingProvider = { serviceState == ServiceCaptureState.RECORDING }
            cameraFacingProvider = { useFrontCamera }
            communicationEnabledProvider = { enableTalkback }
            onAudioFrameUp = { data ->
                playTalkback(data)
            }
            // CRITICAL: Set encoder resolution providers so StreamServer uses actual encoder dimensions
            // This ensures Buffer Mode (960x720) overrides requested resolution (1080x1440)
            encoderWidthProvider = {
                if (VideoEncoder.shouldPreferBufferMode(this@CameraForegroundService, AppSettings.isForceBufferMode(this@CameraForegroundService))) {
                    // Buffer Mode resolution (respect orientation)
                    if (currentWidth < currentHeight) 720 else 960
                } else {
                    synchronized(encoderLock) {
                        videoEncoder?.getWidth() ?: currentWidth
                    }
                }
            }
            encoderHeightProvider = {
                if (VideoEncoder.shouldPreferBufferMode(this@CameraForegroundService, AppSettings.isForceBufferMode(this@CameraForegroundService))) {
                    // Buffer Mode resolution (respect orientation)
                    if (currentWidth < currentHeight) 960 else 720
                } else {
                    synchronized(encoderLock) {
                        videoEncoder?.getHeight() ?: currentHeight
                    }
                }
            }
        }/*if (android.os.Build.VERSION.SDK_INT >= 29) {
                val mgr = getSystemService(android.os.ThermalService::class.java)
                mgr?.registerThermalStatusListener { status ->
                        if (status >= android.os.ThermalStatus.STATUS_SEVERE) {
                                Log.w(logFrom, "Thermal severe — reducing bitrate")
                                 reconfigureEncoderIfNeeded(
                                         StreamConfig(
                                                currentWidth,
                                                currentHeight,
                                                (currentBitrate * 0.7).toInt(),
                                               currentFPS
                                                   )
                                            )
                            }
                     }
           }*/

        streamServer?.start()
        // Apply low-power policy on startup after StreamServer is live.
        try {
            activeViewerSessions = streamServer?.activeSessionCount() ?: 0
            updateIdleState("service_create")
        } catch (_: Throwable) {
        }
        // Streaming audio now starts automatically when video capture begins (PREVIEW state)
        // TalkBack controls two-way communication independently

        // Start orientation listener for UI rotation updates
        orientationListener = object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                // Snapchat-style snapping: 0, 90, 180, 270
                val newRotation = when (orientation) {
                    in 45..134 -> 270 // Reverse landscape
                    in 135..224 -> 180 // Upside down
                    in 225..314 -> 90 // Landscape
                    else -> 0 // Portrait
                }

                // Only broadcast if changed
                val last = lastRotationDegrees
                if (last == null || last != newRotation) {
                    lastRotationDegrees = newRotation
                    // Documented workflow:
                    // - Do NOT restart encoder / do NOT bump epochs on rotation.
                    // - Send ENC_ROT metadata so the Viewer can rotate display smoothly.
                    streamServer?.broadcastEncoderRotation(newRotation)

                    // Request an IDR for smoother perceived transition.
                    try {
                        synchronized(encoderLock) {
                            videoEncoder?.requestSyncFrame()
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        if (orientationListener?.canDetectOrientation() == true) {
            orientationListener?.enable()
        }

        // Streaming audio starts automatically when entering PREVIEW state (video capture begins)
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP_CAPTURE) {
            Log.w(logFrom, "Stop Capture requested from notification")
            stopCapture()
            return START_NOT_STICKY
        }
        // Get password from intent if provided
        intent?.getStringExtra("access_password")?.let {
            setAccessPassword(it)
            Log.d(
                logFrom, "Password updated from intent: ${if (it.isNotEmpty()) "***" else "empty"}"
            )
        }
        intent?.getBooleanExtra("enable_talkback", AppSettings.DEFAULT_TALKBACK_ENABLED)?.let {
            enableTalkback = it
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        // Get password from intent if provided
        intent.getStringExtra("access_password")?.let {
            setAccessPassword(it)
            Log.d(logFrom, "Password updated from bind: ${if (it.isNotEmpty()) "***" else "empty"}")
        }
        intent.getBooleanExtra("enable_talkback", true).let {
            enableTalkback = it
        }
        return binder
    }

    override fun onDestroy() {
        Log.d(logFrom, "Service destroyed")

        if (serviceState == ServiceCaptureState.RECORDING) {
            stopRecording()
        }
        try {
            stopCameraFpsGovernor("on_destroy")
        } catch (_: Throwable) {
        }
        stopTalkback()
        stopStreamingAudio()
        try {
            cameraExecutor.shutdownNow()
        } catch (_: Exception) {
            Log.w(logFrom, "Camera executor shutdown error (ignored)")
        }
        try {
            analysisExecutor.shutdownNow()
        } catch (_: Exception) {
            Log.w(logFrom, "Analysis executor shutdown error (ignored)")
        }
        stopEncoder()
        try {
            streamServer?.stop()
        } catch (_: Exception) {
        }

        serviceState = ServiceCaptureState.IDLE
        persistState()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.removeThermalStatusListener(thermalListener)
        }
        orientationListener?.disable()
        super.onDestroy()
    }

    /* ------Camera binding ------------- */


    @OptIn(ExperimentalCamera2Interop::class)
    fun bindCamera(
        surfaceProvider: Preview.SurfaceProvider?,
        viewPort: ViewPort? = null,
        displayRotation: Int = Surface.ROTATION_0,
        includeVideoCapture: Boolean = false
    ) {
        // IMPORTANT: Do not erase the last known UI surface provider when doing headless binds
        // (e.g., Viewer-triggered camera switches while Primary is backgrounded).
        if (surfaceProvider != null) {
            lastSurfaceProvider = surfaceProvider
        }
        lastViewPort = viewPort
        lastDisplayRotation = displayRotation

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(logFrom, "CAMERA permission missing")
            return
        }

        val token = System.nanoTime()
        bindToken = token
        // Capture selector at call time to avoid races if useFrontCamera changes mid-bind.
        val selectorAtCall = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
        else CameraSelector.DEFAULT_BACK_CAMERA

        Log.d(
            logFrom,
            "Trying to bind camera (token=$token, front=${useFrontCamera}, includePreview=${surfaceProvider != null}, includeVideoCapture=$includeVideoCapture)"
        )
        val providerFuture = ProcessCameraProvider.getInstance(this)
        // CRITICAL: run the (potentially expensive) bind/unbind work off the main thread to prevent ANRs.
        providerFuture.addListener(
            {
                if (bindToken != token) {
                    Log.d(logFrom, "bindCamera: ignoring stale bind (token=$token)")
                    return@addListener
                }

                runWhenStarted {
                    if (bindToken != token) {
                        Log.d(
                            logFrom,
                            "bindCamera: ignoring stale bind inside runWhenStarted (token=$token)"
                        )
                        return@runWhenStarted
                    }
                    cameraProvider = providerFuture.get()

                    // Ensure we have a capability-driven profile before choosing sizes and starting encoder.
                    ensureDeviceProfile("bind_camera")
                    // Active probe (first-run only): verify real camera->encoder pipeline and persist best rung.
                    maybeRunActiveProbe(cameraProvider!!, displayRotation)

                    // Start encoder and get Surface BEFORE binding camera
                    // Encoder Surface must exist before we can bind it to Preview
                    startEncoder()
                    val encoderSurface =
                        synchronized(encoderLock) { videoEncoder?.getInputSurface() }
                    val encoderMode = synchronized(encoderLock) {
                        videoEncoder?.useSurfaceInput?.let { if (it) "Surface" else "Buffer" }
                            ?: "null"
                    }
                    Log.d(
                        logFrom,
                        "🔵 [DIAGNOSTIC] Encoder mode: $encoderMode, Surface=${if (encoderSurface != null) "available" else "null (expected for Buffer mode)"}"
                    )
                    if (encoderSurface == null) {
                        if (encoderMode == "Buffer") {
                            Log.d(
                                logFrom,
                                "Encoder Surface is null (expected for ByteBuffer mode) - will use ImageAnalysis for streaming"
                            )
                        } else {
                            Log.e(
                                logFrom,
                                "Encoder Surface not available but mode is $encoderMode - cannot bind for streaming"
                            )
                        }
                        // Fallback: continue without streaming (preview only) - but for Buffer mode, ImageAnalysis will handle it
                    }

                    // IMPORTANT (FOV consistency):
                    // Binding VideoCapture can force CameraX into a cropped (often 16:9) stream configuration,
                    // which reduces the visible FOV in Preview/ImageAnalysis. Prefer SD first because some devices
                    // expose SD as 4:3, which keeps the preview/stream FOV closer to our 4:3 pipeline.
                    videoCapture = if (includeVideoCapture) {
                        videoCapture ?: run {
                            val recorder = Recorder.Builder().setQualitySelector(
                                QualitySelector.fromOrderedList(
                                    listOf(Quality.SD, Quality.HD, Quality.FHD),
                                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                                )
                            ).build()
                            VideoCapture.withOutput(recorder)
                        }
                    } else {
                        null
                    }

                    // Request portrait target aligned with negotiated resolution (4:3).
                    val target = Size(currentHeight, currentWidth) // e.g., 1440x1080
                    val selector = ResolutionSelector.Builder().setAspectRatioStrategy(
                        AspectRatioStrategy(
                            AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO
                        )
                    ).setResolutionStrategy(
                        ResolutionStrategy(
                            target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    ).build()

                    // Create Preview use case for UI
                    previewUseCase = if (surfaceProvider != null) {
                        Preview.Builder().setResolutionSelector(selector)
                            .setTargetRotation(displayRotation).build().apply {
                                rebindHandler.post {
                                    try {
                                        setSurfaceProvider(surfaceProvider)
                                        Log.d(
                                            logFrom,
                                            "setSurfaceProvider (previewUseCase) called on main thread"
                                        )
                                    } catch (e: Exception) {
                                        Log.e(logFrom, "Failed to set surface provider", e)
                                    }
                                }
                            }
                    } else {
                        null
                    }

                    // Create encoder Preview use case if encoder Surface is available
                    // This enables zero-copy hardware encoding
                    val encoderPreviewUseCase = if (encoderSurface != null) {
                        Log.d(
                            logFrom,
                            "Creating encoderPreviewUseCase - Surface available: $encoderSurface"
                        )
                        Preview.Builder().setResolutionSelector(selector)
                            .setTargetRotation(displayRotation).build().apply {
                                Log.d(
                                    logFrom,
                                    "Encoder Preview use case created, setting Surface provider..."
                                )
                                Log.d(
                                    logFrom,
                                    "Encoder Preview use case created, setting Surface provider..."
                                )
                                rebindHandler.post {
                                    try {
                                        setSurfaceProvider { request ->
                                            Log.d(
                                                logFrom,
                                                "🔵 [DIAGNOSTIC] setSurfaceProvider callback INVOKED by CameraX! resolution=${request.resolution}, surfaceRequest=$request"
                                            )
                                            // Feed encoder Surface directly (zero-copy)
                                            val surface =
                                                synchronized(encoderLock) { videoEncoder?.getInputSurface() }
                                            if (surface != null) {
                                                Log.d(
                                                    logFrom,
                                                    "✅ [DIAGNOSTIC] Encoder Surface is valid, providing to CameraX: $surface"
                                                )
                                                Log.d(
                                                    logFrom,
                                                    "Providing encoder Surface to CameraX (zero-copy streaming), resolution=${request.resolution}"
                                                )
                                                request.provideSurface(
                                                    surface, cameraExecutor
                                                ) {
                                                    Log.d(
                                                        logFrom,
                                                        "✓ Encoder Surface provided and active - CameraX should start feeding frames"
                                                    )
                                                    Log.d(
                                                        logFrom,
                                                        "✅ [DIAGNOSTIC] Surface provider callback completed - CameraX should start sending frames"
                                                    )
                                                }
                                            } else {
                                                Log.e(
                                                    logFrom,
                                                    "❌ [DIAGNOSTIC] Encoder Surface is null when CameraX requested it"
                                                )
                                                request.willNotProvideSurface()
                                            }
                                        }
                                        Log.d(
                                            logFrom,
                                            "Encoder Preview use case Surface provider set successfully on main thread"
                                        )
                                    } catch (e: Exception) {
                                        Log.e(
                                            logFrom, "Failed to set encoder surface provider", e
                                        )
                                    }
                                }
                            }
                    } else {
                        if (encoderMode == "Buffer") {
                            Log.d(
                                logFrom,
                                "Buffer mode: encoderSurface null (expected), encoderPreviewUseCase not used; ImageAnalysis feeds encoder"
                            )
                        } else {
                            Log.w(
                                logFrom,
                                "⚠️ [DIAGNOSTIC] encoderSurface is null, cannot create encoderPreviewUseCase (mode=$encoderMode)"
                            )
                        }
                        null
                    }

                    Log.d(
                        logFrom,
                        "bindCamera: previewUseCase=${previewUseCase != null}, encoderPreviewUseCase=${encoderPreviewUseCase != null}, includeVideoCapture=$includeVideoCapture"
                    )
                    cameraProvider?.unbindAll()

                    // Create a lower-resolution selector for ImageAnalysis when in ByteBuffer mode
                    // This reduces camera pipeline bandwidth stress on LIMITED devices (e.g., Samsung M30s Exynos 9611)
                    // YuvUtils handles scaling, so this is safe and improves reliability
                    // Reuse encoderMode already declared above (line 415)
                    val analysisSelector = if (encoderMode == "Buffer" || encoderSurface == null) {
                        // ByteBuffer mode: Use lower resolution to reduce bandwidth
                        // Use 4:3 aspect ratio (same as camera) but target lower resolution like 960x720 or 640x480
                        ResolutionSelector.Builder().setAspectRatioStrategy(
                            AspectRatioStrategy(
                                AspectRatio.RATIO_4_3, // Same aspect as camera/preview
                                AspectRatioStrategy.FALLBACK_RULE_AUTO
                            )
                        ).setResolutionStrategy(
                            ResolutionStrategy(
                                // Target ~720p in 4:3 (960x720 = 691k pixels vs 1440x1080 = 1.5M pixels)
                                // Fix incorrect hardcoded landscape dims: respect Portrait vs Landscape
                                if (currentWidth < currentHeight) Size(
                                    720, 960
                                ) else Size(960, 720),
                                // Falls back to VGA (640x480) or other 4:3 sizes if needed
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                            )
                        ).build().also {
                            Log.d(
                                logFrom,
                                "🔵 [DIAGNOSTIC] Using lower-resolution selector for ImageAnalysis (ByteBuffer mode): 960x720 (4:3) - reduces camera pipeline bandwidth by ~54% vs 1440x1080"
                            )
                        }
                    } else {
                        // Surface mode or no encoder: Use same resolution as Preview
                        selector.also {
                            Log.d(
                                logFrom,
                                "🔵 [DIAGNOSTIC] Using high-resolution selector for ImageAnalysis (Surface mode or recording only)"
                            )
                        }
                    }

                    val analysisBuilder =
                        ImageAnalysis.Builder().setResolutionSelector(analysisSelector)
                            // Keep output buffers un-rotated; encoder will rotate based on ImageInfo.rotationDegrees.
                            // This is more consistent across OEMs for YUV_420_888 and avoids double-rotation.
                            .setTargetRotation(displayRotation)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

                    // Some OEMs apply distortion correction / vendor "preview enhancements" differently between Preview and YUV.
                    // Apply interop only to ImageAnalysis (stream source) so Primary PreviewView stays unchanged.
                    try {
                        val ext = Camera2Interop.Extender(analysisBuilder)
                        // Stabilization off (harmless if unsupported).
                        ext.setCaptureRequestOption(
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                        )
                        ext.setCaptureRequestOption(
                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            ext.setCaptureRequestOption(
                                CaptureRequest.DISTORTION_CORRECTION_MODE,
                                CaptureRequest.DISTORTION_CORRECTION_MODE_OFF
                            )
                        }
                    } catch (t: Throwable) {
                        Log.w(
                            logFrom,
                            "ImageAnalysis Camera2 interop options not supported (ignored)",
                            t
                        )
                    }

                    // ImageAnalysis is used for:
                    // 1. CustomRecorder (recording with audio)
                    // 2. VideoEncoder ByteBuffer mode (when Surface input is not available/working)
                    // Always create ImageAnalysis when streaming OR recording is active
                    imageAnalysis = if (customRecorder != null || videoEncoder != null) {
                        analysisBuilder.build().also { analysis ->
                            analysis.setAnalyzer(analysisExecutor) { image ->
                                // Track current analysis frame shape for later recording decisions.
                                // Keep it lightweight; just store ints (no allocations).
                                try {
                                    lastAnalysisWidth = image.width
                                    lastAnalysisHeight = image.height
                                    lastAnalysisRotationDegrees = image.imageInfo.rotationDegrees
                                    lastAnalysisFrameUptimeMs =
                                        android.os.SystemClock.uptimeMillis()
                                } catch (_: Throwable) {
                                }

                                // Delivered FPS diagnostics (trust sensor timestamps):
                                // Many OEMs "lie" about requested FPS, so we log measured behavior.
                                try {
                                    val tsNs = image.imageInfo.timestamp
                                    val nowUptime = android.os.SystemClock.uptimeMillis()
                                    if (analysisFpsWindowStartNs <= 0L) {
                                        analysisFpsWindowStartNs = tsNs
                                        analysisLastTimestampNs = tsNs
                                        analysisFpsFrameCount = 0
                                    }
                                    analysisFpsFrameCount++
                                    val windowNs = tsNs - analysisFpsWindowStartNs
                                    if (windowNs >= 1_000_000_000L && (nowUptime - lastAnalysisFpsLogUptimeMs) >= 5_000L) {
                                        lastAnalysisFpsLogUptimeMs = nowUptime
                                        val fps =
                                            analysisFpsFrameCount.toDouble() * 1_000_000_000.0 / windowNs.toDouble()
                                        lastMeasuredAnalysisFps = fps
                                        val deltaMs =
                                            (tsNs - analysisLastTimestampNs).toDouble() / 1_000_000.0
                                        Log.d(
                                            logFrom, "🔵 [CAMERA FPS] Delivered FPS=${
                                                String.format(
                                                    "%.1f", fps
                                                )
                                            } " + "(deltaMs=${
                                                String.format(
                                                    "%.1f", deltaMs
                                                )
                                            }) " + "requestedCeiling=${cameraFpsCeiling} range=${cameraFpsRange} " + "analysis=${image.width}x${image.height} rot=${image.imageInfo.rotationDegrees}"
                                        )
                                        // Reset window for next measurement interval.
                                        analysisFpsWindowStartNs = tsNs
                                        analysisFpsFrameCount = 0
                                    }
                                    analysisLastTimestampNs = tsNs
                                } catch (_: Throwable) {
                                }

                                // Debug: log cropRect vs buffer size occasionally
                                val nowMs = System.currentTimeMillis()
                                if (nowMs - lastAnalysisLogMs >= 10_000L) {
                                    lastAnalysisLogMs = nowMs
                                    val cr = image.cropRect
                                    val fullCrop =
                                        (cr.left == 0 && cr.top == 0 && cr.right == image.width && cr.bottom == image.height)
                                    val cam = currentCamera
                                    val zoomRatio =
                                        try {
                                            cam?.cameraInfo?.zoomState?.value?.zoomRatio
                                        } catch (_: Throwable) {
                                            null
                                        }
                                    val activeArray =
                                        try {
                                            cam?.let {
                                                Camera2CameraInfo.from(it.cameraInfo).getCameraCharacteristic(
                                                    CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE
                                                )
                                            }
                                        } catch (_: Throwable) {
                                            null
                                        }
                                    val activeAr =
                                        if (activeArray != null && activeArray.height() > 0)
                                            activeArray.width().toFloat() / activeArray.height().toFloat()
                                        else null
                                    val enc = synchronized(encoderLock) { videoEncoder }
                                    val encMode = when (enc?.useSurfaceInput) {
                                        true -> "Surface"
                                        false -> "ByteBuffer"
                                        null -> "null"
                                    }
                                    val encW = try {
                                        enc?.getWidth()
                                    } catch (_: Throwable) {
                                        null
                                    }
                                    val encH = try {
                                        enc?.getHeight()
                                    } catch (_: Throwable) {
                                        null
                                    }
                                    val recordingActive = (customRecorder != null) || recordingRequested || serviceState == ServiceCaptureState.RECORDING
                                    val zoomStr = zoomRatio?.let {
                                        String.format(Locale.US, "%.3f", it)
                                    } ?: "?"
                                    val activeArStr = activeAr?.let {
                                        String.format(Locale.US, "%.3f", it)
                                    } ?: "?"
                                    val activeWStr = activeArray?.width()?.toString() ?: "?"
                                    val activeHStr = activeArray?.height()?.toString() ?: "?"
                                    Log.d(
                                        logFrom,
                                        "🟣 [FOVDBG][ANALYSIS] size=${image.width}x${image.height} crop=${cr.left},${cr.top},${cr.right},${cr.bottom} fullCrop=$fullCrop rot=${image.imageInfo.rotationDegrees} " +
                                            "zoomRatio=$zoomStr activeArray=${activeWStr}x${activeHStr} activeAr=$activeArStr " +
                                            "streamEncMode=$encMode streamEnc=${encW ?: "?"}x${encH ?: "?"} recordingActive=$recordingActive"
                                    )
                                }

                                // CRITICAL: ImageProxy ownership
                                // The analyzer MUST close the image exactly once, even if both recorder+encoder consume it.
                                //
                                // When recording is active and the stream is in Buffer mode, we intentionally feed the
                                // SAME ImageProxy to BOTH:
                                // - CustomRecorder (recording)
                                // - VideoEncoder (streaming)
                                //
                                // Each consumer is called with closeImage=false and the analyzer closes in finally.
                                val recorder = customRecorder
                                val encoder = synchronized(encoderLock) { videoEncoder }
                                try {
                                    recorder?.encodeFrame(image, closeImage = false)
                                    if (encoder != null && !encoder.useSurfaceInput) {
                                        Log.d(
                                            logFrom,
                                            "🔵 [FRAME FLOW] Feeding frame to VideoEncoder (ByteBuffer mode) (recording=${recorder != null})"
                                        )
                                        encoder.encode(image, closeImage = false)
                                    }
                                } catch (t: Throwable) {
                                    Log.w(
                                        logFrom,
                                        "⚠️ [FRAME FLOW] Analyzer consumer threw (non-fatal); closing image and continuing",
                                        t
                                    )
                                } finally {
                                    // Analyzer owns closing for shared-consumption path.
                                    try {
                                        image.close()
                                    } catch (_: Throwable) {
                                    }
                                }
                            }
                        }
                    } else {
                        null // No ImageAnalysis needed - neither recording nor streaming active
                    }

                    try {
                        val useCases = mutableListOf<UseCase>()
                        previewUseCase?.let {
                            useCases.add(it)
                            Log.d(
                                logFrom, "✅ [DIAGNOSTIC] Added previewUseCase to useCases list"
                            )
                        }
                        // Add encoder Preview use case for zero-copy streaming
                        encoderPreviewUseCase?.let {
                            useCases.add(it)
                            Log.d(
                                logFrom,
                                "✅ [DIAGNOSTIC] Added encoderPreviewUseCase to useCases list"
                            )
                        } ?: run {
                            if (encoderMode == "Buffer") {
                                Log.d(
                                    logFrom,
                                    "Buffer mode: encoderPreviewUseCase not used (expected), ImageAnalysis feeds encoder"
                                )
                            } else {
                                Log.w(
                                    logFrom,
                                    "⚠️ [DIAGNOSTIC] encoderPreviewUseCase is null, NOT added to useCases"
                                )
                            }
                        }
                        imageAnalysis?.let {
                            useCases.add(it)
                            Log.d(
                                logFrom, "✅ [DIAGNOSTIC] Added imageAnalysis to useCases list"
                            )
                        }
                        if (includeVideoCapture) {
                            videoCapture?.let {
                                useCases.add(it)
                                Log.d(
                                    logFrom, "✅ [DIAGNOSTIC] Added videoCapture to useCases list"
                                )
                            }
                        }

                        Log.d(
                            logFrom, "🔵 [DIAGNOSTIC] Total use cases to bind: ${useCases.size}"
                        )
                        useCases.forEachIndexed { index, useCase ->
                            Log.d(logFrom, "  [$index] ${useCase.javaClass.simpleName}")
                        }

                        // Bind through a ViewPort so use cases share the same sensor crop/FOV.
                        // We DO NOT rely on PreviewView.viewPort (can vary by layout); instead use a fixed 3:4 viewport
                        // matching our UI container and stream aspect.
                        val vp = viewPort ?: ViewPort.Builder(Rational(3, 4), displayRotation)
                            // Prefer FIT to maximize FOV while keeping 3:4 aspect (no bars, no distortion).
                            .setScaleType(ViewPort.FIT).build()

                        Log.d(
                            logFrom,
                            "🔵 [DIAGNOSTIC] Creating UseCaseGroup with ViewPort: aspect=${vp.aspectRatio}, rotation=${vp.rotation}"
                        )
                        val groupBuilder = UseCaseGroup.Builder().setViewPort(vp)
                        useCases.forEach { groupBuilder.addUseCase(it) }
                        val group = groupBuilder.build()
                        Log.d(
                            logFrom, "🔵 [DIAGNOSTIC] UseCaseGroup built, binding to lifecycle..."
                        )
                        val camera = try {
                            cameraProvider?.bindToLifecycle(this, selectorAtCall, group)
                        } catch (ex: Exception) {
                            // Critical recovery:
                            // Some devices (e.g., Nord CE4) cannot bind our Surface-mode combination:
                            //   Preview(UI) + Preview(encoder Surface) + ImageAnalysis
                            // CameraX throws "No supported surface combination".
                            //
                            // When this happens, automatically fall back to ByteBuffer (ImageAnalysis->encoder),
                            // and rebind with a supported set (Preview + ImageAnalysis).
                            val msg = (ex.message ?: "").lowercase()
                            val looksLikeUnsupportedCombo =
                                msg.contains("no supported surface combination") ||
                                    msg.contains("may be attempting to bind too many use cases")

                            val encSurfaceMode = synchronized(encoderLock) { videoEncoder?.useSurfaceInput == true }

                            if (looksLikeUnsupportedCombo && encSurfaceMode && !forceBufferModeDueToCameraXCombo) {
                                Log.w(
                                    logFrom,
                                    "🟠 [CAMERAX] Unsupported Surface combo detected; falling back to Buffer mode and rebinding (reason=${ex.message})"
                                )
                                forceBufferModeDueToCameraXCombo = true
                                try {
                                    // Persist as a runtime probe result so future runs skip Surface mode.
                                    EncoderProbeStore.markSurfaceInputBad(this@CameraForegroundService)
                                } catch (_: Throwable) {
                                }
                                try {
                                    stopEncoder()
                                } catch (_: Throwable) {
                                }
                                try {
                                    cameraProvider?.unbindAll()
                                } catch (_: Throwable) {
                                }
                                // Retry: bind again (will start encoder in Buffer mode and skip encoderPreviewUseCase).
                                try {
                                    bindCamera(
                                        surfaceProvider = surfaceProvider,
                                        viewPort = viewPort,
                                        displayRotation = displayRotation,
                                        includeVideoCapture = includeVideoCapture
                                    )
                                } catch (_: Throwable) {
                                }
                                return@runWhenStarted
                            }
                            throw ex
                        }
                        // Remove any previous camera-state observer before replacing currentCamera.
                        try {
                            val prevCam = currentCamera
                            val prevObs = cameraStateObserver
                            if (prevCam != null && prevObs != null) {
                                prevCam.cameraInfo.cameraState.removeObserver(prevObs)
                            }
                        } catch (_: Throwable) {
                        }
                        currentCamera = camera
                        if (camera != null) {
                            Log.d(logFrom, "✅ [DIAGNOSTIC] Camera bound successfully: $camera")
                            Log.d(
                                logFrom,
                                "🔵 [DIAGNOSTIC] Camera instance: ${camera.javaClass.simpleName}"
                            )
                            Log.d(
                                logFrom,
                                "🔵 [DIAGNOSTIC] Camera info: ${camera.cameraInfo.javaClass.simpleName}"
                            )
                            // Camera restart resilience:
                            // Observe CameraX cameraState errors and request a safe rebind with throttling.
                            try {
                                val obs = Observer<CameraState> { st ->
                                    try {
                                        val err = st.error
                                        if (err != null) {
                                            val nowUptime = android.os.SystemClock.uptimeMillis()
                                            val sinceLast =
                                                nowUptime - lastCameraErrorRebindUptimeMs
                                            if (sinceLast < 2_000L) return@Observer
                                            lastCameraErrorRebindUptimeMs = nowUptime
                                            Log.e(
                                                logFrom,
                                                "❌ [CAMERA] CameraState ERROR: code=${err.code} cause=${err.cause?.javaClass?.simpleName ?: "null"}; requesting rebind (sinceLast=${sinceLast}ms)"
                                            )
                                            // Rebind same camera; include Preview only if UI surface is expected valid.
                                            requestCameraRebind(
                                                reason = "camera_error_${err.code}",
                                                includePreview = primaryUiVisible
                                            )
                                        }
                                    } catch (t: Throwable) {
                                        Log.w(
                                            logFrom,
                                            "⚠️ [CAMERA] cameraState observer error (ignored)",
                                            t
                                        )
                                    }
                                }
                                cameraStateObserver = obs
                                camera.cameraInfo.cameraState.observe(
                                    this@CameraForegroundService, obs
                                )
                            } catch (t: Throwable) {
                                Log.w(
                                    logFrom,
                                    "⚠️ [CAMERA] Failed to attach cameraState observer (ignored)",
                                    t
                                )
                            }
                        } else {
                            Log.e(logFrom, "❌ [DIAGNOSTIC] Camera binding returned null!")
                        }
                        // Samsung M30s (Android 11) low-light behavior:
                        // A strict 30fps fixed AE range can force short exposures and make preview/recording look dark.
                        // Prefer a lower FPS ceiling to allow longer exposure times (brighter output), especially on Exynos.
                        try {
                            val manu = Build.MANUFACTURER?.lowercase() ?: ""
                            val model = Build.MODEL ?: ""
                            val isSamsungAndroid11 = manu == "samsung" && Build.VERSION.SDK_INT == Build.VERSION_CODES.R
                            val isM30Family =
                                model.contains("M30s", ignoreCase = true) ||
                                    model.contains("SM-M307", ignoreCase = true) ||
                                    model.contains("M31", ignoreCase = true) ||
                                    model.contains("M32", ignoreCase = true)
                            if (isSamsungAndroid11 && isM30Family && cameraFpsGovernorCap > 24) {
                                cameraFpsGovernorCap = 24
                                // Force re-selection under the new cap.
                                cameraFpsRange = null
                                cameraFpsComputedForW = 0
                                cameraFpsComputedForH = 0
                                Log.w(
                                    logFrom,
                                    "🟠 [CAMERA FPS] Samsung Android11 default FPS cap set to 24 for brighter exposure (model=$model)"
                                )
                            }
                        } catch (_: Throwable) {
                        }
                        applyCameraControls()
                        // Start governor once camera is active; it will no-op when IDLE.
                        try {
                            startCameraFpsGovernor()
                        } catch (_: Throwable) {
                        }
                        if (serviceState != ServiceCaptureState.RECORDING) {
                            serviceState = ServiceCaptureState.PREVIEW
                            persistState()
                            streamServer?.broadcastRecording(false)
                            // Start streaming audio automatically when video capture begins (PREVIEW state)
                            // This ensures viewers always receive audio during preview
                            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                startStreamingAudio()
                            }
                        }
                        Log.d(logFrom, "Camera bound safely")
                        streamServer?.broadcastCameraFacing(useFrontCamera)

                        // Recording can start immediately after camera bind (no VideoCapture needed)
                    } catch (ex: Exception) {
                        Log.e(logFrom, "Camera bound failed" + ex.message)
                    }


                }

            }, ContextCompat.getMainExecutor(this)
        )


    }

    /* ---Recording control ---------------- */
    /**
     * Start recording asynchronously.
     *
     * IMPORTANT:
     * - This can involve expensive work (storage scans, MediaMuxer setup, and sometimes CameraX rebind).
     * - Never run this on a socket listener thread (would starve PING handling and disconnect the Viewer).
     * - Never run this on the UI thread (risk ANR).
     */
    fun startRecording(withAudio: Boolean) {
        recordingControlExecutor.execute {
            try {
                startRecordingInternal(withAudio)
            } catch (t: Throwable) {
                Log.e(logFrom, "startRecording async failed", t)
                // Best-effort cleanup for partial start.
                try {
                    recordingRequested = false
                    streamServer?.broadcastRecording(false)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun startRecordingInternal(withAudio: Boolean) {
        Log.d(
            logFrom,
            "🔴 [RECORDING] startRecording(withAudio=$withAudio) called - customRecorder=${customRecorder != null}, imageAnalysis=${imageAnalysis != null}, serviceState=$serviceState"
        )
        if (customRecorder != null) {
            Log.w(
                logFrom, "🔴 [RECORDING] startRecording FAILED - recording already in progress"
            )
            return
        }
        // Mark recording intent immediately so camera controls can switch to recording mode
        // even before onRecordingStarted callback updates serviceState.
        recordingRequested = true

        // CRITICAL FIX: Create CustomRecorder FIRST (before file setup)
        // This is needed because bindCamera() checks customRecorder != null to decide if ImageAnalysis should be created
        // Once CustomRecorder exists, we rebind camera, which will create ImageAnalysis since customRecorder != null
        // Then we proceed with file setup and start recording

        // Create CustomRecorder placeholder (will be configured fully before start())
        // We need it to exist before bindCamera so ImageAnalysis gets created
        var tempRecorderCreated = false
        if (imageAnalysis == null || currentCamera == null) {
            Log.w(
                logFrom,
                "🔴 [RECORDING] WARNING: imageAnalysis or camera is null - creating CustomRecorder first, then rebinding camera"
            )
            Log.d(
                logFrom,
                "🔴 [RECORDING] imageAnalysis=${imageAnalysis != null}, currentCamera=${currentCamera != null}"
            )

            // CRITICAL: Determine recording dimensions before creating temporary CustomRecorder
            // This ensures the temp recorder uses correct dimensions matching Buffer/Surface mode
            val isBufferMode = VideoEncoder.shouldPreferBufferMode(
                this,
                AppSettings.isForceBufferMode(this)
            )
            val tempRecordingWidth = if (isBufferMode) {
                // Fix incorrect hardcoded landscape dims for Buffer Mode
                if (currentWidth < currentHeight) 720 else 960
            } else {
                MediaCodecConfig.alignResolution16(currentWidth, currentHeight).first
            }
            val tempRecordingHeight = if (isBufferMode) {
                // Fix incorrect hardcoded landscape dims for Buffer Mode
                if (currentWidth < currentHeight) 960 else 720
            } else {
                MediaCodecConfig.alignResolution16(currentWidth, currentHeight).second
            }

            // Create temporary CustomRecorder so bindCamera will create ImageAnalysis
            customRecorder = CustomRecorder(
                width = tempRecordingWidth,
                height = tempRecordingHeight,
                videoBitrate = currentBitrate,
                frameRate = currentFPS
            )
            tempRecorderCreated = true
            Log.d(
                logFrom,
                "🔴 [RECORDING] Temporary CustomRecorder created - will rebind camera to create ImageAnalysis"
            )

            // Force rebind camera - this will create ImageAnalysis since customRecorder != null now
            try {
                bindCamera(null, null, lastRotationDegrees ?: Surface.ROTATION_0)
                Log.d(
                    logFrom,
                    "🔴 [RECORDING] Camera rebind initiated - ImageAnalysis should be created now"
                )
            } catch (e: Exception) {
                Log.e(
                    logFrom, "🔴 [RECORDING] CRITICAL: Failed to rebind camera for recording", e
                )
                customRecorder = null
                return
            }
        }

        // Double-check after rebind attempt
        if (imageAnalysis == null) {
            Log.e(
                logFrom,
                "🔴 [RECORDING] startRecording FAILED - imageAnalysis is still null after rebind attempt"
            )
            Log.e(
                logFrom,
                "🔴 [RECORDING] Please ensure camera is bound before starting recording. Recording requires ImageAnalysis to feed frames to CustomRecorder."
            )
            if (tempRecorderCreated) {
                customRecorder = null
            }
            recordingRequested = false
            return
        }

        Log.d(logFrom, "🔴 [RECORDING] Camera is ready - proceeding with recording setup")

        // Generate unique filename based on file rotation setting
        val baseTimestamp = "cctv_${System.currentTimeMillis()}"
        val filenameBase = if (fileRotationEnabled) {
            // File rotation enabled: Use timestamp-based name, enforceStorageLimit will delete old files
            baseTimestamp
        } else {
            // File rotation disabled: Generate unique filename that doesn't exist (preserve all recordings)
            generateUniqueFilename(baseTimestamp)
        }
        var outputUri: Uri?

        // Try SAF first (if user selected a folder via Storage Access Framework)
        val safUri: Uri? = videoTreeUri?.let { treeUri ->
            try {
                val dir = DocumentFile.fromTreeUri(this, treeUri)
                if (dir == null || !dir.isDirectory) {
                    Log.w(logFrom, "SAF folder invalid; ignoring SAF and using MediaStore")
                    null
                } else {
                    enforceStorageLimitSaf(dir)
                    val file = dir.createFile("video/mp4", "$filenameBase.mp4")
                    val targetUri = file?.uri
                    if (targetUri != null) {
                        Log.d(logFrom, "🔴 [RECORDING] Created SAF file: $targetUri")
                        targetUri
                    } else {
                        Log.w(
                            logFrom, "Failed to create file in SAF folder; using MediaStore"
                        )
                        null
                    }
                }
            } catch (e: Exception) {
                Log.w(logFrom, "SAF folder access failed; using MediaStore", e)
                null
            }
        }

        // Use MediaStore if SAF failed or not available
        if (safUri == null) {
            enforceStorageLimit()
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filenameBase)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, videoRelativePath)
            }

            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                Log.d(logFrom, "🔴 [RECORDING] Created MediaStore entry: $uri")
                outputUri = uri
                // Keep file path for potential fallback (though we prefer FileDescriptor)
                // File path extraction no longer needed - we use FileDescriptor instead
                // Keep query for potential future use (logging, etc.)
                // val cursor = contentResolver.query(uri, arrayOf(MediaStore.Video.Media.DATA), null, null)
            } else {
                Log.e(logFrom, "Failed to create MediaStore entry")
                return
            }
        } else {
            outputUri = safUri
        }

        // CRITICAL FIX: Open FileDescriptor from URI and pass to MediaMuxer instead of file path
        // This resolves EEXIST errors with Scoped Storage (Android 10+)
        // Note: outputUri is guaranteed non-null at this point (assigned above with early returns for failures)
        val finalUri = outputUri

        // Open FileDescriptor from URI in read-write mode
        var fileDescriptor: android.os.ParcelFileDescriptor?
        try {
            Log.d(logFrom, "🔴 [RECORDING] Opening FileDescriptor from URI: $finalUri")
            fileDescriptor = contentResolver.openFileDescriptor(finalUri, "rw")
            if (fileDescriptor == null) {
                Log.e(
                    logFrom,
                    "🔴 [RECORDING] CRITICAL: Failed to open FileDescriptor from URI: $finalUri"
                )
                return
            }
            Log.d(
                logFrom, "🔴 [RECORDING] Successfully opened FileDescriptor: fd=${fileDescriptor.fd}"
            )
        } catch (e: Exception) {
            Log.e(
                logFrom,
                "🔴 [RECORDING] CRITICAL: Exception opening FileDescriptor from URI: $finalUri",
                e
            )
            return
        }

        val finalFileDescriptor = fileDescriptor

        try {
            // CRITICAL FIX: Improved resolution selection for better video quality and full-screen coverage
            // Strategy:
            // 1. Try Surface Mode first (higher resolution, better quality) if device supports it
            // 2. Fall back to Buffer Mode only if device requires it (problematic devices)
            // 3. For Buffer Mode, use higher resolution if possible (check ImageAnalysis capabilities)
            val forceBufferMode = AppSettings.isForceBufferMode(this)
            val isBufferMode = VideoEncoder.shouldPreferBufferMode(this, forceBufferMode)

            val recordingWidth: Int
            val recordingHeight: Int

            if (isBufferMode) {
                // Buffer Mode: Device requires Buffer Mode (problematic device or forced)
                // IMPORTANT (FOV/geometry consistency):
                // On some devices (e.g., Samsung M30s), ImageAnalysis may deliver sizes like 720x540. If we "align down"
                // (540 -> 528) for recording, we introduce a subtle geometry mismatch vs the stream (which is stable 3:4).
                //
                // To keep recorded output consistent with streamed output, record at the same stable 3:4 resolution
                // that streaming uses in ByteBuffer mode.
                if (currentWidth < currentHeight) { // Portrait
                    recordingWidth = 720
                    recordingHeight = 960
                } else { // Landscape
                    recordingWidth = 960
                    recordingHeight = 720
                }
                Log.d(
                    logFrom,
                    "🔴 [RECORDING] Buffer Mode - Using stream-aligned recording resolution ${recordingWidth}x${recordingHeight} (3:4) for FOV consistency"
                )
            } else {
                // Surface Mode: Use full camera resolution (better quality, taller for portrait screens)
                // Align dimensions to 16 to match MediaCodecConfig expectations
                // For 1080x1440 input, this becomes 1072x1440 (1080/16*16 = 1072)
                // This provides much better quality and fills taller screens
                val (alignedW, alignedH) = MediaCodecConfig.alignResolution16(
                    currentWidth, currentHeight
                )
                recordingWidth = alignedW
                recordingHeight = alignedH
                Log.d(
                    logFrom,
                    "🔴 [RECORDING] Surface Mode - Using recording resolution ${recordingWidth}x${recordingHeight} (aligned from ${currentWidth}x${currentHeight})"
                )
                Log.d(
                    logFrom,
                    "🔴 [RECORDING] Surface Mode provides better quality and taller resolution for full-screen coverage"
                )
            }

            // Create CustomRecorder (or recreate if temp recorder was created with wrong dimensions)
            if (!tempRecorderCreated) {
                customRecorder = CustomRecorder(
                    width = recordingWidth,
                    height = recordingHeight,
                    videoBitrate = currentBitrate,
                    frameRate = currentFPS
                )
                Log.d(
                    logFrom,
                    "🔴 [RECORDING] CustomRecorder created with dimensions: ${recordingWidth}x${recordingHeight}"
                )
            } else {
                // CRITICAL FIX: Always recreate CustomRecorder if tempRecorderCreated is true
                // The temporary CustomRecorder was created with tempRecordingWidth/Height,
                // but we need recordingWidth/Height for the actual recording
                // Since CustomRecorder dimensions are private, we can't verify if they match,
                // so we always recreate to ensure correct dimensions
                val existingRecorder = customRecorder
                val tempWidth = if (isBufferMode) {
                    if (currentWidth < currentHeight) 720 else 960
                } else {
                    MediaCodecConfig.alignResolution16(currentWidth, currentHeight).first
                }
                val tempHeight = if (isBufferMode) {
                    if (currentWidth < currentHeight) 960 else 720
                } else {
                    MediaCodecConfig.alignResolution16(currentWidth, currentHeight).second
                }

                // Check if dimensions might be different (temp vs final)
                val mightMismatch = (tempWidth != recordingWidth || tempHeight != recordingHeight)

                if (mightMismatch || existingRecorder == null) {
                    Log.w(
                        logFrom,
                        "🔴 [RECORDING] CustomRecorder was pre-created with temp dimensions - recreating with correct dimensions"
                    )
                    Log.w(
                        logFrom,
                        "🔴 [RECORDING] Temp dimensions: ${tempWidth}x${tempHeight}, Final: ${recordingWidth}x${recordingHeight}"
                    )

                    // Create new CustomRecorder with correct dimensions
                    customRecorder = CustomRecorder(
                        width = recordingWidth,
                        height = recordingHeight,
                        videoBitrate = currentBitrate,
                        frameRate = currentFPS
                    )
                    Log.d(
                        logFrom,
                        "🔴 [RECORDING] CustomRecorder recreated with dimensions: ${recordingWidth}x${recordingHeight}"
                    )
                } else {
                    Log.d(
                        logFrom,
                        "🔴 [RECORDING] CustomRecorder temp dimensions match final - reusing existing instance"
                    )
                }
            }

            // Configure CustomRecorder callbacks
            customRecorder!!.apply {
                onRecordingStarted = {
                    Log.d(
                        logFrom,
                        "🔴 [RECORDING] CustomRecorder.onRecordingStarted callback invoked - broadcasting RECORDING|active=true to viewers"
                    )
                    recordingWithAudio = withAudio
                    serviceState = ServiceCaptureState.RECORDING
                    persistState()
                    streamServer?.broadcastRecording(true)
                    try {
                        updateIdleState("recording_started")
                    } catch (_: Throwable) {
                    }
                    Log.d(
                        logFrom,
                        "🔴 [RECORDING] Recording state broadcasted: RECORDING|active=true sent to all viewers"
                    )
                    // Apply recording-optimized camera controls now that recording is confirmed.
                    try {
                        applyCameraControls()
                    } catch (_: Throwable) {
                    }
                }
                onRecordingStopped = { error ->
                    if (error != null) {
                        Log.e(logFrom, "CustomRecorder stopped with error", error)
                    }
                    recordingWithAudio = false
                    serviceState = ServiceCaptureState.PREVIEW
                    recordingRequested = false
                    persistState()
                    streamServer?.broadcastRecording(false)
                    customRecorder = null
                    try {
                        updateIdleState("recording_stopped")
                    } catch (_: Throwable) {
                    }
                    // Re-apply non-recording camera controls after recording ends.
                    try {
                        applyCameraControls()
                    } catch (_: Throwable) {
                    }
                }
            }

            // Start recording using FileDescriptor (CRITICAL FIX for EEXIST errors)
            // CRITICAL: Ensure we have at least one analysis frame before choosing orientation hint.
            // If we start with hint=0 while frames are rotMeta=90, CustomRecorder will rotate pixels in Kotlin
            // (~80-250ms per frame) and playback becomes unsmooth.
            val waitStartMs = android.os.SystemClock.uptimeMillis()
            while (android.os.SystemClock.uptimeMillis() - waitStartMs < 350L) {
                if (lastAnalysisFrameUptimeMs > 0L) break
                try {
                    Thread.sleep(10)
                } catch (_: Throwable) {
                    break
                }
            }
            val orientationHint = try {
                // IMPORTANT (match stream behavior):
                // In Buffer Mode we rotate pixels in CustomRecorder (like the stream encoder) and keep container hint at 0.
                // In Surface Mode, keep using container rotation metadata (cheaper than rotating pixels).
                if (isBufferMode) {
                    0
                } else {
                    val deg = lastAnalysisRotationDegrees
                    when (((deg % 360) + 360) % 360) {
                        90 -> 90
                        180 -> 180
                        270 -> 270
                        else -> 0
                    }
                }
            } catch (_: Throwable) {
                0
            }
            Log.d(
                logFrom,
                "🔴 [RECORDING] Orientation hint computed: hint=$orientationHint from analysisRot=$lastAnalysisRotationDegrees " + "analysisSeen=${lastAnalysisFrameUptimeMs > 0L} waitMs=${android.os.SystemClock.uptimeMillis() - waitStartMs}"
            )
            Log.d(
                logFrom,
                "🔴 [RECORDING] Calling customRecorder.start() with FileDescriptor: fd=${finalFileDescriptor.fd}, uri=$finalUri, withAudio=$withAudio, orientationHint=$orientationHint"
            )
            // Apply recording camera controls ASAP (before first frames) for max smoothness.
            try {
                applyCameraControls()
            } catch (_: Throwable) {
            }
            val started = customRecorder!!.start(finalFileDescriptor, withAudio, orientationHint)
            if (!started) {
                Log.e(
                    logFrom,
                    "🔴 [RECORDING] CRITICAL: customRecorder.start() returned FALSE - recording failed to start"
                )
                Log.e(
                    logFrom,
                    "🔴 [RECORDING] Check CustomRecorder logs for video codec, audio codec, or MediaMuxer setup failures"
                )
                // Close FileDescriptor on failure
                try {
                    finalFileDescriptor.close()
                } catch (e: Exception) {
                    Log.w(logFrom, "Error closing FileDescriptor after start() failure", e)
                }
                customRecorder = null
                recordingRequested = false
                return
            }

            Log.d(
                logFrom,
                "🔴 [RECORDING] customRecorder.start() returned TRUE - waiting for onRecordingStarted callback"
            )
            Log.d(
                logFrom,
                "Recording started: URI=$finalUri, FileDescriptor=fd=${finalFileDescriptor.fd}, withAudio=$withAudio"
            )
        } catch (e: Throwable) {
            Log.e(logFrom, "startRecording failed", e)
            // Close FileDescriptor on exception
            // Note: fileDescriptor is guaranteed to be non-null here (assigned before try block, with null checks/early returns)
            try {
                fileDescriptor.close()
            } catch (closeEx: Exception) {
                Log.w(
                    logFrom, "Error closing FileDescriptor after startRecording exception", closeEx
                )
            }
            customRecorder?.stop()
            customRecorder = null
            recordingWithAudio = false
            serviceState = ServiceCaptureState.PREVIEW
            recordingRequested = false
            persistState()
            streamServer?.broadcastRecording(false)
        }
    }

    // deleted startTalkbackCapture / stopTalkback

    private fun stopTalkback() {
        // Release TalkBack playback track (Viewer->Primary audio)
        releaseTalkbackTrack()
    }

    fun setAeComp(comp: Float) {
        aeCompValue = comp
        applyCameraControls()
    }

    fun setTorch(enabled: Boolean) {
        torchEnabled = enabled
        applyCameraControls()
    }

    fun setZoom(ratio: Float) {
        val cam = currentCamera ?: return
        val zoomState = cam.cameraInfo.zoomState.value ?: return
        val clamped = ratio.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        try {
            Log.d(
                logFrom,
                "🟣 [FOVDBG][ZOOM_APPLY] Applying zoomRatio=$clamped (requested=$ratio, range=${zoomState.minZoomRatio}..${zoomState.maxZoomRatio})"
            )
        } catch (_: Throwable) {
        }
        cam.cameraControl.setZoomRatio(clamped)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyCameraControls() {
        val cam = currentCamera ?: return
        // CRITICAL: Consolidate Camera2 request options into a single request.
        // Why: multiple setCaptureRequestOptions() calls can override each other on some OEMs,
        // which can accidentally drop the AE FPS range (leading to 16.7/25fps capture even when [30,30] was set).
        val recordingActive =
            recordingRequested || (serviceState == ServiceCaptureState.RECORDING) || (customRecorder != null)
        // Camera FPS is a *ceiling*, not a frequently-changing knob.
        // We compute a stable fixed ceiling (30 -> 24 -> 15) and keep it unless we explicitly downgrade later.
        val targetFps = ensureCameraFixedFpsCeiling(cam, currentWidth, currentHeight)

        try {
            val builder = CaptureRequestOptions.Builder()

            // --- AE FPS range ---
            val ranges = try {
                Camera2CameraInfo.from(cam.cameraInfo)
                    .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            } catch (_: Throwable) {
                null
            }

            // Prefer FIXED FPS ranges for stability (avoid variable ranges like 15–30).
            // Fallback to a tight variable range only if fixed is unavailable.
            val chosenFpsRange: Range<Int>? = try {
                cameraFpsRange ?: computeBestFpsRange(ranges, targetFps)
            } catch (_: Throwable) {
                null
            }

            if (chosenFpsRange != null) {
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, chosenFpsRange
                )
            }

            // --- Anti-banding ---
            // NOTE: If you consistently see ~25fps even with [30,30], it's often due to 50Hz anti-banding constraints.
            // For recording, we prefer smooth motion, so disable anti-banding (may introduce flicker under some lights).
            // For preview/streaming, keep AUTO to reduce flicker.
            try {
                val mode = if (recordingActive) {
                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF
                } else {
                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
                }
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, mode
                )
            } catch (_: Throwable) {
            }

            // --- Stabilization off (reduce analysis/preview mismatch + extra crop) ---
            try {
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                )
                builder.setCaptureRequestOption(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                )
            } catch (_: Throwable) {
            }

            // --- Distortion correction off (can introduce ISP crop) ---
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    builder.setCaptureRequestOption(
                        CaptureRequest.DISTORTION_CORRECTION_MODE,
                        CaptureRequest.DISTORTION_CORRECTION_MODE_OFF
                    )
                } catch (_: Throwable) {
                }
            }

            // --- Force full active-array crop ---
            val activeArray = try {
                Camera2CameraInfo.from(cam.cameraInfo)
                    .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            } catch (_: Throwable) {
                null
            }
            if (activeArray != null) {
                try {
                    builder.setCaptureRequestOption(
                        CaptureRequest.SCALER_CROP_REGION, activeArray
                    )
                } catch (_: Throwable) {
                }
            }

            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(builder.build())

            Log.d(
                logFrom,
                "Applied Camera2 controls: recordingActive=$recordingActive targetFpsCeiling=$targetFps " + "fpsRange=$chosenFpsRange antibanding=" + (if (recordingActive) "OFF" else "AUTO") + " activeArray=" + (activeArray != null)
            )
        } catch (t: Throwable) {
            Log.w(logFrom, "Failed to apply consolidated Camera2 controls (ignored)", t)
        }
        cam.cameraInfo.exposureState.let { exp ->
            val range = exp.exposureCompensationRange
            val target = aeCompValue.toInt().coerceIn(range.lower, range.upper)
            try {
                cam.cameraControl.setExposureCompensationIndex(target)
                Log.d(
                    logFrom, "Set exposure comp to $target (range ${range.lower}..${range.upper})"
                )
            } catch (t: Throwable) {
                Log.e(logFrom, "Failed to set exposure comp", t)
            }
        }
        try {
            cam.cameraControl.enableTorch(torchEnabled)
        } catch (_: Exception) {
        }
        // NOTE: Stabilization/distortion/crop requests are applied above in one consolidated request.
        try {
            cam.cameraControl.setZoomRatio(1.0f)
        } catch (_: Throwable) {
        }
    }

    /**
     * Compute (and cache) a stable Camera2 fixed-FPS ceiling for this session.
     *
     * Intent:
     * - Keep camera delivery stable (sensor/AE stability).
     * - Let encoder adapt via bitrate + frame dropping.
     *
     * Algorithm (deterministic):
     * - Read supported AE FPS ranges.
     * - Prefer fixed ranges only: 30 -> 24 -> 15.
     * - Clamp by a conservative "max FPS for resolution" heuristic.
     *
     * Fallback:
     * - If anything fails, return previous ceiling (default 30).
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun ensureCameraFixedFpsCeiling(
        cam: androidx.camera.core.Camera, w: Int, h: Int
    ): Int {
        return try {
            // Avoid recomputing unless resolution changed.
            if (cameraFpsComputedForW == w && cameraFpsComputedForH == h && cameraFpsRange != null) {
                return cameraFpsCeiling
            }
            cameraFpsComputedForW = w
            cameraFpsComputedForH = h

            val ranges = runCatching {
                Camera2CameraInfo.from(cam.cameraInfo)
                    .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            }.getOrNull()

            val maxAllowed = maxFpsForResolution(w, h)
            val fixedFps =
                (ranges ?: emptyArray()).filter { it.lower == it.upper }.map { it.upper }.distinct()
                    .sortedDescending()

            val chosenFps = chooseStartupFps(fixedFps, maxAllowed)
            val fixedRange = ranges?.firstOrNull { it.lower == chosenFps && it.upper == chosenFps }

            if (fixedRange != null) {
                cameraFpsCeiling = chosenFps
                cameraFpsRange = fixedRange
                Log.d(
                    logFrom,
                    "🔵 [CAMERA FPS] Chosen fixed FPS ceiling: ${chosenFps}fps using range=$fixedRange (maxAllowed=$maxAllowed, res=${w}x${h}, fixedFps=$fixedFps)"
                )
                chosenFps
            } else {
                // Fixed not available; fall back to a tight variable range containing chosenFps, else keep prior.
                cameraFpsRange = computeBestFpsRange(ranges, chosenFps)
                Log.w(
                    logFrom,
                    "⚠️ [CAMERA FPS] Fixed ${chosenFps}fps not available. Using fallback fpsRange=${cameraFpsRange} (res=${w}x${h})"
                )
                cameraFpsCeiling
            }
        } catch (t: Throwable) {
            Log.w(
                logFrom,
                "⚠️ [CAMERA FPS] Failed to compute fixed FPS ceiling; keeping existing=${cameraFpsCeiling}",
                t
            )
            cameraFpsCeiling
        }
    }

    /**
     * Conservative resolution->maxFPS heuristic (OEM-friendly).
     *
     * Note:
     * We don't attempt to exhaustively validate resolution+fps combinations here because CameraX abstracts
     * the full Camera2 StreamConfigurationMap negotiation. We keep this simple and stable.
     */
    private fun maxFpsForResolution(width: Int, height: Int): Int {
        return when {
            width >= 1920 || height >= 1080 -> 30
            width >= 1280 || height >= 720 -> 30
            else -> 60
        }
    }

    /**
     * Choose startup FPS from fixed fps list with preference 30 -> 24 -> 15.
     * Falls back to the highest fixed FPS <= maxAllowed, else lowest available.
     */
    private fun chooseStartupFps(fixedFps: List<Int>, maxAllowedFps: Int): Int {
        // Apply governor cap (downgrade-only) in addition to resolution max.
        val cap = cameraFpsGovernorCap.coerceIn(15, 60)
        val allowed = fixedFps.filter { it <= maxAllowedFps && it <= cap }
        return allowed.firstOrNull { it == 30 } ?: allowed.firstOrNull { it == 24 }
        ?: allowed.firstOrNull { it == 15 } ?: allowed.firstOrNull() ?: fixedFps.firstOrNull()
        ?: cameraFpsCeiling
    }

    /**
     * Best-effort range selection when fixed fps is not available.
     * Prefers tight ranges and then ranges that include desiredFps.
     */
    private fun computeBestFpsRange(
        ranges: Array<Range<Int>>?, desiredFps: Int
    ): Range<Int>? {
        return try {
            val rs = ranges ?: return null
            // Prefer fixed exact.
            rs.firstOrNull { it.lower == desiredFps && it.upper == desiredFps }
                ?: rs.filter { it.upper >= desiredFps && it.lower <= desiredFps }.minWithOrNull(
                    compareBy(
                        { it.upper - it.lower },
                        { kotlin.math.abs(it.upper - desiredFps) })
                ) ?: rs.filter { it.upper >= desiredFps }
                    .minWithOrNull(compareBy({ it.upper - desiredFps }, { it.lower }))
        } catch (_: Throwable) {
            null
        }
    }

    private fun ensureTalkbackTrack(): android.media.AudioTrack {
        val existing = talkbackTrack
        if (existing != null) {
            // Check if sample rate matches (should be 48kHz to match incoming audio)
            val existingRate = existing.sampleRate
            if (existingRate == talkbackPlaybackSampleRate) {
                return existing
            } else {
                // Sample rate changed - recreate track
                Log.d(
                    logFrom,
                    "TalkBack AudioTrack rate changed: ${existingRate}Hz -> ${talkbackPlaybackSampleRate}Hz, recreating"
                )
                releaseTalkbackTrack()
            }
        }
        val minBuf = android.media.AudioTrack.getMinBufferSize(
            talkbackPlaybackSampleRate, audioChannelOut, audioFormat
        ).coerceAtLeast(talkbackBytesPerFrame * 2)
        val t = android.media.AudioTrack.Builder().setAudioAttributes(
            // Talkback is a "voice call"-like path; using VOICE_COMMUNICATION typically yields better loudness
            // and routing characteristics than USAGE_MEDIA on many devices.
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        ).setAudioFormat(
            AudioFormat.Builder().setEncoding(audioFormat).setSampleRate(talkbackPlaybackSampleRate)
                .setChannelMask(audioChannelOut).build()
        ).setTransferMode(android.media.AudioTrack.MODE_STREAM).setBufferSizeInBytes(minBuf).build()
        t.play()
        try {
            // Ensure we are not inadvertently attenuated by a device default.
            // Stream volume is still controlled by the system, but per-track volume should be max.
            t.setVolume(1.0f)
        } catch (_: Throwable) {
        }
        talkbackTrack = t
        return t
    }

    /* ===============================
     * Camera FPS governor (downgrade-only)
     * =============================== */

    /**
     * Start the downgrade-only camera FPS governor.
     *
     * Behavior:
     * - Runs periodically while capture is active (PREVIEW/RECORDING).
     * - Downgrades camera FPS ceiling only as a last resort.
     * - Never increases camera FPS automatically.
     *
     * Grace period (requested):
     * - After each downgrade, ignore all metrics for ~5s to avoid false double-downgrades
     *   while the camera AE + encoder pipeline stabilizes.
     */
    private fun startCameraFpsGovernor() {
        if (cameraFpsGovernorRunning) return
        cameraFpsGovernorRunning = true
        Log.d(
            logFrom,
            "🔵 [CAMERA FPS GOV] Started (tick=${cameraFpsGovernorTickMs}ms, cooldown=${cameraFpsChangeCooldownMs}ms)"
        )
        rebindHandler.removeCallbacks(cameraFpsGovernorRunnable)
        rebindHandler.postDelayed(cameraFpsGovernorRunnable, cameraFpsGovernorTickMs)
    }

    /**
     * Stop the camera FPS governor.
     * Safe to call multiple times.
     */
    private fun stopCameraFpsGovernor(reason: String) {
        if (!cameraFpsGovernorRunning) return
        cameraFpsGovernorRunning = false
        rebindHandler.removeCallbacks(cameraFpsGovernorRunnable)
        Log.d(logFrom, "🔵 [CAMERA FPS GOV] Stopped (reason=$reason)")
    }

    /**
     * Periodic governor evaluation.
     *
     * Inputs (simple + robust):
     * - Thermal severity (severe/critical/emergency)
     * - Sustained backpressure streak (proxy for network/receiver stress)
     * - Encoder already at minimum bitrate for long enough
     *
     * Output:
     * - Downgrade camera FPS cap: 30 -> 24 -> 15
     */
    private fun evaluateCameraFpsGovernorTick() {
        // Only meaningful while capture is active.
        if (serviceState == ServiceCaptureState.IDLE) return
        val cam = currentCamera ?: return

        val nowUptime = android.os.SystemClock.uptimeMillis()

        // Grace period after downgrade: ignore metrics.
        if (nowUptime < cameraFpsIgnoreMetricsUntilUptimeMs) {
            return
        }

        // Track bitrate floor stability (robust even if bitrate changes via different code paths).
        updateBitrateFloorTracking(nowUptime, currentBitrate)

        val isThermalSevere = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            lastThermalStatus == PowerManager.THERMAL_STATUS_SEVERE || lastThermalStatus == PowerManager.THERMAL_STATUS_CRITICAL || lastThermalStatus == PowerManager.THERMAL_STATUS_EMERGENCY
        } else {
            false
        }

        val atMinBitrateForMs =
            if (atMinBitrateSinceUptimeMs > 0L) (nowUptime - atMinBitrateSinceUptimeMs) else 0L

        val backpressureStreakMs =
            if (backpressureStreakStartUptimeMs > 0L) (nowUptime - backpressureStreakStartUptimeMs) else 0L

        // Sustained backpressure = multiple events in a short window for long enough.
        val sustainedBackpressure =
            backpressureStreakStartUptimeMs > 0L && backpressureEventCountInStreak >= 3 && backpressureStreakMs >= 10_000L && (nowUptime - lastBackpressureUptimeMs) <= 6_000L

        val cooldownOk = (nowUptime - cameraFpsLastChangeUptimeMs) >= cameraFpsChangeCooldownMs

        // Gate: only downgrade if we're not already at minimum cap.
        val currentCap = cameraFpsGovernorCap
        if (currentCap <= 15) return

        // Decision: downgrade only when really needed.
        val shouldDowngrade =
            isThermalSevere || (atMinBitrateForMs >= 10_000L && sustainedBackpressure)

        if (!shouldDowngrade || !cooldownOk) return

        val nextCap = if (currentCap > 24) 24 else 15

        // Apply downgrade:
        // - Update governor cap
        // - Force recalculation of fixed fps selection (clear cache)
        // - Apply Camera2 controls immediately (best effort)
        cameraFpsGovernorCap = nextCap
        cameraFpsLastChangeUptimeMs = nowUptime
        cameraFpsIgnoreMetricsUntilUptimeMs = nowUptime + 5_000L // grace period requested

        // Reset caches so ensureCameraFixedFpsCeiling re-selects under the new cap.
        cameraFpsRange = null
        cameraFpsComputedForW = 0
        cameraFpsComputedForH = 0

        // Reset backpressure streak so we don't immediately downgrade again after grace expires.
        backpressureStreakStartUptimeMs = 0L
        backpressureEventCountInStreak = 0

        Log.w(
            logFrom,
            "🟠 [CAMERA FPS GOV] Downgrading camera FPS cap: $currentCap -> $nextCap " + "(thermal=$lastThermalStatus atMinBitrateForMs=${atMinBitrateForMs}ms sustainedBackpressure=$sustainedBackpressure " + "bpStreakMs=$backpressureStreakMs bpCount=$backpressureEventCountInStreak measuredFps=${
                String.format(
                    "%.1f", lastMeasuredAnalysisFps
                )
            }) " + "grace=5000ms cooldown=${cameraFpsChangeCooldownMs}ms"
        )

        try {
            // Apply immediately to reduce load as soon as possible.
            // Note: Some OEMs may not switch FPS without a full session rebuild; our applyCameraControls() is best-effort
            // and safe. If the OEM ignores it, delivered-FPS logs will make that visible.
            applyCameraControls()
            // Also recompute ceiling/range cache deterministically.
            ensureCameraFixedFpsCeiling(cam, currentWidth, currentHeight)
        } catch (t: Throwable) {
            Log.w(
                logFrom, "⚠️ [CAMERA FPS GOV] Failed to apply FPS downgrade (non-fatal)", t
            )
        }
    }

    /**
     * Track whether we have been at minimum bitrate for a sustained duration.
     * This is used as a prerequisite for camera-FPS downgrades (last resort).
     */
    private fun updateBitrateFloorTracking(nowUptimeMs: Long, bitrate: Int) {
        try {
            val atMin = bitrate <= (MIN_BITRATE_BPS + 1_000)
            if (atMin) {
                if (atMinBitrateSinceUptimeMs <= 0L) {
                    atMinBitrateSinceUptimeMs = nowUptimeMs
                }
            } else {
                atMinBitrateSinceUptimeMs = 0L
            }
        } catch (_: Throwable) {
        }
    }

    private fun releaseTalkbackTrack() {
        val t = talkbackTrack ?: return
        try {
            t.pause()
        } catch (_: Throwable) {
        }
        try {
            t.flush()
        } catch (_: Throwable) {
        }
        try {
            t.stop()
        } catch (_: Throwable) {
        }
        try {
            t.release()
        } catch (_: Throwable) {
        }
        talkbackTrack = null
    }

    private fun playTalkback(pcm: ByteArray) {
        try {
            if (!enableTalkback) return
            // Suppress low-level noise so the Primary device doesn't play "hiss" or random junk
            // when PTT is idle or framing gets corrupted.
            // PCM 16-bit little-endian.
            var sumSq = 0.0
            var samples = 0
            var i = 0
            while (i + 1 < pcm.size) {
                val s = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
                val v = s.toShort().toInt()
                sumSq += (v * v).toDouble()
                samples++
                i += 2
            }
            val rms = if (samples == 0) 0.0 else kotlin.math.sqrt(sumSq / samples)
            // Keep a light gate, but don't cut normal speech.
            // Viewer talkback can be quiet on some devices; a high gate makes it sound faint/intermittent.
            // Lower gate slightly; we still suppress very low-level hiss.
            if (rms < 120.0) return

            // Adaptive gain:
            // Target a moderate speech RMS; clamp to avoid distortion.
            val desiredRms = 2800.0
            val gain = if (rms <= 1.0) 1.0 else (desiredRms / rms).coerceIn(1.0, 4.0)

            // Soft limiter to avoid harsh clipping when applying large gain.
            // Formula: y = x / (1 + |x|/limit). This is cheap and stable.
            fun softLimit(x: Int, limit: Int = 28000): Int {
                val ax = kotlin.math.abs(x)
                if (ax <= limit) return x
                val y = (x.toDouble() / (1.0 + (ax.toDouble() / limit.toDouble()))).toInt()
                return y.coerceIn(-32768, 32767)
            }

            var clipCount = 0
            var j = 0
            while (j + 1 < pcm.size) {
                val s = (pcm[j].toInt() and 0xFF) or (pcm[j + 1].toInt() shl 8)
                val v = s.toShort().toInt()
                var out = (v.toDouble() * gain).toInt()
                if (out > 32767 || out < -32768) clipCount++
                out = softLimit(out)
                pcm[j] = (out and 0xFF).toByte()
                pcm[j + 1] = ((out shr 8) and 0xFF).toByte()
                j += 2
            }

            val track = ensureTalkbackTrack()
            // Rate-limited diagnostics (helps field-debug quiet devices without spamming logs).
            try {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastTalkbackDbgUptimeMs > 2_000L) {
                    lastTalkbackDbgUptimeMs = now
                    Log.d(
                        logFrom,
                        "TalkBack play rms=${"%.1f".format(rms)} gain=${"%.2f".format(gain)} clippedSamples=$clipCount bytes=${pcm.size}"
                    )
                }
            } catch (_: Throwable) {
            }
            track.write(pcm, 0, pcm.size)
        } catch (t: Throwable) {
            Log.e(logFrom, "Talkback playback failed", t)
        }
    }

    /* ---------------- Audio Streaming (Camera -> Viewer) ---------------- */
    /**
     * Start streaming audio using AudioSourceEngine
     * This registers StreamAudioSender as a listener to receive PCM data and broadcast to viewers.
     */
    private fun startStreamingAudio() {
        if (streamAudioSender != null) {
            Log.d(logFrom, "Streaming audio already started")
            return
        }

        val server = streamServer ?: run {
            Log.w(logFrom, "Cannot start video audio sender: streamServer is null")
            return
        }

        streamAudioSender = StreamAudioSender(server)
        audioSourceEngine.registerStreamingListener(streamAudioSender!!)
        isStreamingAudio = true
        Log.d(logFrom, "Streaming audio started via AudioSourceEngine")
    }

    /**
     * Stop streaming audio
     * This unregisters StreamAudioSender from AudioSourceEngine.
     */
    private fun stopStreamingAudio() {
        streamAudioSender?.let {
            audioSourceEngine.unregisterStreamingListener(it)
            it.release() // Release AAC encoder resources
            streamAudioSender = null
            isStreamingAudio = false
            Log.d(logFrom, "Streaming audio stopped")
        }
    }

    fun stopRecording() {
        if (stopRecordingInProgress) {
            Log.w(
                logFrom, "🔴 [RECORDING] stopRecording() ignored - stop already in progress"
            )
            return
        }

        stopRecordingInProgress = true
        Log.d(
            logFrom,
            "🔴 [RECORDING] stopRecording() scheduled - customRecorder=${customRecorder != null}"
        )

        recordingControlExecutor.execute {
            try {
                Log.d(logFrom, "🔴 [RECORDING] stopRecording() running on background thread")
                try {
                    customRecorder?.stop()
                    Log.d(logFrom, "🔴 [RECORDING] customRecorder.stop() completed")
                } catch (e: Throwable) {
                    Log.e(
                        logFrom, "🔴 [RECORDING] CRITICAL: customRecorder.stop() failed", e
                    )
                    // Continue with cleanup even if stop() failed to prevent state inconsistencies
                }
            } finally {
                // Always clean up service state regardless of customRecorder.stop() result
                customRecorder = null
                recordingWithAudio = false

                serviceState = ServiceCaptureState.PREVIEW
                persistState()
                try {
                    streamServer?.broadcastRecording(false)
                } catch (t: Throwable) {
                    Log.w(logFrom, "🔴 [RECORDING] Error broadcasting recording=false", t)
                }

                stopRecordingInProgress = false
                try {
                    updateIdleState("stop_recording")
                } catch (_: Throwable) {
                }
                Log.d(
                    logFrom, "🔴 [RECORDING] stopRecording() completed - state reset to PREVIEW"
                )
            }
        }
    }

    /**
     * Stops the entire capture pipeline (preview/stream/encoder) and returns service to IDLE.
     * Safe to call multiple times.
     */
    fun stopCapture() {
        // IMPORTANT BEHAVIOR:
        // "Stop Capture" should stop camera/encoder/audio production, but keep the TCP StreamServer alive.
        // This allows the Viewer to remain CONNECTED (no video) and automatically resume STREAMING when
        // capture starts again, without forcing a reconnect.
        try {
            if (serviceState == ServiceCaptureState.RECORDING) {
                stopRecording()
            }
        } catch (_: Throwable) {
        }

        try {
            cameraProvider?.unbindAll()
        } catch (_: Throwable) {
        }
        currentCamera = null

        // Tell viewers "stream intentionally ended" so they can map to DISCONNECTED and distinguish from network loss.
        try {
            streamServer?.broadcastStreamStateStopped()
        } catch (_: Throwable) {
        }
        try {
            stopEncoder()
        } catch (_: Throwable) {
        }
        try {
            stopStreamingAudio()
        } catch (_: Throwable) {
        }
        try {
            customRecorder?.stop()
        } catch (_: Throwable) {
        }

        serviceState = ServiceCaptureState.IDLE
        persistState()
        try {
            streamServer?.broadcastRecording(false)
        } catch (_: Throwable) {
        }
        try {
            stopCameraFpsGovernor("stop_capture")
        } catch (_: Throwable) {
        }
        // Keep service + server alive. Foreground behavior is kept as-is for stability.
        Log.w(
            logFrom,
            "Capture stopped: camera/encoder/audio stopped; StreamServer remains active for viewers (CONNECTED/no video)."
        )
    }

    /*Encoding*/ private fun startEncoder() {
        if (videoEncoder != null) return

        val forceBufferMode = AppSettings.isForceBufferMode(this) || forceBufferModeDueToCameraXCombo

        // Detect if Buffer Mode will be active (manual override or problematic device)
        val willUseBufferMode = VideoEncoder.shouldPreferBufferMode(this, forceBufferMode)

        // For Buffer Mode: Match encoder resolution to the stream orientation.
        //
        // IMPORTANT:
        // - Our StreamServer/Viewer negotiation expects portrait to be 720x960 and landscape 960x720 (4:3).
        // - If we accidentally start the encoder at 960x720 while telling the Viewer 720x960 (or vice-versa),
        //   the H.264 SPS dimensions won't match the MediaFormat config on the Viewer and it may never render
        //   (UI stuck at "Starting stream").
        //
        // Therefore Buffer Mode must be orientation-correct.
        val encoderWidth: Int
        val encoderHeight: Int

        if (willUseBufferMode) {
            // Buffer Mode: use 4:3 resolution aligned to 16.
            // Portrait: 720x960, Landscape: 960x720 (both are 16-aligned).
            val portrait = currentWidth < currentHeight
            encoderWidth = if (portrait) 720 else 960
            encoderHeight = if (portrait) 960 else 720
            Log.d(
                logFrom,
                "🔵 [RESOLUTION] Buffer Mode detected - Using encoder resolution ${encoderWidth}x${encoderHeight} (portrait=$portrait) to match negotiated stream orientation"
            )
        } else {
            // Surface Mode: Use high-resolution encoder (aligned to 16)
            val (alignedW, alignedH) = MediaCodecConfig.alignResolution16(
                currentWidth, currentHeight
            )
            encoderWidth = alignedW
            encoderHeight = alignedH
            Log.d(
                logFrom,
                "🔵 [RESOLUTION] Surface Mode - Using encoder resolution ${encoderWidth}x${encoderHeight} (aligned from ${currentWidth}x${currentHeight})"
            )
        }

        videoEncoder = VideoEncoder(
            width = encoderWidth,
            height = encoderHeight,
            bitrate = currentBitrate,
            frameRate = currentFPS,
            iFrameInterval = 1,
            context = this,
            forceBufferMode = forceBufferMode
        ) { _, _, _ ->
            // Callback not used - frameListener handles it
        }
        videoEncoder?.setCodecConfigListener { sps, pps ->
            streamServer?.broadcastCsd(sps, pps)
        }
        videoEncoder?.setEncodedFrameListener(object : EncodedFrameListener {
            override fun onEncodedFrame(frame: EncodedFrame) {
                try {
                    Log.d(
                        logFrom,
                        "🔵 [FRAME FLOW] frameListener.onEncodedFrame() called: size=${frame.data.size}, isKeyFrame=${frame.isKeyFrame}, streamServer=${streamServer != null}"
                    )
                    streamServer?.enqueueFrame(frame)
                } catch (t: Throwable) {
                    Log.e(
                        logFrom, "🔴 [FRAME FLOW] frameListener.onEncodedFrame() failed", t
                    )
                }
            }
        })
        Log.d(logFrom, "SetEncodedFrameListener initialized for sendFrame")

        videoEncoder?.start()
        val encoderMode = synchronized(encoderLock) {
            videoEncoder?.useSurfaceInput?.let { if (it) "Surface input mode" else "ByteBuffer input mode" }
                ?: "Unknown"
        }
        Log.d(
            logFrom, "Video encoder started ($encoderMode, ${encoderWidth}x${encoderHeight})"
        )

        // CRITICAL: Update StreamServer with actual encoder resolution (may differ from requested in Buffer mode)
        // This ensures STREAM_ACCEPTED uses encoder dimensions (960x720), not requested (1080x1440)
        synchronized(encoderLock) {
            videoEncoder?.let { encoder ->
                streamServer?.updateActiveConfigWithEncoderResolution(
                    encoder.getWidth(), encoder.getHeight()
                )
            }
        }

        videoEncoder?.requestSyncFrame()
    }

    private fun stopEncoder() {
        stopBitrateRecoveryGovernor()
        videoEncoder?.stop()
        videoEncoder = null

        Log.d(logFrom, "Video Encoder stopped")
    }

    /* ---------------- Remote Commands ---------------- */
    fun onCommand(command: RemoteCommand, payload: Any? = null) {
        when (command) {
            RemoteCommand.START_RECORDING -> {
                Log.d(logFrom, "Remote START_RECORDING")
                startRecording(true)
            }

            RemoteCommand.STOP_RECORDING -> {
                Log.d(logFrom, "Remote STOP_RECORDING")
                stopRecording()
            }

            RemoteCommand.REQ_KEYFRAME -> {
                synchronized(encoderLock) {
                    videoEncoder?.requestSyncFrame()
                }
            }

            RemoteCommand.BACKPRESSURE -> {
                // Track sustained backpressure as a proxy for network/receiver instability.
                // This is intentionally simple and robust: we do NOT react to a single blip.
                try {
                    val nowUptime = android.os.SystemClock.uptimeMillis()
                    val sinceLast = nowUptime - lastBackpressureUptimeMs
                    lastBackpressureUptimeMs = nowUptime
                    // If backpressure events are close together, treat it as a sustained streak.
                    if (backpressureStreakStartUptimeMs <= 0L || sinceLast > 4_000L) {
                        backpressureStreakStartUptimeMs = nowUptime
                        backpressureEventCountInStreak = 0
                    }
                    backpressureEventCountInStreak++
                } catch (_: Throwable) {
                }
                sustainedPressureCount++
                if (sustainedPressureCount >= 3) {
                    sustainedPressureCount = 0
                    lastCongestionUptimeMs =
                        android.os.SystemClock.uptimeMillis() // Mark congestion

                    val loweredBitrate =
                        (currentBitrate * 0.7).toInt().coerceAtLeast(MIN_BITRATE_BPS)
                    if (loweredBitrate < currentBitrate) {
                        Log.w(
                            logFrom, "Backpressure sustained — lowering bitrate to $loweredBitrate"
                        )
                        currentBitrate = loweredBitrate
                        // Use seamless bitrate adjustment instead of encoder reset
                        adjustBitrate(loweredBitrate)
                        // Governor input: if we reached min bitrate, start/continue the timer.
                        try {
                            updateBitrateFloorTracking(
                                android.os.SystemClock.uptimeMillis(), currentBitrate
                            )
                        } catch (_: Throwable) {
                        }
                    }
                }
            }

            RemoteCommand.ADJUST_BITRATE -> {
                val newBitrate = payload as? Int
                if (newBitrate != null && newBitrate > 0) {
                    Log.d(logFrom, "Remote ADJUST_BITRATE: $newBitrate")
                    adjustBitrate(newBitrate)
                }
            }

            RemoteCommand.PRESSURE_CLEAR -> {
                sustainedPressureCount = 0
            }

            RemoteCommand.SWITCH_CAMERA -> {
                // Disallow switching while recording to avoid corrupting the file/stream.
                if (serviceState == ServiceCaptureState.RECORDING) {
                    Log.w(logFrom, "Ignoring SWITCH_CAMERA while recording")
                    return
                }
                Log.d(logFrom, "Remote SWITCH_CAMERA (primaryUiVisible=$primaryUiVisible)")
                // Only include Preview if the Primary UI is visible (surface is expected to be valid).
                // Otherwise bind headless to avoid freezes.
                switchCamera(includePreview = primaryUiVisible)
            }

            RemoteCommand.ZOOM -> {
                val ratio = payload as? Float ?: 1.0f
                try {
                    Log.d(logFrom, "🟣 [FOVDBG][ZOOM_CMD] Received ZOOM command ratio=$ratio")
                } catch (_: Throwable) {
                }
                setZoom(ratio)
            }
        }
    }/* ---Storage rotation ----------- */


    private fun switchCamera(includePreview: Boolean) {
        val wantFront = !useFrontCamera

        // If provider is ready, validate requested camera exists.
        cameraProvider?.let { provider ->
            val has = try {
                provider.hasCamera(if (wantFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA)
            } catch (_: Throwable) {
                false
            }
            if (!has) {
                Log.w(
                    logFrom, "Requested camera not available (front=$wantFront); ignoring"
                )
                return
            }
        }

        useFrontCamera = wantFront
        // Switching camera changes cameraId/capabilities; refresh the profile so we pick a valid sensor mode.
        ensureDeviceProfile("switch_camera")
        rebindWantsPreview = includePreview
        // Torch often not supported on front camera; turn it off locally.
        torchEnabled = false

        // CameraX can fail to open the new camera if the previous one is still CLOSING.
        // Retry a few times with a short delay.
        val token = System.nanoTime()
        rebindToken = token
        scheduleRebind(token, attempt = 0)
    }

    /**
     * Requests a rebind of the currently-selected camera (no facing toggle).
     *
     * Intended use:
     * - Recover from CameraX/Camera2 camera HAL errors (device disconnects, vendor crashes, permission changes).
     *
     * Safety:
     * - Uses the same retry pipeline as switchCamera() (scheduleRebind/tryRebind).
     * - Throttled by the caller (cameraState observer), so we don't get into rapid rebind loops.
     */
    private fun requestCameraRebind(reason: String, includePreview: Boolean) {
        try {
            rebindWantsPreview = includePreview
            val token = System.nanoTime()
            rebindToken = token
            Log.w(
                logFrom,
                "🟠 [CAMERA] requestCameraRebind(reason=$reason, includePreview=$includePreview) token=$token"
            )
            scheduleRebind(token, attempt = 0)
        } catch (t: Throwable) {
            Log.e(logFrom, "🔴 [CAMERA] requestCameraRebind failed (ignored)", t)
        }
    }

    private fun scheduleRebind(token: Long, attempt: Int) {
        rebindHandler.postDelayed(
            {
                if (rebindToken != token) return@postDelayed
                tryRebind(token, attempt)
            }, if (attempt == 0) 250L else 350L
        )
    }

    private fun tryRebind(token: Long, attempt: Int) {
        // IMPORTANT:
        // - When switching from Viewer, Primary UI is often backgrounded and the PreviewView surface can be invalid.
        //   Binding a Preview use case to an invalid surface can stall camera output and freeze the stream.
        // - For remote switches we rebind "headless" (ImageAnalysis-only) by passing a null surface provider.
        val sp: Preview.SurfaceProvider? = if (rebindWantsPreview) lastSurfaceProvider else null
        try {
            cameraProvider?.unbindAll()
            bindCamera(
                surfaceProvider = sp,
                viewPort = lastViewPort,
                displayRotation = lastDisplayRotation,
                includeVideoCapture = (serviceState == ServiceCaptureState.RECORDING)
            )
            synchronized(encoderLock) { videoEncoder?.requestSyncFrame() }
            Log.d(
                logFrom, "switchCamera: rebind requested (attempt=$attempt, front=$useFrontCamera)"
            )
        } catch (t: Throwable) {
            if (attempt >= 5) {
                Log.e(
                    logFrom, "switchCamera: rebind failed after retries (front=$useFrontCamera)", t
                )
                return
            }
            Log.w(logFrom, "switchCamera: rebind failed (attempt=$attempt), retrying…", t)
            scheduleRebind(token, attempt + 1)
        }
    }

    // Called by Primary UI to switch camera locally (same behavior as viewer-triggered switch).
    fun requestSwitchCamera() {
        if (serviceState == ServiceCaptureState.RECORDING) {
            Log.w(logFrom, "Ignoring UI switch camera while recording")
            return
        }
        Log.d(logFrom, "UI SWITCH_CAMERA")
        switchCamera(includePreview = true)
    }

    fun isUsingFrontCamera(): Boolean = useFrontCamera

    /**
     * Generate a unique filename that doesn't exist yet.
     * When file rotation is disabled, we must ensure each recording creates a new file.
     * This function appends a counter to the base name if the file already exists.
     *
     * @param baseName Base filename without extension (e.g., "cctv_1234567890")
     * @return Unique filename that doesn't exist (e.g., "cctv_1234567890", "cctv_1234567890_1", etc.)
     */
    private fun generateUniqueFilename(baseName: String): String {
        // For MediaStore, we need to check if DISPLAY_NAME exists in the database
        // However, DISPLAY_NAME doesn't include extension in MediaStore, so we check without extension
        val resolver = contentResolver
        /* Disabled here because they are calculated inside the loop
        val selection =
             "${MediaStore.Video.Media.DISPLAY_NAME} = ? AND ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
         val args = arrayOf(baseName, "%$videoRelativePath%")
 */
        var counter = 0
        var uniqueName = baseName

        while (true) {
            // Check if this filename exists in MediaStore
            val querySelection =
                "${MediaStore.Video.Media.DISPLAY_NAME} = ? AND ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            val queryArgs = arrayOf(uniqueName, "%$videoRelativePath%")
            val cursor = resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID),
                querySelection,
                queryArgs,
                null
            )

            val exists = cursor?.use { it.moveToFirst() } == true
            cursor?.close()

            if (!exists) {
                // Also check if physical file exists (for fallback location)
                val videoDir = File(getExternalFilesDir(null), "Videos")
                val fallbackFile = File(videoDir, "$uniqueName.mp4")
                if (!fallbackFile.exists()) {
                    // Filename is unique
                    if (counter > 0) {
                        Log.d(
                            logFrom,
                            "🔴 [RECORDING] Generated unique filename with counter: $uniqueName (counter=$counter)"
                        )
                    }
                    return uniqueName
                }
            }

            // Filename exists, try with counter suffix
            counter++
            uniqueName = "${baseName}_$counter"
            Log.d(logFrom, "🔴 [RECORDING] Filename $baseName exists, trying: $uniqueName")
        }
    }

    private fun enforceStorageLimit() {
        try {
            if (!fileRotationEnabled) return
            val resolver = contentResolver
            val projection = arrayOf(
                MediaStore.Video.Media._ID, MediaStore.Video.Media.SIZE
            )

            val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            val args = arrayOf("%$videoRelativePath%")
            val sort = "${MediaStore.Video.Media.DATE_TAKEN} ASC"

            var totalSize = 0L
            val entries = mutableListOf<Pair<Long, Long>>()

            resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, selection, args, sort
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val size = cursor.getLong(sizeCol)
                    totalSize += size
                    entries.add(id to size)
                }
            }

            if (totalSize <= maxStorageBytes) return

            for ((id, size) in entries) {
                val uri = ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                )
                try {
                    resolver.delete(uri, null, null)
                } catch (e: SecurityException) {
                    Log.w(logFrom, "Storage cleanup delete failed (no access?): $uri", e)
                    break
                }
                totalSize -= size
                if (totalSize <= maxStorageBytes) break
            }
        } catch (e: Throwable) {
            Log.w(logFrom, "enforceStorageLimit failed (ignored)", e)
        }
    }

    private fun enforceStorageLimitSaf(dir: DocumentFile?) {
        try {
            if (!fileRotationEnabled) return
            if (dir == null || !dir.isDirectory) return
            val files = dir.listFiles().filter {
                it.isFile && (it.name?.endsWith(
                    ".mp4", ignoreCase = true
                ) == true)
            }
            var total = 0L
            val entries = files.map { f ->
                val size = runCatching { f.length() }.getOrDefault(0L)
                total += size
                Triple(f, size, runCatching { f.lastModified() }.getOrDefault(0L))
            }.sortedBy { it.third } // oldest first
            if (total <= maxStorageBytes) return
            for ((file, size, _) in entries) {
                if (total <= maxStorageBytes) break
                runCatching { file.delete() }
                total -= size
            }
        } catch (t: Throwable) {
            Log.w(logFrom, "enforceStorageLimitSaf failed (ignored)", t)
        }
    }

    private fun persistState() {
        prefs.edit { putString("service_state", serviceState.name) }
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, logFrom, NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                channel
            )
        }

        val stopIntent = Intent(this, CameraForegroundService::class.java).apply {
            action = ACTION_STOP_CAPTURE
        }
        val stopPending = PendingIntent.getService(
            this,
            1002,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_content))
            .setSmallIcon(android.R.drawable.ic_menu_camera).setOngoing(true).addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_action_stop_capture),
                stopPending
            ).build()
    }

    fun getServiceState(): ServiceCaptureState = serviceState
    fun isRecordingWithAudio(): Boolean = recordingWithAudio

    // --- Preview attach/detach coalescing (latest-wins) ---
    // Goal: Prevent rapid ON_STOP/ON_START churn from spamming CameraX graph mutations and causing ANRs.
    // Rule: CameraX graph calls still run on main thread; we just reduce frequency and collapse duplicates.
    private sealed class PreviewUiAction {
        data class Attach(
            val surfaceProvider: Preview.SurfaceProvider, val displayRotation: Int
        ) : PreviewUiAction()

        data object Detach : PreviewUiAction()
    }

    private val previewUiOpLock = Any()

    @Volatile
    private var pendingPreviewUiAction: PreviewUiAction? = null

    @Volatile
    private var previewUiOpScheduled: Boolean = false

    @Volatile
    private var previewUiOpRunning: Boolean = false

    @Volatile
    private var lastPreviewUiOpUptimeMs: Long = 0L

    private val previewUiDebounceMs: Long = 150L
    private val previewUiMinIntervalMs: Long = 250L

    private val previewUiRunner = Runnable { runCoalescedPreviewUiAction() }

    private fun requestPreviewUiAction(action: PreviewUiAction) {
        synchronized(previewUiOpLock) {
            // Latest-wins: overwrite any previous pending action.
            pendingPreviewUiAction = action
            if (!previewUiOpScheduled) {
                previewUiOpScheduled = true
                rebindHandler.removeCallbacks(previewUiRunner)
                rebindHandler.postDelayed(previewUiRunner, previewUiDebounceMs)
            }
        }
    }

    private fun runCoalescedPreviewUiAction() {
        val actionToRun: PreviewUiAction? = synchronized(previewUiOpLock) {
            previewUiOpScheduled = false
            if (previewUiOpRunning) {
                // A preview op is in-flight; reschedule to run the latest request afterwards.
                previewUiOpScheduled = true
                rebindHandler.postDelayed(previewUiRunner, previewUiDebounceMs)
                return
            }
            // Enforce a small minimum interval between CameraX graph mutations.
            val now = android.os.SystemClock.uptimeMillis()
            val sinceLast = now - lastPreviewUiOpUptimeMs
            if (sinceLast < previewUiMinIntervalMs) {
                previewUiOpScheduled = true
                rebindHandler.postDelayed(
                    previewUiRunner, (previewUiMinIntervalMs - sinceLast).coerceAtLeast(20L)
                )
                return
            }

            val a = pendingPreviewUiAction
            pendingPreviewUiAction = null
            if (a != null) {
                previewUiOpRunning = true
            }
            a
        }

        if (actionToRun == null) return

        // Execute on main thread (CameraX requirement). We are already on main because previewUiRunner is posted via rebindHandler.
        val start = android.os.SystemClock.uptimeMillis()
        try {
            when (actionToRun) {
                is PreviewUiAction.Detach -> performDetachPreviewInternal()
                is PreviewUiAction.Attach -> performAttachPreviewInternal(
                    actionToRun.surfaceProvider, actionToRun.displayRotation
                )
            }
        } catch (t: Throwable) {
            Log.w(logFrom, "⚠️ [CAMERAX] Preview UI op failed (non-fatal): $actionToRun", t)
        } finally {
            val end = android.os.SystemClock.uptimeMillis()
            val dur = end - start
            if (dur >= 750L) {
                Log.w(
                    logFrom,
                    "⚠️ [CAMERAX] Preview UI op took ${dur}ms: $actionToRun (risk of ANR under churn)"
                )
            } else {
                Log.d(logFrom, "🔵 [CAMERAX] Preview UI op completed in ${dur}ms: $actionToRun")
            }

            synchronized(previewUiOpLock) {
                lastPreviewUiOpUptimeMs = end
                previewUiOpRunning = false
                // If another request arrived while we were running, schedule again.
                if (pendingPreviewUiAction != null && !previewUiOpScheduled) {
                    previewUiOpScheduled = true
                    rebindHandler.postDelayed(previewUiRunner, previewUiDebounceMs)
                }
            }
        }
    }

    /**
     * Safely detach the Preview UseCase from the active camera session.
     *
     * Why:
     * - When the App/Activity goes to background (ON_STOP), the PreviewView's Surface is destroyed.
     * - If CameraX keeps trying to write to a dead surface, it can stall the entire pipeline on some HALs (Exynos/M30s),
     *   stopping ImageAnalysis (recording/streaming) effectively.
     * - Using bindCamera(null) triggers a full session reconfiguration (close -> open), which blocks the Main Thread
     *   for ~300ms, causing UI freeze/ANR during the transition.
     * - unbind(previewUseCase) is lightweight and instant, removing the dead dependency while keeping
     *   ImageAnalysis/VideoCapture running smoothly.
     */
    fun detachPreview() {
        // Coalesced request (latest-wins). Returns immediately to caller.
        requestPreviewUiAction(PreviewUiAction.Detach)
    }

    /**
     * Safely re-attach Preview to the current camera session without rebuilding the full use-case graph.
     *
     * Why:
     * - Full `bindCamera(...)` does `unbindAll()` + rebuilds the entire graph, which can block the main thread
     *   under lifecycle churn and trigger ANRs.
     * - After backgrounding, we only need the UI preview back; streaming/analysis should already be running.
     *
     * Contract:
     * - Runs CameraX API calls on the MAIN thread (CameraX requirement).
     * - Returns immediately to the caller (safe for Binder/UI thread).
     */
    fun attachPreview(surfaceProvider: Preview.SurfaceProvider, displayRotation: Int) {
        // Remember the latest UI surface so camera switches can re-attach later.
        lastSurfaceProvider = surfaceProvider
        lastDisplayRotation = displayRotation
        // Coalesced request (latest-wins). Returns immediately to caller.
        requestPreviewUiAction(PreviewUiAction.Attach(surfaceProvider, displayRotation))
    }

    /**
     * Main-thread internal implementation: detach Preview use case.
     * Must NOT be called from Binder/UI thread directly; use requestPreviewUiAction().
     */
    private fun performDetachPreviewInternal() {
        val provider = cameraProvider ?: return
        val preview = previewUseCase ?: return
        try {
            if (provider.isBound(preview)) {
                Log.d(logFrom, "🔵 [CAMERAX] Detaching PreviewUseCase (coalesced)")
                provider.unbind(preview)
            }
        } catch (t: Throwable) {
            Log.w(logFrom, "⚠️ [CAMERAX] performDetachPreviewInternal failed (ignored)", t)
        }
    }

    /**
     * Main-thread internal implementation: attach Preview use case.
     * Must NOT be called from Binder/UI thread directly; use requestPreviewUiAction().
     */
    private fun performAttachPreviewInternal(
        surfaceProvider: Preview.SurfaceProvider, displayRotation: Int
    ) {
        val provider = cameraProvider
        if (provider == null) {
            Log.w(logFrom, "⚠️ [CAMERAX] attachPreview: cameraProvider is null (no-op)")
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(logFrom, "⚠️ [CAMERAX] attachPreview: CAMERA permission missing (no-op)")
            return
        }

        val selectorAtCall =
            if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

        try {
            val existing = previewUseCase
            if (existing != null && provider.isBound(existing)) {
                Log.d(
                    logFrom,
                    "🔵 [CAMERAX] attachPreview: preview already bound; updating SurfaceProvider"
                )
                existing.surfaceProvider = surfaceProvider
                return
            }

            val target = Size(currentHeight, currentWidth) // 4:3 portrait target
            val selector = ResolutionSelector.Builder().setAspectRatioStrategy(
                AspectRatioStrategy(
                    AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO
                )
            ).setResolutionStrategy(
                ResolutionStrategy(
                    target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                )
            ).build()

            val preview =
                Preview.Builder().setResolutionSelector(selector).setTargetRotation(displayRotation)
                    .build().apply { setSurfaceProvider(surfaceProvider) }

            previewUseCase = preview

            Log.d(logFrom, "🔵 [CAMERAX] attachPreview: binding Preview use case (coalesced)")
            provider.bindToLifecycle(this, selectorAtCall, preview)
        } catch (t: Throwable) {
            Log.w(
                logFrom,
                "⚠️ [CAMERAX] performAttachPreviewInternal failed; falling back to full bindCamera()",
                t
            )
            try {
                bindCamera(surfaceProvider, null, displayRotation)
            } catch (t2: Throwable) {
                Log.e(logFrom, "❌ [CAMERAX] attachPreview fallback bindCamera failed", t2)
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Expected behavior:
        // - If the Primary is actively capturing (PREVIEW/RECORDING), the service should continue (CCTV use case).
        // - If capture is STOPPED (IDLE) and the app is quit, we *may* stop the service to remove the notification,
        //   but ONLY when there are no connected viewers; otherwise we keep the TCP server alive to prevent
        //   Viewer-side ECONNREFUSED/reconnect loops.
        val sessions = try {
            streamServer?.activeSessionCount()
        } catch (t: Throwable) {
            Log.w(
                logFrom,
                "Task removed — failed to read StreamServer session count (defaulting to 0)",
                t
            )
            0
        }
        Log.w(
            logFrom,
            "Task removed — serviceState=$serviceState (recording=${serviceState == ServiceCaptureState.RECORDING}, sessions=$sessions)"
        )

        if (sessions != null) {
            if (serviceState == ServiceCaptureState.IDLE && sessions <= 0) {
                Log.w(
                    logFrom,
                    "Task removed while IDLE with 0 sessions — stopping foreground + service"
                )
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (_: Throwable) {
                }
                stopSelf()
            } else {
                Log.w(
                    logFrom,
                    "Task removed but keeping service alive (serviceState=$serviceState, sessions=$sessions)"
                )
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status: Int ->
        // Snapshot for governor decisions + diagnostics.
        lastThermalStatus = status
        when (status) {
            PowerManager.THERMAL_STATUS_SEVERE, PowerManager.THERMAL_STATUS_CRITICAL, PowerManager.THERMAL_STATUS_EMERGENCY -> {
                onThermalThrottling()
            }
        }
    }

    private fun onThermalThrottling() {
        Log.w("CCTV_CAMERA", "Thermal throttling detected — requesting encoder downgrade")

        val lowered = StreamConfig(
            width = currentWidth,
            height = currentHeight,
            bitrate = (currentBitrate * 0.7).toInt().coerceAtLeast(300_000),
            fps = currentFPS
        )

        reconfigureEncoderIfNeeded(lowered)
    }

    /*****Lifecycle Logic*****************/
    private fun runWhenStarted(block: () -> Unit) {
        if (lifecycle.currentState.isAtLeast(
                androidx.lifecycle.Lifecycle.State.STARTED
            )
        ) {
            block()
        } else {
            lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                    lifecycle.removeObserver(this)
                    block()
                }
            })
        }
    }


    /********************Helper*****************/
    /**
     * Adjust bitrate seamlessly without encoder reset
     * Uses MediaCodec.setParameters() for dynamic bitrate adjustment
     */
    private fun adjustBitrate(newBitrate: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastBitrateChangeUptimeMs < BITRATE_CHANGE_MIN_INTERVAL_MS) {
            // Log.v(logFrom, "Skipping bitrate adjustment (rate limited)")
            return
        }

        synchronized(encoderLock) {
            videoEncoder?.adjustBitrate(newBitrate)
        }
        currentBitrate = newBitrate
        lastBitrateChangeUptimeMs = now
        try {
            updateBitrateFloorTracking(android.os.SystemClock.uptimeMillis(), currentBitrate)
        } catch (_: Throwable) {
        }
        Log.d(logFrom, "Bitrate adjusted to $newBitrate bps (seamless, no encoder reset)")
    }

    fun reconfigureEncoderIfNeeded(config: StreamConfig?) {
        if (config == null) return

        // Keep config requests capability-driven:
        // - choose a validated common sensor/output size (avoid FOV/crop mismatches)
        // - cap bitrate and fps to what we believe is stable on this device
        ensureDeviceProfile("reconfigure_encoder")
        val resolvedCfg = resolveRequestedConfigUsingProfile(config)

        // NOTE:
        // On some devices (notably Samsung M30-family), codec stop/start during a viewer connect/renegotiate can
        // trigger a native crash (SIGSEGV / status=11) in vendor MediaCodec / camera HAL.
        // For those devices we prefer stability: keep a fixed encoder resolution (Buffer Mode) and only adjust
        // bitrate at runtime (MediaCodec.setParameters), ignoring viewer-driven width/height/fps changes.
        val forceBufferMode = AppSettings.isForceBufferMode(this)
        val willUseBufferMode = VideoEncoder.shouldPreferBufferMode(this, forceBufferMode)
        if (willUseBufferMode) {
            // Resolve the fixed buffer-mode encoder resolution based on requested orientation.
            val portrait = resolvedCfg.width < resolvedCfg.height
            val fixedW = if (portrait) 720 else 960
            val fixedH = if (portrait) 960 else 720

            // Keep internal "current" resolution consistent with what we actually run.
            currentWidth = fixedW
            currentHeight = fixedH

            // If encoder isn't running yet, bring it up using the existing start path (no stop/start churn).
            val encoderRunning = synchronized(encoderLock) { videoEncoder != null }
            if (!encoderRunning) {
                Log.w(
                    logFrom,
                    "🟠 [RESOLUTION] Buffer Mode: encoder not running; starting fixed encoder ${fixedW}x${fixedH} (requested=${config.width}x${config.height})"
                )
                try {
                    startEncoder()
                } catch (t: Throwable) {
                    Log.e(logFrom, "❌ Buffer Mode: startEncoder() failed", t)
                }
            }

            // Apply bitrate change safely without recreating the codec.
            if (resolvedCfg.bitrate != currentBitrate) {
                try {
                    adjustBitrate(resolvedCfg.bitrate)
                } catch (t: Throwable) {
                    Log.w(logFrom, "⚠️ Buffer Mode: adjustBitrate failed (ignored)", t)
                    currentBitrate = resolvedCfg.bitrate
                }
            }

            // FPS changes are ignored in Buffer Mode to avoid rebind/recreate churn on problematic devices.
            if (resolvedCfg.fps != currentFPS) {
                Log.w(
                    logFrom,
                    "🟠 [CAMERA FPS] Buffer Mode: ignoring viewer fps=${resolvedCfg.fps} (keeping=$currentFPS) for stability"
                )
            }

            // Keep StreamServer + Viewer in sync with the *actual* encoder dimensions.
            try {
                streamServer?.updateActiveConfigWithEncoderResolution(fixedW, fixedH)
            } catch (t: Throwable) {
                Log.w(logFrom, "Buffer Mode: updateActiveConfigWithEncoderResolution failed (ignored)", t)
            }

            // Force a keyframe after any resolved config so new viewers lock on quickly.
            try {
                synchronized(encoderLock) {
                    videoEncoder?.requestSyncFrame()
                }
            } catch (_: Throwable) {
            }

            Log.w(
                logFrom,
                "🟠 [RESOLUTION] Buffer Mode: kept fixed encoder ${fixedW}x${fixedH}; applied bitrate=${resolvedCfg.bitrate} (requested=${resolvedCfg.width}x${resolvedCfg.height}@${resolvedCfg.fps})"
            )
            return
        }

        if (resolvedCfg.width == currentWidth && resolvedCfg.height == currentHeight && resolvedCfg.bitrate == currentBitrate && resolvedCfg.fps == currentFPS) {
            // No-op: already running requested config
            return
        }

        Log.d(
            logFrom,
            "Reconfiguring encoder: ${resolvedCfg.width}x${resolvedCfg.height} @ ${resolvedCfg.bitrate} fps=${resolvedCfg.fps}"
        )

        // Ensure encoder stop/start does not race with ImageAnalysis feeding encode()
        cameraExecutor.execute {
            synchronized(encoderLock) {
                try {
                    stopEncoder()

                    if (resolvedCfg.bitrate != currentBitrate) {
                        bitrateMax = resolvedCfg.bitrate // Update max target on explicit config change
                        Log.d(logFrom, "AIMD: Resetting bitrateMax to ${resolvedCfg.bitrate}")
                    }
                    startBitrateRecoveryGovernor()

                    currentBitrate = resolvedCfg.bitrate
                    currentFPS = resolvedCfg.fps

                    // For Buffer Mode: use 4:3 orientation-correct resolution (16-aligned).
                    // For Surface Mode: use requested config (aligned to 16).
                    val encoderWidth: Int
                    val encoderHeight: Int

                    if (willUseBufferMode) {
                        // CRITICAL (M30s stability + FOV consistency):
                        // In Buffer Mode we must keep CameraX binding resolution modest.
                        // If we keep `currentWidth/currentHeight` at the viewer-requested 1080x1440, CameraX will try to
                        // bind high-res Preview use cases even though the encoder is fixed at 720x960, which can crash or
                        // severely crop/derange the pipeline on Samsung M30s.
                        val portrait = resolvedCfg.width < resolvedCfg.height
                        encoderWidth = if (portrait) 720 else 960
                        encoderHeight = if (portrait) 960 else 720
                        currentWidth = encoderWidth
                        currentHeight = encoderHeight
                        Log.d(
                            logFrom,
                            "🔵 [RESOLUTION] reconfigureEncoderIfNeeded - Buffer Mode: Clamped stream/camera to ${encoderWidth}x${encoderHeight} (requested=${resolvedCfg.width}x${resolvedCfg.height}, portrait=$portrait)"
                        )
                    } else {
                        currentWidth = resolvedCfg.width
                        currentHeight = resolvedCfg.height
                        val (alignedW, alignedH) = MediaCodecConfig.alignResolution16(
                            currentWidth, currentHeight
                        )
                        encoderWidth = alignedW
                        encoderHeight = alignedH
                        Log.d(
                            logFrom,
                            "🔵 [RESOLUTION] reconfigureEncoderIfNeeded - Surface Mode: Using ${encoderWidth}x${encoderHeight}"
                        )
                    }

                    videoEncoder = VideoEncoder(
                        width = encoderWidth,
                        height = encoderHeight,
                        bitrate = currentBitrate,
                        frameRate = currentFPS,
                        iFrameInterval = 1,
                        context = this,
                        forceBufferMode = forceBufferMode
                    ) { _, _, _ -> }

                    videoEncoder?.setCodecConfigListener { sps, pps ->
                        streamServer?.broadcastCsd(sps, pps)
                    }
                    videoEncoder?.setEncodedFrameListener(object : EncodedFrameListener {
                        override fun onEncodedFrame(frame: EncodedFrame) {
                            try {
                                Log.d(
                                    logFrom,
                                    "🔵 [FRAME FLOW] frameListener.onEncodedFrame() called (reconfigure): size=${frame.data.size}, isKeyFrame=${frame.isKeyFrame}, streamServer=${streamServer != null}"
                                )
                                streamServer?.enqueueFrame(frame)
                            } catch (t: Throwable) {
                                Log.e(
                                    logFrom,
                                    "🔴 [FRAME FLOW] frameListener.onEncodedFrame() failed (reconfigure)",
                                    t
                                )
                            }
                        }
                    })

                    videoEncoder?.start()

                    // Keep StreamServer + Viewer in sync with the *actual* encoder dimensions immediately.
                    try {
                        streamServer?.updateActiveConfigWithEncoderResolution(
                            encoderWidth, encoderHeight
                        )
                    } catch (t: Throwable) {
                        Log.w(
                            logFrom,
                            "Failed to update StreamServer with new encoder resolution (ignored)",
                            t
                        )
                    }

                    videoEncoder?.requestSyncFrame()

                    Log.d(
                        logFrom,
                        "✅ Encoder reconfigured and restarted: ${encoderWidth}x${encoderHeight} @ $currentBitrate fps=${currentFPS} (bufferMode=$willUseBufferMode)"
                    )

                    // CRITICAL (M30s stability + connect loops):
                    // If we clamped the stream/encoder to Buffer Mode (720x960), the CameraX graph must be rebound to
                    // actually apply the lower resolution selector. Otherwise the camera may still be running the
                    // previous high-res graph (e.g., 1080x1440), which can crash or stall on Samsung M30s when a
                    // high-end Viewer connects and triggers negotiation.
                    //
                    // This rebind is lightweight (uses the existing retry pipeline) and respects whether the Primary UI
                    // is visible to avoid binding Preview to a dead surface.
                    try {
                        if (willUseBufferMode && serviceState != ServiceCaptureState.IDLE) {
                            requestCameraRebind(
                                reason = "stream_config_buffer_mode_${encoderWidth}x${encoderHeight}",
                                includePreview = primaryUiVisible
                            )
                        }
                    } catch (_: Throwable) {
                    }
                } catch (t: Throwable) {
                    // Defensive fallback: if reconfigure fails, try to restart encoder with current settings.
                    Log.e(
                        logFrom, "❌ Encoder reconfigure failed - attempting fallback restart", t
                    )
                    try {
                        // Ensure we don't leave encoder in a stopped/null state.
                        stopEncoder()
                    } catch (_: Throwable) {
                    }
                    try {
                        startEncoder()
                        Log.w(
                            logFrom, "Fallback encoder restart succeeded after reconfigure failure"
                        )
                    } catch (t2: Throwable) {
                        Log.e(
                            logFrom,
                            "Fallback encoder restart FAILED - streaming will be broken until restart",
                            t2
                        )
                    }
                }
            }
        }
    }
}