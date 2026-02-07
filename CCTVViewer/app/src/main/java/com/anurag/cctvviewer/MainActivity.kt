package com.anurag.cctvviewer

import android.graphics.Bitmap
import android.Manifest
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import android.content.ContentValues
import android.content.Intent
import android.provider.MediaStore
import android.os.Build
import android.os.Environment
import android.content.pm.PackageManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import android.view.TextureView
import android.view.SurfaceView
import android.graphics.SurfaceTexture
import android.graphics.Matrix
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.viewinterop.AndroidView
import com.anurag.cctvviewer.ui.theme.CCTVViewerTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.documentfile.provider.DocumentFile
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.anurag.cctvviewer.ui.responsive.ResponsiveSingleScreen
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import androidx.core.net.toUri

// Debug-only UI diagnostics overlay.
// Keep this OFF by default to avoid logcat/UI noise during normal use.
private const val DEBUG_STREAM_STATS_OVERLAY = false

/**
 * A single "UI/render truth" state derived from multiple low-level signals.
 *
 * Intent:
 * - Reduce scattered boolean-driven UI decisions (surface flags, preview flags, connection flags).
 * - Provide Media3-like discipline for overlays and user feedback WITHOUT changing the streaming pipeline.
 * - Keep this derivation conservative so we don't break existing behavior.
 */
private sealed interface PlaybackState {
    /** App is not connected (user sees "No Connection"). */
    data object Idle : PlaybackState

    /** TCP connection / handshake in progress. */
    data object Connecting : PlaybackState

    /** Connected and negotiating / waiting for the first visible frame (warmup). */
    data object Buffering : PlaybackState

    /** Stream is connected, but no frames are arriving or capture stopped. */
    data object NoVideo : PlaybackState

    /** Stream was playing but temporarily stalled (e.g., brief pause, keyframe wait, surface transition). */
    data object Rebuffering : PlaybackState

    /** Video is confirmed visible (TextureView update or SurfaceView PixelCopy) */
    data object Playing : PlaybackState
}

/**
 * Derives a stable PlaybackState from existing project signals.
 *
 * IMPORTANT:
 * - This function MUST NOT have side-effects.
 * - It should be safe to call on every recomposition.
 * - It intentionally does NOT inspect internal StreamClient fields to avoid coupling/refactor risk.
 */
private fun derivePlaybackState(
    connectionState: ConnectionState, hasPreviewFrame: Boolean
): PlaybackState {
    // Render-confirmed wins: if we can see a frame, we are "Playing" regardless of minor state flags.
    if (hasPreviewFrame) return PlaybackState.Playing

    return when (connectionState) {
        ConnectionState.DISCONNECTED -> PlaybackState.Idle
        ConnectionState.CONNECTING -> PlaybackState.Connecting
        ConnectionState.AUTHENTICATED -> PlaybackState.Buffering
        ConnectionState.CONNECTED -> PlaybackState.NoVideo
        ConnectionState.RECOVERING -> PlaybackState.Rebuffering
        // STREAMING but no visible frame: treat as buffering (Surface warmup, keyframe wait, PixelCopy gating).
        ConnectionState.STREAMING -> PlaybackState.Buffering
        ConnectionState.IDLE -> PlaybackState.Idle
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Back gesture/button should send app to background instead of closing.
        onBackPressedDispatcher.addCallback(
            this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    moveTaskToBack(true)
                }
            })
       /* WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            show(WindowInsetsCompat.Type.statusBars())
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }*/


        // Keep screen on while viewing
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            CCTVViewerTheme {
                Surface(Modifier.statusBarsPadding()) {
                    ResponsiveSingleScreen(
                        // Reference design size for the existing single-screen layout.
                        // The wrapper will scale this canvas to fit available display space.
                        refWidth = 360.dp, refHeight = 800.dp,

                        minScale = 0.85f, maxScale = 1.30f,
                        // Protect the fixed layout from extreme font scaling overflow.
                        // Set to null if you want to fully honor accessibility font scaling.
                        safeFontScaleRange = 0.90f..1.10f, logTag = "CCTV_VIEWER"
                    ) {
                        val context = LocalContext.current

                        val defaultUseSurfaceView = remember {
                            // Default renderer:
                            // - SurfaceView is the preferred, high-performance path on most modern devices.
                            // - Some specific devices have known SurfaceView rendering issues; those will be force-switched
                            //   to TextureView via forceTextureViewDevice below.
                            true
                        }
                        // Some Samsung Exynos devices (e.g., SM-M307F / M30s) can decode into vendor color formats
                        // that render black on SurfaceView even though MediaCodec is "rendering".
                        // For stability, force TextureView on these devices.
                        val forceTextureViewDevice = remember { DeviceQuirks.forceTextureViewForBlackSurfaceView() }
                        var useSurfaceView by remember {
                            mutableStateOf(
                                AppSettings.isUseSurfaceViewEnabled(
                                    context, defaultUseSurfaceView
                                )
                            )
                        }
                        var state by remember { mutableStateOf(ConnectionState.DISCONNECTED) }
                        var prevStateForToast by remember { mutableStateOf(ConnectionState.DISCONNECTED) }
                        /** Once true, we clear focus from the IP field when returning to DISCONNECTED so the keyboard does not auto-show after a successful connection. */
                        var connectionEverSucceeded by remember { mutableStateOf(false) }
                        var lastError by remember { mutableStateOf<String?>(null) }
                        var rotationDeg by remember { mutableIntStateOf(0) }
                        // NOTE:
                        // Do not model pinch-zoom as Compose state.
                        // Updating a Compose state on every gesture frame can cause avoidable recompositions and
                        // restart expensive modifiers (e.g., `pointerInput`). We keep zoom accumulation inside the
                        // gesture coroutine and only send coalesced zoom commands to the Primary.
                        var isRecording by remember { mutableStateOf(false) }
                        // IMPORTANT (TextureView correctness on some Samsung devices):
                        // Do not seed a fake "default" video size (e.g. 1280x720). On TextureView we call
                        // SurfaceTexture.setDefaultBufferSize(srcW, srcH) from applyTextureTransform().
                        // If we apply an incorrect placeholder size early, some devices can keep that buffer sizing
                        // and the preview will look persistently "zoomed" until a full surface/decoder restart.
                        //
                        // We therefore start at 0 and only size/transform once MediaCodec reports output format.
                        var videoW by remember { mutableIntStateOf(0) }
                        var videoH by remember { mutableIntStateOf(0) }
                        var isFrontCamera by remember { mutableStateOf(false) }
                        var cropL by remember { mutableIntStateOf(0) }
                        var cropT by remember { mutableIntStateOf(0) }
                        var cropR by remember { mutableIntStateOf(-1) }
                        var cropB by remember { mutableIntStateOf(-1) }
                        var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
                        var surfaceViewRef by remember { mutableStateOf<SurfaceView?>(null) }
                        var hasPreviewFrame by remember { mutableStateOf(false) }
                        // Tracks whether we've ever shown a real preview in this session.
                        // Used to distinguish "initial buffering" vs "rebuffer after stall" at the UI level.
                        var hadPreviewEver by remember { mutableStateOf(false) }
                        // Decoder-side "first frame rendered" signal.
                        // IMPORTANT: On some devices (e.g. Nord CE4), the *first rendered output* can still be a green/uninitialized frame.
                        // We therefore treat this as a "decoder is producing output" signal, and use PixelCopy on SurfaceView
                        // to detect when real (non-green) pixels appear before declaring preview visible.
                        var decoderReportedFirstRender by remember { mutableStateOf(false) }
                        // SurfaceView readiness needs to be stable: require a few consecutive "good" PixelCopy samples
                        // so we don't accidentally unhide on a transient or stale copy.
                        var surfaceReadyStreak by remember { mutableIntStateOf(0) }
                        // 3-gate renderer: transform only when view laid out, surface ready, and video dimensions known.
                        // Prevents permanent bad state when transform was skipped (e.g. width=0) but a flag was cleared.
                        var viewReady by remember { mutableStateOf(false) }
                        var surfaceReady by remember { mutableStateOf(false) }
                        // Cache: only recompute when inputs change (reduces CPU/battery).
                        var lastAppliedVideoW by remember { mutableIntStateOf(0) }
                        var lastAppliedVideoH by remember { mutableIntStateOf(0) }
                        var lastAppliedViewW by remember { mutableIntStateOf(0) }
                        var lastAppliedViewH by remember { mutableIntStateOf(0) }
                        var firstTransformLogged by remember { mutableStateOf(false) }
                        // Texture update tracking for diagnostics
                        var textureUpdateCounter by remember { mutableLongStateOf(0L) }
                        var textureUpdateFirstTime by remember { mutableLongStateOf(0L) }
                        // Decoder is configured to crop-to-fill; UI container matches aspect.

                        // IP input state
                        var serverIp by remember { mutableStateOf("192.168.1.7") } // default fallback
                        var ipError by remember { mutableStateOf<String?>(null) }

                        // Password input state
                        var password by remember { mutableStateOf(AppSettings.getPassword(context)) }
                        var passwordVisible by remember { mutableStateOf(false) }
                        var talkActive by remember { mutableStateOf(false) }
                        // Audio should be audible by default.
                        var muteAudio by remember { mutableStateOf(false) }
                        var communicationEnabled by remember { mutableStateOf(true) }
                        // Auto-fallback guard: only switch renderer once per connection.
                        var autoFallbackAttempted by remember { mutableStateOf(false) }

                        /**
                         * Flag to force-stop active talkback when server disables communication.
                         *
                         * Purpose: When the Primary device disables talkback (communicationEnabled becomes false),
                         * any active talk session on the Viewer must be stopped immediately. However, the callback
                         * that receives this state change (onCommunicationEnabledChanged) cannot directly call
                         * client.stopTalk() because it's not in a composable context.
                         *
                         * Mechanism: This flag is set to true when communicationEnabled becomes false, which
                         * triggers a LaunchedEffect that stops talkback and resets the flag. This ensures proper
                         * cleanup even if the user had talkback active when the server disabled communication.
                         */
                        var forceStopTalk by remember { mutableStateOf(false) }
                        // Settings
                        var showSettings by remember { mutableStateOf(false) }
                        var port by remember { mutableIntStateOf(AppSettings.getPort(context)) }
                        var portText by remember { mutableStateOf(port.toString()) }
                        var showTimestamp by remember {
                            mutableStateOf(
                                AppSettings.isTimestampEnabled(
                                    context
                                )
                            )
                        }
                        var useSurfaceViewSetting by remember { mutableStateOf(useSurfaceView) }

                        // Safety: if this device is known to show black video on SurfaceView, force TextureView and persist it.
                        LaunchedEffect(forceTextureViewDevice) {
                            try {
                                if (forceTextureViewDevice && useSurfaceView) {
                                    Log.w(
                                        "CCTV_VIEWER",
                                        "[VIEWER MA] ⚠️ [SURFACE] Forcing TextureView: device=${Build.MANUFACTURER} ${Build.MODEL} is known to render black on SurfaceView"
                                    )
                                    AppSettings.setUseSurfaceViewEnabled(context, false)
                                    useSurfaceViewSetting = false
                                    useSurfaceView = false
                                    Toast.makeText(
                                        context,
                                        "SurfaceView disabled on this device (black-screen issue). Using TextureView.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            } catch (t: Throwable) {
                                Log.e(
                                    "CCTV_VIEWER",
                                    "[VIEWER MA] 🔴 [SURFACE] Failed to force TextureView fallback",
                                    t
                                )
                            }
                        }

                        // UX: if we were in CONNECTED (no video) and the Primary app closes, show a disconnect toast.
                        LaunchedEffect(state, prevStateForToast) {
                            try {
                                if (state == ConnectionState.DISCONNECTED && prevStateForToast == ConnectionState.CONNECTED) {
                                    Toast.makeText(
                                        context, "Disconnected from Primary", Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } catch (_: Throwable) {
                            }
                        }
                        // Image storage (captures/screenshots)
                        var imageTreeUriText by remember {
                            mutableStateOf(
                                AppSettings.getImageTreeUri(
                                    context
                                )
                            )
                        }
                        var imageFolderLabel by remember { mutableStateOf("") }
                        var timestampText by remember { mutableStateOf("") }
                        var hasAudioPermission by remember {
                            mutableStateOf(
                                ContextCompat.checkSelfPermission(
                                    this, Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                            )
                        }
                        // Hoisted string resources for use in non-composable scopes (callbacks)
                        val toastMicDenied =
                            stringResource(R.string.toast_microphone_permission_denied)
                        val toastDisconnectPort =
                            stringResource(R.string.toast_disconnect_to_change_port)
                        val toastImageSaveFailed = stringResource(R.string.toast_image_save_failed)
                        val toastImageSavedFmt = stringResource(R.string.toast_image_saved)
                        val errorEnterServerIp = stringResource(R.string.error_enter_server_ip)
                        val errorInvalidIp = stringResource(R.string.error_invalid_ip)
                        val errorMessageFmt = stringResource(R.string.error_message)
                        val audioPermissionLauncher = rememberLauncherForActivityResult(
                            ActivityResultContracts.RequestPermission()
                        ) { granted ->
                            hasAudioPermission = granted
                            if (!granted) {
                                Toast.makeText(context, toastMicDenied, Toast.LENGTH_SHORT).show()
                            }
                        }

                        // Resolve the selected folder display name (best-effort).
                        LaunchedEffect(imageTreeUriText) {
                            imageFolderLabel = imageTreeUriText?.let { s ->
                                val uri = runCatching { s.toUri() }.getOrNull()
                                val name = if (uri != null) runCatching {
                                    DocumentFile.fromTreeUri(
                                        context, uri
                                    )?.name
                                }.getOrNull() else null
                                name ?: s
                            } ?: ""
                        }

                        val imageFolderPicker = rememberLauncherForActivityResult(
                            contract = ActivityResultContracts.OpenDocumentTree()
                        ) { uri ->
                            if (uri != null) {
                                try {
                                    val flags =
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                    context.contentResolver.takePersistableUriPermission(uri, flags)
                                } catch (_: Throwable) {
                                    // Some providers may not support persistable permissions; still save the URI.
                                }
                                imageTreeUriText = uri.toString()
                                AppSettings.setImageTreeUri(context, imageTreeUriText)
                            }
                        }

// Load last successful IP and connection-ever-succeeded once, when composable starts
                        LaunchedEffect(Unit) {
                            val prefs = context.getSharedPreferences("cctv_viewer", MODE_PRIVATE)
                            val saved = prefs.getString("last_server_ip", null)
                            if (!saved.isNullOrBlank()) {
                                serverIp = saved
                            }
                            connectionEverSucceeded = prefs.getBoolean("connection_ever_succeeded", false)
                        }
                        // After first successful connection, persist so we stop auto-focusing the IP field on later disconnects/relaunches
                        LaunchedEffect(state) {
                            if (state == ConnectionState.STREAMING || state == ConnectionState.CONNECTED) {
                                context.getSharedPreferences("cctv_viewer", MODE_PRIVATE).edit {
                                    putBoolean("connection_ever_succeeded", true)
                                }
                                connectionEverSucceeded = true
                            }
                        }

                        // Simple IPv4 validation regex
                        val ipRegex = remember {
                            Regex("""^((25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(25[0-5]|2[0-4]\d|[01]?\d\d?)$""")
                        }

                        fun isValidIp(ip: String): Boolean = ipRegex.matches(ip.trim())

                        // Single StreamClient instance; host is supplied via updateHost()
                        val client = remember(password, port) {
                            StreamClient(
                                port = port,
                                password = password,
                                onStateChanged = { newState ->
                                    // Track state transitions for UX behaviors (e.g., disconnect toast).
                                    prevStateForToast = state
                                    state = newState
                                    // If Primary stops capture, StreamClient watchdog downgrades to CONNECTED.
                                    // Ensure UI shows "No Video" immediately instead of keeping a stale last frame.
                                    if (newState == ConnectionState.CONNECTED || newState == ConnectionState.DISCONNECTED) {
                                        hasPreviewFrame = false
                                        decoderReportedFirstRender = false
                                    }
                                },
                                onError = { msg -> lastError = msg },
                                onRotationChanged = { deg -> rotationDeg = deg },
                                onRecordingStateChanged = { active -> isRecording = active },
                                onFirstFrameRendered = {
                                    // Decoder-side signal: MediaCodec has released at least one output buffer with render=true.
                                    // For TextureView we can trust this (onSurfaceTextureUpdated will also fire), but for SurfaceView
                                    // we must verify pixels to avoid showing a green/uninitialized surface.
                                    if (!decoderReportedFirstRender) {
                                        decoderReportedFirstRender = true
                                        Log.d(
                                            "CCTV_VIEWER",
                                            "[VIEWER MA] 🔍 [SURFACE] Decoder reported first render (useSurfaceView=$useSurfaceView)"
                                        )
                                    }
                                    if (!useSurfaceView) {
                                        hasPreviewFrame = true
                                    }
                                },
                                onCameraFacingChanged = { front -> isFrontCamera = front },
                                onCommunicationEnabledChanged = { enabled ->
                                    communicationEnabled = enabled
                                    if (!enabled) forceStopTalk = true
                                },
                                onVideoSizeChanged = { w, h ->
                                    videoW = w
                                    videoH = h
                                    // Reset crop until decoder tells us otherwise.
                                    cropL = 0
                                    cropT = 0
                                    cropR = -1
                                    cropB = -1
                                },
                                onVideoCropChanged = { l, t, r, b ->
                                    cropL = l
                                    cropT = t
                                    cropR = r
                                    cropB = b
                                })
                        }

                        // Keep StreamClient's internal host in sync with the UI text field.
                        //
                        // This prevents "Please enter server IP…" / "Enter Server IP…" style errors on resume:
                        // StreamClient can attempt an automatic reconnect when the app returns to foreground,
                        // but its `host` is only updated via updateHost(). If the user already has a valid IP
                        // in the field (loaded from prefs), we still want StreamClient.host to be set.
                        LaunchedEffect(serverIp, state) {
                            if (state == ConnectionState.DISCONNECTED) {
                                try {
                                    client.updateHost(serverIp.trimEnd('.'))
                                } catch (_: Throwable) {
                                }
                            }
                        }

                        // IMPORTANT:
                        // Do NOT shutdown the StreamClient just because the UI composition is disposed.
                        // When the app goes to background, Compose may dispose/recreate parts of the tree; if we shutdown here,
                        // the viewer will drop the socket/heartbeat and appear "randomly disconnected" on return to foreground.
                        //
                        // Shutdown is handled via Lifecycle (ON_DESTROY) below.

                        // Keep StreamClient informed about whether the UI has *actually* shown video.
                        // IMPORTANT: This is stronger than decoder output and is required to avoid early bitrate thrash.
                        LaunchedEffect(client, hasPreviewFrame) {
                            try {
                                client.setPreviewVisible(hasPreviewFrame)
                            } catch (t: Throwable) {
                                Log.w(
                                    "CCTV_VIEWER",
                                    "[VIEWER MA] ⚠️ [VIEW] Failed to set previewVisible on StreamClient",
                                    t
                                )
                            }
                        }

                        // Maintain "ever previewed" flag for UI-only state derivation.
                        LaunchedEffect(state, hasPreviewFrame) {
                            if (state == ConnectionState.DISCONNECTED) {
                                hadPreviewEver = false
                                return@LaunchedEffect
                            }
                            if (hasPreviewFrame) {
                                hadPreviewEver = true
                            }
                        }

                        // On disconnect: clear any stale visible frame and reset view state.
                        LaunchedEffect(state) {
                            if (state != ConnectionState.DISCONNECTED) return@LaunchedEffect
                            hasPreviewFrame = false
                            decoderReportedFirstRender = false
                            // CRITICAL:
                            // On TextureView we hide the view (alpha=0) until we detect the *first rendered frame*
                            // via onSurfaceTextureUpdated(). That "first frame" detection uses textureUpdateCounter==0.
                            //
                            // If we disconnect/reconnect without recreating the TextureView (common case),
                            // onSurfaceTextureAvailable() will NOT fire again, so textureUpdateCounter would remain >0.
                            // Result: frames can be rendering but alpha never returns to 1.0 → user sees a black box.
                            textureUpdateCounter = 0L
                            textureUpdateFirstTime = 0L
                            // Hide stale last frame (TextureView retains its last buffer).
                            try {
                                textureViewRef?.alpha = 0.0f
                            } catch (_: Throwable) {
                            }
                        }

                        // Periodically poll StreamClient health for UI classification (no pipeline control).
                        var healthSnapshot by remember {
                            mutableStateOf<StreamHealthSnapshot?>(
                                null
                            )
                        }
                        LaunchedEffect(client) {
                            while (true) {
                                healthSnapshot = try {
                                    client.getHealthSnapshot()
                                } catch (_: Throwable) {
                                    null
                                }
                                delay(250)
                            }
                        }

                        // CRITICAL FIX: If user toggles SurfaceView/TextureView mode, detach surface so the new view can re-attach cleanly.
                        // This ensures proper decoder/Surface lifecycle when switching between view types (matches backup working version)
                        // IMPORTANT: Only detach the surface when the renderer mode ACTUALLY CHANGES due to user action
                        // (SurfaceView <-> TextureView). Detaching during initial composition can race Surface callbacks and
                        // lead to "first connect shows no preview until reconnect".
                        var lastRendererWasSurfaceView by remember { mutableStateOf(useSurfaceView) }
                        LaunchedEffect(useSurfaceView) {
                            val prev = lastRendererWasSurfaceView
                            if (useSurfaceView != prev) {
                                Log.w(
                                    "CCTV_VIEWER",
                                    "[VIEWER MA] 🔍 [SURFACE] Renderer toggled: useSurfaceView $prev -> $useSurfaceView; detaching surface for clean re-attach"
                                )
                                try {
                                    client.detachSurface()
                                } catch (_: Throwable) {
                                }
                                hasPreviewFrame = false
                                decoderReportedFirstRender = false
                                textureUpdateCounter = 0L
                                textureUpdateFirstTime = 0L
                                // Clear view references to force recreation with new view type
                                textureViewRef = null
                                surfaceViewRef = null
                            }
                            // Compose state update (next run compares against this).
                            @Suppress("AssignedValueIsNeverRead")
                            lastRendererWasSurfaceView = useSurfaceView
                        }

                        // CRITICAL FIX: Removed detachSurface() from LaunchedEffect because:
                        // 1. It was being called on every recomposition, setting surfaceReady=false
                        // 2. This prevented the decoder from starting (decoder needs surfaceReady=true)
                        // 3. The surface lifecycle is already handled by SurfaceView/TextureView callbacks
                        // 4. SurfaceView.surfaceDestroyed() and TextureView.onSurfaceTextureDestroyed()
                        //    already call detachSurface() when needed
                        // The view references (textureViewRef, surfaceViewRef) will be updated when
                        // the AndroidView factory creates the new view, and the old view's callback
                        // will handle detachSurface() when the view is destroyed.

                        LaunchedEffect(forceStopTalk, client) {
                            if (!forceStopTalk) return@LaunchedEffect
                            if (talkActive) {
                                client.stopTalk()
                                talkActive = false
                            }
                            // Reset flag after handling
                            @Suppress("AssignedValueIsNeverRead")
                            forceStopTalk = false
                        }

                        // Ensure StreamClient matches the UI default (muted).
                        LaunchedEffect(client) {
                            client.setAudioMuted(muteAudio)
                            val prefs = context.getSharedPreferences("cctv_viewer", MODE_PRIVATE)
                            client.setStartProfileOverridePersistence(load = {
                                val w = prefs.getInt("perf_start_w", -1)
                                val h = prefs.getInt("perf_start_h", -1)
                                val br = prefs.getInt("perf_start_br", -1)
                                val fps = prefs.getInt("perf_start_fps", -1)
                                if (w > 0 && h > 0 && br > 0 && fps > 0) StreamConfig(
                                    w, h, br, fps
                                ) else null
                            }, save = { cfg: StreamConfig? ->
                                prefs.edit {
                                    if (cfg == null) {
                                        remove("perf_start_w")
                                        remove("perf_start_h")
                                        remove("perf_start_br")
                                        remove("perf_start_fps")
                                    } else {
                                        putInt("perf_start_w", cfg.width)
                                        putInt("perf_start_h", cfg.height)
                                        putInt("perf_start_br", cfg.bitrate)
                                        putInt("perf_start_fps", cfg.fps)
                                    }
                                }
                            })
                        }

                        // Render scaling is fixed to FIT (no crop-to-fill feature).

                        // SurfaceView safety fallback:
                        // On some devices, PixelCopy can be unreliable (or never returns a "good" sample),
                        // which would keep the UI stuck on "Starting stream" even though MediaCodec is already
                        // rendering to the SurfaceView.
                        //
                        // This fallback prevents that UX dead-end:
                        // - Only applies to SurfaceView mode.
                        // - Only triggers after StreamClient reports at least one rendered output buffer.
                        // - Only triggers once per "render attempt" window (LaunchedEffect re-keys on state).
                        // - Uses a grace delay to avoid revealing a transient green warmup frame.
                        LaunchedEffect(
                            state, useSurfaceView, decoderReportedFirstRender, hasPreviewFrame
                        ) {
                            try {
                                if (!useSurfaceView) return@LaunchedEffect
                                if (state != ConnectionState.STREAMING && state != ConnectionState.RECOVERING) return@LaunchedEffect
                                if (!decoderReportedFirstRender) return@LaunchedEffect
                                if (hasPreviewFrame) return@LaunchedEffect

                                // Give PixelCopy a moment to validate pixels; then force-reveal to avoid permanent blocking.
                                delay(2500)

                                if (useSurfaceView && (state == ConnectionState.STREAMING || state == ConnectionState.RECOVERING) && decoderReportedFirstRender && !hasPreviewFrame) {
                                    Log.w(
                                        "CCTV_VIEWER",
                                        "[VIEWER MA] ⚠️ [SURFACE] SurfaceView render detected but PixelCopy did not confirm within grace window; forcing preview reveal to avoid 'Starting stream' lock"
                                    )
                                    hasPreviewFrame = true
                                }
                            } catch (t: Throwable) {
                                // Never let UI fallback crash the app.
                                Log.e(
                                    "CCTV_VIEWER",
                                    "[VIEWER MA] 🔴 [SURFACE] SurfaceView fallback reveal failed (ignored)",
                                    t
                                )
                            }
                        }

                        // AUTO-FALLBACK: If we reach STREAMING but no frame is rendered, switch SurfaceView <-> TextureView once.
                        LaunchedEffect(state, hasPreviewFrame) {
                            if (state == ConnectionState.DISCONNECTED) {
                                @Suppress("AssignedValueIsNeverRead")
                                autoFallbackAttempted = false
                                return@LaunchedEffect
                            }
                            if (state < ConnectionState.STREAMING) return@LaunchedEffect
                            if (hasPreviewFrame) return@LaunchedEffect
                            if (autoFallbackAttempted) return@LaunchedEffect
                            // Do not auto-toggle on devices where SurfaceView is explicitly unsupported.
                            if (forceTextureViewDevice) return@LaunchedEffect
                            // IMPORTANT: Only fallback from TextureView -> SurfaceView.
                            // On some devices (e.g., Nord CE4), TextureView can remain black while SurfaceView works.
                            // Since SurfaceView is our default, we should never auto-switch away from it.
                            if (useSurfaceView) return@LaunchedEffect

                            // Wait briefly for first frame.
                            // 2 seconds was too aggressive on some devices during first connect / resume.
                            // Give the decoder + encoder time to negotiate and deliver the first keyframe.
                            delay(5000)
                            if ((state == ConnectionState.STREAMING || state == ConnectionState.RECOVERING) && !hasPreviewFrame && !autoFallbackAttempted) {
                                @Suppress("AssignedValueIsNeverRead")
                                autoFallbackAttempted = true
                                Log.w(
                                    "CCTV_VIEWER",
                                    "[VIEWER MA] ⚠️ [SURFACE] No preview frame after 5s in STREAMING; switching renderer TextureView -> SurfaceView"
                                )
                                try {
                                    client.detachSurface()
                                } catch (_: Throwable) {
                                }
                                // IMPORTANT: Do NOT persist fallback choice.
                                // Fallback is a per-session recovery mechanism; the user's setting (and default SurfaceView)
                                // should remain stable across reconnects.
                                useSurfaceView = true // TextureView -> SurfaceView only
                                Toast.makeText(
                                    context,
                                      "Video preview fallback: switched to SurfaceView",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }

                        // Timestamp overlay ticker (UI-only, does not affect streaming).
                        LaunchedEffect(showTimestamp) {
                            if (!showTimestamp) {
                                timestampText = ""
                                return@LaunchedEffect
                            }
                            while (showTimestamp) {
                                timestampText = SimpleDateFormat(
                                    "yyyy-MM-dd HH:mm:ss", Locale.getDefault()
                                ).format(
                                    Date()
                                )
                                delay(1000)
                            }
                        }

                        /** @return true if transform was applied (FIT), false if skipped (e.g. view not laid out). When false, TextureView keeps default FILL → zoomed preview. */
                        fun applyTextureTransform(
                            tv: TextureView,
                            srcW: Int,
                            srcH: Int,
                            rotation: Int,
                            isFrontCamera: Boolean,
                            reason: String
                        ): Boolean {
                            val vw = tv.width
                            val vh = tv.height
                            if (vw <= 0 || vh <= 0 || srcW <= 0 || srcH <= 0) {
                                Log.d(
                                    "CCTV_VIEWER",
                                    "[VIEWER MA] Transform skip $reason vw=$vw vh=$vh decoded=${srcW}x${srcH} (FIT not applied → preview may look zoomed)"
                                )
                                return false
                            }

                            val rot = ((rotation % 360) + 360) % 360
                            // Keep the SurfaceTexture buffer sized to the decoded video.
                            // If left at the default (often view size), some devices will apply implicit scaling that
                            // makes FIT/FILL behavior appear inconsistent.
                            try {
                                tv.surfaceTexture?.setDefaultBufferSize(srcW, srcH)
                            } catch (_: Throwable) {
                            }
                            // Decoder may expose a crop rect (typically just alignment padding like 1088->1080).
                            // We only use it if it looks like tiny padding; otherwise we ignore it to preserve FOV.
                            val cropValid =
                                cropR >= 0 &&
                                    cropB >= 0 &&
                                    cropR >= cropL &&
                                    cropB >= cropT &&
                                    cropL >= 0 &&
                                    cropT >= 0 &&
                                    cropR < srcW &&
                                    cropB < srcH
                            val cropWi = if (cropValid) (cropR - cropL + 1).coerceAtLeast(1) else srcW
                            val cropHi = if (cropValid) (cropB - cropT + 1).coerceAtLeast(1) else srcH
                            val cropLooksLikePadding =
                                cropValid &&
                                    (srcW - cropWi) in 0..32 &&
                                    (srcH - cropHi) in 0..32 &&
                                    cropL in 0..16 &&
                                    cropT in 0..16
                            val contentRect = if (cropLooksLikePadding) {
                                RectF(
                                    cropL.toFloat(),
                                    cropT.toFloat(),
                                    (cropR + 1).toFloat(),
                                    (cropB + 1).toFloat()
                                )
                            } else {
                                RectF(0f, 0f, srcW.toFloat(), srcH.toFloat())
                            }
                            val contentW = contentRect.width()
                            val contentH = contentRect.height()


                            val cxView = vw / 2f
                            val cyView = vh / 2f

                            // IMPORTANT (TextureView correctness):
                            // Compute scaling based on the *rotated* content dimensions.
                            // The previous approach (fit -> rotate) can clip on some devices and looks like "zoom".
                            val rotW = if (rot == 90 || rot == 270) contentH else contentW
                            val rotH = if (rot == 90 || rot == 270) contentW else contentH
                            val sx = if (rotW > 0f) (vw.toFloat() / rotW) else 1f
                            val sy = if (rotH > 0f) (vh.toFloat() / rotH) else 1f
                            val fitScale = kotlin.math.min(sx, sy)


                            val m = Matrix()
                            // 1) Move the visible content (crop rect) to origin.
                            m.postTranslate(-contentRect.left, -contentRect.top)
                            // 2) Center content at origin for clean rotate/scale.
                            m.postTranslate(-contentW / 2f, -contentH / 2f)
                            // 3) Apply rotation around origin (content center).
                            if (rot != 0) {
                                m.postRotate(rot.toFloat())
                            }
                            // 4) Flip horizontally for front camera (mirror).
                            if (isFrontCamera) {
                                m.postScale(-1f, 1f)
                            }
                            // 5) Scale for FIT/FILL.
                            if (fitScale.isFinite() && fitScale > 0f) {
                                m.postScale(fitScale, fitScale)
                            }
                            // 6) Move to view center.
                            m.postTranslate(cxView, cyView)

                            tv.setTransform(m)

                            val videoAr = if (srcH > 0) srcW.toFloat() / srcH else 0f
                            val viewAr = if (vh > 0) vw.toFloat() / vh else 0f
                            if (reason != "perFrame") {
                                Log.d(
                                    "CCTV_VIEWER",
                                    "[VIEWER MA] Transform applied (FIT) $reason decoded=${srcW}x${srcH} view=${vw}x${vh} videoAspect=${"%.3f".format(videoAr)} viewAspect=${"%.3f".format(viewAr)} scale=${"%.4f".format(fitScale)} rot=$rot cropUsed=$cropLooksLikePadding"
                                )
                            }
                            return true
                        }

                        /**
                         * 3-gate apply: only runs when view laid out, surface ready, and video dimensions known.
                         * Never clears readiness flags; only applies when transform actually succeeds.
                         * @param fromTextureUpdate true when called from onSurfaceTextureUpdated (e.g. Samsung): skip cache so we re-apply every frame.
                         */
                        fun maybeApplyTransform(tv: TextureView, fromTextureUpdate: Boolean = false) {
                            if (!viewReady || !surfaceReady) return
                            val (w, h) = if (videoW > 0 && videoH > 0) videoW to videoH else (client.getAcceptedVideoDimensions() ?: (0 to 0))
                            if (w <= 0 || h <= 0) return
                            val vw = tv.width
                            val vh = tv.height
                            if (vw <= 0 || vh <= 0) return
                            if (!fromTextureUpdate && lastAppliedVideoW == w && lastAppliedVideoH == h && lastAppliedViewW == vw && lastAppliedViewH == vh) return
                            val reason = if (firstTransformLogged) "perFrame" else "firstFrame"
                            val applied = applyTextureTransform(tv, w, h, 0, isFrontCamera, reason)
                            @Suppress("AssignedValueIsNeverRead")
                            if (applied) {
                                lastAppliedVideoW = w
                                lastAppliedVideoH = h
                                lastAppliedViewW = vw
                                lastAppliedViewH = vh
                                if (!firstTransformLogged) {
                                    Log.w(
                                        "CCTV_VIEWER",
                                        "[VIEWER MA] 🔴 [TEXTURE RENDER] First transform applied (3-gate): view=${vw}x${vh}, video=${w}x${h}"
                                    )
                                    firstTransformLogged = true
                                }
                            }
                        }

                        // NOTE:
                        // Auto-crop was an older experiment to hide tiny decoder rounding bars on some devices.
                        // It can be perceived as "zoom" and harms FOV correctness, so it's intentionally disabled.
                        @Suppress("UNUSED_PARAMETER")
                        fun detectBlackBarsOnce(tv: TextureView) = Unit

                        // TextureView: do NOT apply encoder rotation metadata.
                        // Rotation is treated as label-only to preserve the older stable behavior.

                        LaunchedEffect(
                            videoW, videoH, cropL, cropT, cropR, cropB, isFrontCamera
                        ) {
                            if (useSurfaceView) return@LaunchedEffect
                            textureViewRef?.let { tv ->
                                tv.post { maybeApplyTransform(tv) }
                                tv.postDelayed({ maybeApplyTransform(tv) }, 50)
                                tv.postDelayed({ maybeApplyTransform(tv) }, 200)
                            }
                        }

                        // (Intentionally no auto-crop effect.)

                        // Update transform when camera facing changes
                        LaunchedEffect(isFrontCamera, videoW, videoH) {
                            if (useSurfaceView) return@LaunchedEffect
                            textureViewRef?.let { tv ->
                                if (videoW > 0 && videoH > 0) tv.post { maybeApplyTransform(tv) }
                            }
                        }

                        // Pause/resume decoding when app backgrounded/foregrounded (viewer only).
                        // Keep the socket + heartbeat alive in background; only release everything on ON_DESTROY.
                        val lifecycleOwner = LocalLifecycleOwner.current
                        DisposableEffect(lifecycleOwner, client) {
                            val observer = LifecycleEventObserver { _, event ->
                                when (event) {
                                    Lifecycle.Event.ON_STOP -> {
                                        client.onAppBackgrounded()
                                    }

                                    Lifecycle.Event.ON_START -> {
                                        client.onAppForegrounded()
                                        hasPreviewFrame = false
                                        // Force re-apply transform after returning from background; layout callbacks are not always fired.
                                        textureViewRef?.let { tv ->
                                            if (!useSurfaceView) {
                                                tv.post { maybeApplyTransform(tv) }
                                                tv.postDelayed({ maybeApplyTransform(tv) }, 50)
                                                tv.postDelayed({ maybeApplyTransform(tv) }, 200)
                                                tv.postDelayed({ maybeApplyTransform(tv) }, 500)
                                            }
                                        }
                                    }

                                    Lifecycle.Event.ON_DESTROY -> {
                                        Log.d(
                                            "CCTV_VIEWER",
                                            "[VIEWER MA] 🔴 [LIFECYCLE] ON_DESTROY -> StreamClient.shutdown()"
                                        )
                                        client.shutdown()
                                    }

                                    else -> Unit
                                }
                            }
                            lifecycleOwner.lifecycle.addObserver(observer)
                            onDispose {
                                lifecycleOwner.lifecycle.removeObserver(observer)
                            }
                        }

                        // CCTVViewerTheme {
                        val scrollState = rememberScrollState()
                        val scope = rememberCoroutineScope()
                        val ipBringIntoView = remember { BringIntoViewRequester() }
                        val pwBringIntoView = remember { BringIntoViewRequester() }
                        val focusManager = LocalFocusManager.current
                        // When connection is established, remove focus from IP field so the keyboard dismisses.
                        // After first success, also clear focus when returning to DISCONNECTED so the keyboard does not auto-show on later disconnects/relaunches.
                        LaunchedEffect(state, connectionEverSucceeded) {
                            if (state == ConnectionState.STREAMING || state == ConnectionState.CONNECTED) {
                                focusManager.clearFocus()
                            }
                            if (state == ConnectionState.DISCONNECTED && connectionEverSucceeded) {
                                focusManager.clearFocus()
                            }
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                // Prevent keyboard from covering the IP/password fields.
                                .imePadding()
                                // Keep only small horizontal margin; avoid extra top/bottom padding (top inset handled by ResponsiveSingleScreen).
                                .padding(horizontal = 1.dp, vertical = 0.dp),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            // Header and video preview grouped together with minimal spacing
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Viewer", style = MaterialTheme.typography.titleLarge
                                    )
                                    Spacer(modifier = Modifier.weight(1f))
                                    Text(
                                        text = when (state) {
                                            ConnectionState.DISCONNECTED -> stringResource(R.string.state_disconnected)
                                            ConnectionState.CONNECTING -> stringResource(R.string.state_connecting)
                                            ConnectionState.AUTHENTICATED -> stringResource(R.string.state_authenticating)
                                            ConnectionState.CONNECTED -> stringResource(R.string.state_connected)
                                            ConnectionState.RECOVERING -> stringResource(R.string.state_recovering)
                                            ConnectionState.STREAMING -> stringResource(R.string.state_streaming)
                                            ConnectionState.IDLE -> stringResource(R.string.state_idle_stream_stopped)
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    IconButton(
                                        onClick = {
                                            portText = port.toString()
                                            imageTreeUriText = AppSettings.getImageTreeUri(context)
                                            showSettings = true
                                        }, modifier = Modifier.padding(start = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Settings,
                                            contentDescription = stringResource(R.string.settings_title)
                                        )
                                    }
                                }

                                if (state >= ConnectionState.AUTHENTICATED) {
                                    Text(
                                        text = stringResource(
                                            R.string.video_info, videoW, videoH, rotationDeg
                                        ),
                                        color = MaterialTheme.colorScheme.onBackground,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                // 🔹 VIDEO SURFACE
                                // Keep the *measured* surface at a stable 3:4 shape for UI consistency.
                                // IMPORTANT: Rendering preserves full FOV (FIT). If the stream aspect doesn't match the
                                // view, we will letterbox/pillarbox rather than silently crop (no "zoomed-in" preview).
                                @Suppress(
                                    "UI_COMPOSABLE_EXPECTED", "ComposableTargetInLambda"
                                ) BoxWithConstraints(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 240.dp)
                                ) {
                                    // UI contract:
                                    // Keep the viewer preview container fixed to match the Primary capture box.
                                    // Content inside is FIT/FILL using SurfaceView scaling or TextureView matrix.
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(maxWidth * (4f / 3f))
                                            .border(
                                                1.dp,
                                                MaterialTheme.colorScheme.outline,
                                                RectangleShape
                                            )
                                            .background(Color.Black)
                                            .clipToBounds()
                                            .pointerInput(Unit) {
                                                // Local accumulator (no recomposition pressure).
                                                var zoomAccum = 1f
                                                var lastSentZoom = 1f
                                                var lastSentUptimeMs =
                                                    android.os.SystemClock.uptimeMillis()
                                                detectTransformGestures { _, _, zoom, _ ->
                                                    // Defensive: ignore invalid gesture values.
                                                    val z =
                                                        if (zoom.isFinite() && zoom > 0f) zoom else 1f
                                                    val newZoom = (zoomAccum * z).coerceIn(1f, 10f)
                                                    zoomAccum = newZoom

                                                    // Coalesce: send at most ~60fps and only when meaningful change occurs.
                                                    val now = android.os.SystemClock.uptimeMillis()
                                                    val changedEnough =
                                                        kotlin.math.abs(newZoom - lastSentZoom) >= 0.02f
                                                    val timeOk = (now - lastSentUptimeMs) >= 16L
                                                    if (changedEnough && timeOk) {
                                                        lastSentZoom = newZoom
                                                        lastSentUptimeMs = now
                                                        client.sendZoom(newZoom)
                                                    }
                                                }
                                            }
                                            .graphicsLayer { clip = true },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        // IMPORTANT:
                                        // Keep the video view in composition even while DISCONNECTED so the Surface is already
                                        // created/attached before the first keyframe arrives. This reduces "first connect needs reconnect"
                                        // race conditions on some devices. We show a black overlay label above when disconnected.
                                        // CRITICAL: key(client) forces AndroidView to be recreated when port/password change.
                                        // StreamClient is created via remember(password, port); when either changes, a new
                                        // client is created. Without key(client), the AndroidView factory runs only once and
                                        // SurfaceTextureListener/SurfaceHolder.Callback capture the old client → surface
                                        // attaches to a disconnected/abandoned client until app restart.
                                        key(client) {
                                            // Compose cleanup: detach surface when this AndroidView is disposed.
                                            DisposableEffect(useSurfaceView) {
                                                onDispose {
                                                    try {
                                                        client.detachSurface()
                                                    } catch (_: Throwable) {
                                                    }
                                                }
                                            }

                                        @Suppress(
                                            "UI_COMPOSABLE_EXPECTED", "ComposableTargetInLambda"
                                        ) AndroidView(
                                            factory = { ctx ->
                                                // UNCONDITIONAL LOG: Entry point - confirms which view type is being created
                                                Log.d(
                                                    "CCTV_VIEWER",
                                                    "[VIEWER MA] 🔍 [SURFACE] AndroidView factory called: useSurfaceView=$useSurfaceView"
                                                )
                                                if (useSurfaceView) {
                                                    // UNCONDITIONAL LOG: SurfaceView path - this view type doesn't have onSurfaceTextureUpdated callback
                                                    Log.d(
                                                        "CCTV_VIEWER",
                                                        "[VIEWER MA] 🔍 [SURFACE] Using SurfaceView - frames render directly, no TextureView callbacks"
                                                    )
                                                    SurfaceView(ctx).apply {
                                                        // CRITICAL: Ensure SurfaceView is properly configured for video rendering
                                                        // IMPORTANT: In Compose, SurfaceView can appear "behind" composables.
                                                        // NOTE: Do NOT force media overlay Z-order here.
                                                        // We want Compose overlays (black cover) to be able to hide the Surface while it warms up.
                                                        // CRITICAL FIX: Use OPAQUE format for video (TRANSLUCENT can cause rendering issues on some devices)
                                                        // OPAQUE format is required for hardware-accelerated video decoding with MediaCodec
                                                        holder.setFormat(android.graphics.PixelFormat.OPAQUE)
                                                        holder.setKeepScreenOn(true) // Keep screen on during playback

                                                        // CRITICAL: Ensure hardware acceleration is enabled
                                                        try {
                                                            setLayerType(
                                                                android.view.View.LAYER_TYPE_HARDWARE,
                                                                null
                                                            )
                                                            Log.d(
                                                                "CCTV_VIEWER",
                                                                "[VIEWER MA] 🔍 [SURFACE] SurfaceView hardware acceleration enabled"
                                                            )
                                                        } catch (e: Exception) {
                                                            Log.w(
                                                                "CCTV_VIEWER",
                                                                "[VIEWER MA] ⚠️ [SURFACE] Could not set hardware acceleration",
                                                                e
                                                            )
                                                        }

                                                        // CRITICAL: Ensure view is visible and properly sized
                                                        visibility = android.view.View.VISIBLE
                                                        // Keep SurfaceView visible; we hide it (and any green warmup frames) using a Compose overlay.
                                                        alpha = 1.0f

                                                        Log.d(
                                                            "CCTV_VIEWER",
                                                            "[VIEWER MA] 🔍 [SURFACE] SurfaceView created, setting up callbacks"
                                                        )
                                                        surfaceViewRef = this
                                                        textureViewRef = null
                                                        hasPreviewFrame = false

                                                        // Prevent duplicate surface attach during initial layout churn.
                                                        // surfaceCreated() can fire more than once; re-attaching resets the decoder and can
                                                        // prolong warmup (or re-trigger green frames).
                                                        val attachOnceRef =
                                                            java.util.concurrent.atomic.AtomicBoolean(
                                                                false
                                                            )
                                                        // Some devices (observed on Samsung M30s) can report surfaceCreated(), but
                                                        // holder.surface.isValid may still be false until a later surfaceChanged/layout pass.
                                                        // To avoid getting stuck in "Starting stream" (decoder never starts),
                                                        // we retry attachment a few times if the surface is not yet valid/ready.
                                                        val attachRetryCount =
                                                            java.util.concurrent.atomic.AtomicInteger(
                                                                0
                                                            )
                                                        val maxAttachRetries = 10
                                                        // CRITICAL FIX: Track surface destruction to prevent retry loop from continuing
                                                        // after the surface is destroyed (e.g., user exits screen quickly).
                                                        // This prevents unnecessary retries, warnings, and CPU cycles.
                                                        val isSurfaceDestroyed =
                                                            java.util.concurrent.atomic.AtomicBoolean(
                                                                false
                                                            )
                                                        // Cancelable retry runnable (prevents piling up posted callbacks).
                                                        var attachRetryRunnable: Runnable? = null

                                                        /**
                                                         * Attempt to attach the current SurfaceHolder surface to the StreamClient.
                                                         *
                                                         * Intended behavior:
                                                         * - Attach exactly once per SurfaceView instance when a valid Surface is available.
                                                         * - If the Surface is not valid yet (or the view is still 0x0), retry a few times.
                                                         *
                                                         * Stability features:
                                                         * - try/catch around all attachment calls (Surface/MediaCodec edge cases).
                                                         * - fallback retry scheduled on the view thread.
                                                         * - Aborts immediately if surface is destroyed (prevents wasted retries).
                                                         */
                                                        fun tryAttachSurface(reason: String) {
                                                            try {
                                                                // CRITICAL FIX: Check if surface was destroyed before attempting attach or scheduling retries.
                                                                // This prevents the retry loop from continuing for up to ~500ms after surface destruction.
                                                                if (isSurfaceDestroyed.get()) {
                                                                    Log.d(
                                                                        "CCTV_VIEWER",
                                                                        "[VIEWER MA] 🔍 [SURFACE] tryAttachSurface($reason) aborted: surface was destroyed"
                                                                    )
                                                                    return
                                                                }

                                                                val surface = holder.surface
                                                                val surfaceValid =
                                                                    surface != null && surface.isValid
                                                                val vw = this@apply.width
                                                                val vh = this@apply.height
                                                                val attempt = attachRetryCount.get()

                                                                Log.d(
                                                                    "CCTV_VIEWER",
                                                                    "[VIEWER MA] 🔍 [SURFACE] tryAttachSurface($reason): attempt=$attempt, surfaceValid=$surfaceValid, viewSize=${vw}x${vh}, alreadyAttached=${attachOnceRef.get()}"
                                                                )

                                                                // If already attached, do nothing.
                                                                if (attachOnceRef.get()) return

                                                                // If the Surface is not ready (or view not measured), schedule a retry.
                                                                if (!surfaceValid || vw == 0 || vh == 0) {
                                                                    // CRITICAL FIX: Re-check surface destruction before scheduling retry.
                                                                    // This prevents scheduling a retry that will immediately abort.
                                                                    if (isSurfaceDestroyed.get()) {
                                                                        Log.d(
                                                                            "CCTV_VIEWER",
                                                                            "[VIEWER MA] 🔍 [SURFACE] tryAttachSurface($reason) aborted before retry: surface was destroyed"
                                                                        )
                                                                        return
                                                                    }

                                                                    val next =
                                                                        attachRetryCount.incrementAndGet()
                                                                    if (next <= maxAttachRetries) {
                                                                        // Small progressive delay so we don't spin.
                                                                        val delayMs = 50L * next
                                                                        Log.w(
                                                                            "CCTV_VIEWER",
                                                                            "[VIEWER MA] ⚠️ [SURFACE] Surface not ready for attach yet (surfaceValid=$surfaceValid, viewSize=${vw}x${vh}). " + "Retrying in ${delayMs}ms (attempt $next/$maxAttachRetries) reason=$reason"
                                                                        )
                                                                        // Cancel any previously posted retry to avoid main-thread wakeups/log spam.
                                                                        attachRetryRunnable?.let { prev ->
                                                                            try {
                                                                                this@apply.removeCallbacks(
                                                                                    prev
                                                                                )
                                                                            } catch (_: Throwable) {
                                                                            }
                                                                        }
                                                                        val r = Runnable {
                                                                            tryAttachSurface("retry_$reason")
                                                                        }
                                                                        attachRetryRunnable = r
                                                                        this@apply.postDelayed(
                                                                            r, delayMs
                                                                        )
                                                                    } else {
                                                                        Log.e(
                                                                            "CCTV_VIEWER",
                                                                            "[VIEWER MA] 🔴 [SURFACE] Giving up attachSurface after $maxAttachRetries retries (last surfaceValid=$surfaceValid, viewSize=${vw}x${vh}). " + "Video may remain stuck at 'Starting stream'. Try switching to TextureView in Settings."
                                                                        )
                                                                    }
                                                                    return
                                                                }

                                                                // Surface is valid. Attach exactly once.
                                                                if (attachOnceRef.compareAndSet(
                                                                        false, true
                                                                    )
                                                                ) {
                                                                    Log.d(
                                                                        "CCTV_VIEWER",
                                                                        "[VIEWER MA] 🔍 [SURFACE] Attaching Surface to client (reason=$reason)"
                                                                    )
                                                                    client.attachSurface(surface)
                                                                    hasPreviewFrame = false
                                                                    Log.d(
                                                                        "CCTV_VIEWER",
                                                                        "[VIEWER MA] 🔍 [SURFACE] attachSurface() completed (reason=$reason)"
                                                                    )
                                                                } else {
                                                                    Log.w(
                                                                        "CCTV_VIEWER",
                                                                        "[VIEWER MA] ⚠️ [SURFACE] Skipping attachSurface(): already attached (reason=$reason)"
                                                                    )
                                                                }
                                                            } catch (t: Throwable) {
                                                                Log.e(
                                                                    "CCTV_VIEWER",
                                                                    "[VIEWER MA] 🔴 [SURFACE] tryAttachSurface($reason) failed",
                                                                    t
                                                                )
                                                            }
                                                        }

                                                        // Add layout listener to log view size for diagnostics
                                                        addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                                                            val width = right - left
                                                            val height = bottom - top
                                                            val wasZeroSize =
                                                                (oldRight - oldLeft) == 0 || (oldBottom - oldTop) == 0
                                                            val isZeroSize =
                                                                width == 0 || height == 0

                                                            if (wasZeroSize != isZeroSize || !wasZeroSize) {
                                                                Log.d(
                                                                    "CCTV_VIEWER",
                                                                    "[VIEWER MA] 🔍 [SURFACE] SurfaceView layout changed: ${width}x${height}, visibility=${visibility}, alpha=$alpha, parent=${parent?.javaClass?.simpleName}"
                                                                )
                                                                if (isZeroSize) {
                                                                    Log.w(
                                                                        "CCTV_VIEWER",
                                                                        "[VIEWER MA] ⚠️ [SURFACE] WARNING: SurfaceView has ZERO SIZE! Video will not render. Check layout constraints."
                                                                    )
                                                                }
                                                            }
                                                        }

                                                        // Check if surface already exists (e.g., when AndroidView is recomposed)
                                                        // If surface is already available, attach immediately
                                                        val surface = holder.surface
                                                        val surfaceValid = surface.isValid
                                                        Log.d(
                                                            "CCTV_VIEWER",
                                                            "🔍 [SURFACE] SurfaceView created, checking surface validity: isValid=$surfaceValid"
                                                        )
                                                        if (surfaceValid) {
                                                            Log.d(
                                                                "CCTV_VIEWER",
                                                                "[VIEWER MA] 🔍 [SURFACE] SurfaceView surface already valid, attaching immediately"
                                                            )
                                                            try {
                                                                tryAttachSurface("immediate_valid_surface")
                                                            } catch (t: Throwable) {
                                                                Log.e(
                                                                    "CCTV_VIEWER",
                                                                    "[VIEWER MA] SurfaceView attach failed (immediate)",
                                                                    t
                                                                )
                                                            }
                                                        } else {
                                                            Log.d(
                                                                "CCTV_VIEWER",
                                                                "[VIEWER MA] 🔍 [SURFACE] Surface not valid yet, waiting for surfaceCreated() callback"
                                                            )
                                                            // Fallback: schedule an attach attempt in case surfaceCreated fires before validity flips.
                                                            tryAttachSurface("immediate_invalid_surface")
                                                        }

                                                        Log.d(
                                                            "CCTV_VIEWER",
                                                            "[VIEWER MA] 🔍 [SURFACE] Registering SurfaceHolder.Callback"
                                                        )
                                                        holder.addCallback(object :
                                                            android.view.SurfaceHolder.Callback {
                                                            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                                                                // UNCONDITIONAL LOG: SurfaceView entry point - confirms Surface is ready
                                                                Log.d(
                                                                    "CCTV_VIEWER",
                                                                    "[VIEWER MA] 🔍 [SURFACE] SurfaceView.surfaceCreated CALLED"
                                                                )
                                                                try {
                                                                    // CRITICAL FIX: Reset surface destruction flag and retry count for new surface lifecycle
                                                                    isSurfaceDestroyed.set(false)
                                                                    attachRetryCount.set(0)

                                                                    // CRITICAL: Ensure format is set on the holder BEFORE getting the surface
                                                                    // This must be done before MediaCodec uses the Surface
                                                                    try {
                                                                        holder.setFormat(android.graphics.PixelFormat.OPAQUE)
                                                                        Log.d(
                                                                            "CCTV_VIEWER",
                                                                            "[VIEWER MA] 🔍 [SURFACE] Set OPAQUE format on SurfaceHolder in surfaceCreated"
                                                                        )
                                                                    } catch (e: Exception) {
                                                                        Log.w(
                                                                            "CCTV_VIEWER",
                                                                            "[VIEWER MA] ⚠️ [SURFACE] Could not set format in surfaceCreated",
                                                                            e
                                                                        )
                                                                    }

                                                                    // Attempt attach using our retry-capable helper.
                                                                    // On some devices surfaceCreated arrives before surface becomes valid.
                                                                    tryAttachSurface("surfaceCreated")
                                                                    val surface = holder.surface
                                                                    val surfaceValid =
                                                                        surface != null && surface.isValid

                                                                    // UNCONDITIONAL LOG: Surface validity check - required for rendering
                                                                    if (surfaceValid) {
                                                                        // CRITICAL: Log view state when surface is ready - helps diagnose rendering issues
                                                                        this@apply.post {
                                                                            val viewWidth =
                                                                                this@apply.width
                                                                            val viewHeight =
                                                                                this@apply.height
                                                                            val viewVisibility =
                                                                                this@apply.visibility
                                                                            val viewAlpha =
                                                                                this@apply.alpha
                                                                            val parent =
                                                                                this@apply.parent
                                                                            Log.d(
                                                                                "CCTV_VIEWER",
                                                                                "[VIEWER MA] 🔍 [SURFACE] Surface is VALID - view state: ${viewWidth}x${viewHeight}, visibility=$viewVisibility, alpha=$viewAlpha, parent=${parent?.javaClass?.simpleName}"
                                                                            )
                                                                            if (viewWidth == 0 || viewHeight == 0) {
                                                                                Log.e(
                                                                                    "CCTV_VIEWER",
                                                                                    "[VIEWER MA] 🔴 [SURFACE] CRITICAL: View size is ${viewWidth}x${viewHeight}! Frames may render but won't be visible."
                                                                                )
                                                                            }
                                                                        }

                                                                        Log.d(
                                                                            "CCTV_VIEWER",
                                                                            "[VIEWER MA] 🔍 [SURFACE] Surface is VALID - attach should now succeed (see tryAttachSurface logs)."
                                                                        )
                                                                    } else {
                                                                        Log.e(
                                                                            "CCTV_VIEWER",
                                                                            "[VIEWER MA] 🔴 [SURFACE] CRITICAL: Surface is NULL or INVALID in surfaceCreated! Frames will not render."
                                                                        )
                                                                    }
                                                                } catch (t: Throwable) {
                                                                    Log.e(
                                                                        "CCTV_VIEWER",
                                                                        "[VIEWER MA] 🔴 [SURFACE] EXCEPTION in surfaceCreated",
                                                                        t
                                                                    )
                                                                }
                                                            }

                                                            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                                                                try {
                                                                    // CRITICAL FIX: Set surface destruction flag to abort any pending retries
                                                                    // This prevents the retry loop from continuing for up to ~500ms after surface destruction
                                                                    isSurfaceDestroyed.set(true)
                                                                    Log.d(
                                                                        "CCTV_VIEWER",
                                                                        "[VIEWER MA] 🔍 [SURFACE] SurfaceView.surfaceDestroyed CALLED - aborting any pending attach retries"
                                                                    )
                                                                    // Cancel any posted retry runnable.
                                                                    attachRetryRunnable?.let { r ->
                                                                        try {
                                                                            this@apply.removeCallbacks(
                                                                                r
                                                                            )
                                                                        } catch (_: Throwable) {
                                                                        }
                                                                    }
                                                                    attachRetryRunnable = null

                                                                    client.detachSurface()
                                                                } catch (_: Throwable) {
                                                                }
                                                                try {
                                                                    attachOnceRef.set(false)
                                                                } catch (_: Throwable) {
                                                                }
                                                                if (surfaceViewRef === this@apply) {
                                                                    surfaceViewRef = null
                                                                }
                                                                hasPreviewFrame = false
                                                            }

                                                            override fun surfaceChanged(
                                                                holder: android.view.SurfaceHolder,
                                                                format: Int,
                                                                width: Int,
                                                                height: Int
                                                            ) {
                                                                // UNCONDITIONAL LOG: Surface size changed - important for rendering
                                                                Log.d(
                                                                    "CCTV_VIEWER",
                                                                    "[VIEWER MA] 🔍 [SURFACE] SurfaceView.surfaceChanged: ${width}x${height}, format=$format"
                                                                )
                                                                if (width == 0 || height == 0) {
                                                                    Log.w(
                                                                        "CCTV_VIEWER",
                                                                        "[VIEWER MA] ⚠️ [SURFACE] WARNING: Surface size is ${width}x${height}! Video will not render properly."
                                                                    )
                                                                }
                                                                // IMPORTANT:
                                                                // Do NOT re-attach on every size change.
                                                                // On some devices (and especially in Compose) surfaceChanged can fire multiple times
                                                                // during initial layout; re-attaching resets the decoder and can prevent output forever.
                                                                // The initial attach is done in surfaceCreated() and is sufficient.
                                                                //
                                                                // Fallback (Samsung M30s observed):
                                                                // surfaceCreated can occur before the Surface becomes valid; surfaceChanged is often the
                                                                // first point where the Surface is actually usable. If we haven't attached yet, try now.
                                                                if (!attachOnceRef.get()) {
                                                                    tryAttachSurface("surfaceChanged_${width}x${height}")
                                                                }
                                                            }
                                                        })

                                                        // Compose can remove AndroidView without immediately triggering surfaceDestroyed().
                                                        // Ensure we abort retries and detach the client surface when the view is detached.
                                                        addOnAttachStateChangeListener(object :
                                                            android.view.View.OnAttachStateChangeListener {
                                                            override fun onViewAttachedToWindow(v: android.view.View) =
                                                                Unit

                                                            override fun onViewDetachedFromWindow(v: android.view.View) {
                                                                try {
                                                                    isSurfaceDestroyed.set(true)
                                                                    attachRetryRunnable?.let { r ->
                                                                        try {
                                                                            this@apply.removeCallbacks(
                                                                                r
                                                                            )
                                                                        } catch (_: Throwable) {
                                                                        }
                                                                    }
                                                                    attachRetryRunnable = null
                                                                    try {
                                                                        client.detachSurface()
                                                                    } catch (_: Throwable) {
                                                                    }
                                                                } catch (_: Throwable) {
                                                                }
                                                            }
                                                        })
                                                    }
                                                } else {
                                                    // UNCONDITIONAL LOG: TextureView path - this view type uses onSurfaceTextureUpdated callback
                                                    Log.d(
                                                        "CCTV_VIEWER",
                                                        "[VIEWER MA] 🔍 [SURFACE] Using TextureView - onSurfaceTextureUpdated callback will fire when frames render"
                                                    )
                                                    // CRITICAL FIX: Do NOT capture rotationDeg here - it may be 0 at view creation time
                                                    // Rotation updates are handled by LaunchedEffect(videoW, videoH, rotationDeg, ...) which
                                                    // applies transforms when rotation actually changes. Callbacks here use 0 (matches backup).
                                                    TextureView(ctx).apply {
                                                        Log.d(
                                                            "CCTV_VIEWER",
                                                            "[VIEWER MA] 🔍 [SURFACE] TextureView created, setting up callbacks"
                                                        )

                                                        // FORCE MATCH_PARENT: Prevents View from expanding to video buffer size (1920x1080)
                                                        // and causing the "Crop/Zoom" effect within the 3:4 container.
                                                        layoutParams = android.view.ViewGroup.LayoutParams(
                                                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                                        )
                                                        Log.d("CCTV_VIEWER", "[VIEWER MA] 🔍 [SURFACE] TextureView created (MATCH_PARENT forced)")


                                                        textureViewRef = this
                                                        surfaceViewRef = null
                                                        hasPreviewFrame = false
                                                        // 3-gate: new view → reset so we only apply when layout + surface + video are ready
                                                        viewReady = false
                                                        surfaceReady = false
                                                        lastAppliedVideoW = 0
                                                        lastAppliedVideoH = 0
                                                        lastAppliedViewW = 0
                                                        lastAppliedViewH = 0
                                                        firstTransformLogged = false
                                                        // CRITICAL: Set isOpaque=false (matches backup working version)
                                                        // isOpaque=true can cause rendering issues on some devices
                                                        isOpaque = false
                                                        // CRITICAL FIX: TextureView doesn't support setBackgroundColor()
                                                        // Background is handled by Compose Modifier.background() instead
                                                        // Attempting to call setBackgroundColor() will crash with UnsupportedOperationException
                                                        // Ensure view is visible (alpha = 1.0)
                                                        // Keep TextureView hidden until first rendered frame callback (safe).
                                                        alpha = 0.0f
                                                        // Ensure view is not invisible
                                                        visibility = android.view.View.VISIBLE
                                                        val surfaceRef =
                                                            arrayOfNulls<android.view.Surface>(1)
                                                        surfaceTextureListener = object :
                                                            TextureView.SurfaceTextureListener {
                                                            override fun onSurfaceTextureAvailable(
                                                                surface: SurfaceTexture,
                                                                width: Int,
                                                                height: Int
                                                            ) {
                                                                // UNCONDITIONAL LOG: Entry point - confirms callback is registered and TextureView is ready
                                                                Log.d(
                                                                    "CCTV_VIEWER",
                                                                    "[VIEWER MA] 🔍 [TEXTURE] onSurfaceTextureAvailable CALLED: ${width}x${height}, textureView=${this@apply.width}x${this@apply.height}, visibility=${this@apply.visibility}"
                                                                )

                                                                try {
                                                                    // CRITICAL FIX: Create Surface FIRST (matching backup working version)
                                                                    // setDefaultBufferSize will be called later in applyTextureTransform when actual video dimensions are known
                                                                    // Setting buffer size here with potentially wrong dimensions (videoW/videoH may be 0) locks in incorrect size
                                                                    val surfaceObj =
                                                                        android.view.Surface(surface)
                                                                    val surfaceValid =
                                                                        surfaceObj.isValid

                                                                    // UNCONDITIONAL LOG: Surface validity - if false, rendering will fail
                                                                    if (surfaceValid) {
                                                                        Log.d(
                                                                            "CCTV_VIEWER",
                                                                            "[VIEWER MA] 🔍 [TEXTURE] Surface created and VALID - ready for MediaCodec"
                                                                        )
                                                                    } else {
                                                                        Log.e(
                                                                            "CCTV_VIEWER",
                                                                            "[VIEWER MA] 🔴 [TEXTURE] Surface created but INVALID - MediaCodec will not render frames!"
                                                                        )
                                                                        Toast.makeText(
                                                                            ctx,
                                                                            "Surface invalid - video may not render",
                                                                            Toast.LENGTH_SHORT
                                                                        ).show()
                                                                    }

                                                                    surfaceRef[0] = surfaceObj
                                                                    client.attachSurface(surfaceObj)

                                                                    // UNCONDITIONAL LOG: Confirms Surface attachment - decoder should now use this Surface
                                                                    Log.d(
                                                                        "CCTV_VIEWER",
                                                                        "[VIEWER MA] 🔍 [TEXTURE] Surface attached to decoder - decoder.releaseOutputBuffer(render=true) should trigger onSurfaceTextureUpdated"
                                                                    )

                                                                    surfaceReady = true
                                                                    hasPreviewFrame = false
                                                                    // Reset texture update tracking for new surface
                                                                    textureUpdateCounter = 0L
                                                                    textureUpdateFirstTime = 0L
                                                                    maybeApplyTransform(this@apply)
                                                                } catch (t: Throwable) {
                                                                    Log.e(
                                                                        "CCTV_VIEWER",
                                                                        "[VIEWER MA] 🔴 [TEXTURE] EXCEPTION in onSurfaceTextureAvailable",
                                                                        t
                                                                    )
                                                                    Toast.makeText(
                                                                        ctx, ctx.getString(
                                                                            R.string.toast_preview_init_failed,
                                                                            t.javaClass.simpleName
                                                                        ), Toast.LENGTH_SHORT
                                                                    ).show()
                                                                }
                                                            }

                                                            override fun onSurfaceTextureSizeChanged(
                                                                surface: SurfaceTexture,
                                                                width: Int,
                                                                height: Int
                                                            ) {
                                                                maybeApplyTransform(this@apply)
                                                            }

                                                            override fun onSurfaceTextureDestroyed(
                                                                surface: SurfaceTexture
                                                            ): Boolean {
                                                                Log.d(
                                                                    "CCTV_VIEWER",
                                                                    "[VIEWER MA] Texture destroyed"
                                                                )
                                                                try {
                                                                    client.detachSurface()
                                                                } catch (_: Throwable) {
                                                                }
                                                                try {
                                                                    surfaceRef[0]?.release()
                                                                } catch (_: Exception) {
                                                                }
                                                                surfaceRef[0] = null
                                                                if (textureViewRef === this@apply) {
                                                                    textureViewRef = null
                                                                }
                                                                surfaceReady = false
                                                                hasPreviewFrame = false
                                                                return true
                                                            }

                                                            override fun onSurfaceTextureUpdated(
                                                                surface: SurfaceTexture
                                                            ) {
                                                                // UNCONDITIONAL LOG: This MUST execute every time a frame is rendered to TextureView
                                                                // If you don't see this log, onSurfaceTextureUpdated is not being called at all
                                                                Log.d(
                                                                    "CCTV_VIEWER",
                                                                    "[VIEWER MA] 🔵 [TEXTURE RENDER] onSurfaceTextureUpdated CALLED - Frame rendered to TextureView"
                                                                )

                                                                try {
                                                                    // Track texture update frequency for diagnostics
                                                                    val now =
                                                                        System.currentTimeMillis()
                                                                    val isFirstFrame =
                                                                        textureUpdateCounter == 0L
                                                                    if (isFirstFrame) {
                                                                        textureUpdateFirstTime = now
                                                                        Log.w(
                                                                            "CCTV_VIEWER",
                                                                            "[VIEWER MA] 🔴 [TEXTURE RENDER] FIRST FRAME rendered to TextureView"
                                                                        )
                                                                        // CRITICAL FIX: Set alpha to 1.0 when first frame is rendered
                                                                        // TextureView starts with alpha=0.0 to hide green/uninitialized frames
                                                                        // Once we have a real frame, make it visible
                                                                        if (this@apply.alpha < 1.0f) {
                                                                            this@apply.alpha = 1.0f
                                                                            Log.d(
                                                                                "CCTV_VIEWER",
                                                                                "[VIEWER MA] 🔵 [TEXTURE RENDER] Setting TextureView alpha to 1.0 (first frame rendered)"
                                                                            )
                                                                        }
                                                                    }
                                                                    textureUpdateCounter++

                                                                    // Log first 10 frames, then every 30th frame for FPS calculation
                                                                    val shouldLogDetailed =
                                                                        textureUpdateCounter <= 10 || textureUpdateCounter % 30 == 0L
                                                                    if (shouldLogDetailed) {
                                                                        val elapsedSinceFirst =
                                                                            if (textureUpdateFirstTime > 0) now - textureUpdateFirstTime else 0L
                                                                        val fps =
                                                                            if (elapsedSinceFirst > 0) (textureUpdateCounter * 1000.0 / elapsedSinceFirst) else 0.0
                                                                        Log.d(
                                                                            "CCTV_VIEWER",
                                                                            "[VIEWER MA] 🔵 [TEXTURE RENDER] Frame #$textureUpdateCounter rendered - view=${this@apply.width}x${this@apply.height}, visibility=${this@apply.visibility}, alpha=${this@apply.alpha}, fps=${
                                                                                String.format(
                                                                                    "%.1f", fps
                                                                                )
                                                                            }"
                                                                        )
                                                                    }

                                                                    // Re-apply every frame when we have dimensions (Samsung M30s etc. reset matrix per frame). 3-gate inside.
                                                                    maybeApplyTransform(this@apply, fromTextureUpdate = true)
                                                                } catch (e: Exception) {
                                                                    Log.e(
                                                                        "CCTV_VIEWER",
                                                                        "[VIEWER MA] 🔴 [TEXTURE RENDER] EXCEPTION in onSurfaceTextureUpdated",
                                                                        e
                                                                    )
                                                                }
                                                            }
                                                        }
                                                        addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                                                            val w = right - left
                                                            val h = bottom - top
                                                            if (w > 0 && h > 0) viewReady = true
                                                            if (w != oldRight - oldLeft || h != oldBottom - oldTop) {
                                                                maybeApplyTransform(this)
                                                            }
                                                        }

                                                    }

                                                }
                                            },
                                            update = { view ->
                                                // UNCONDITIONAL LOG: Update block - confirms view size after layout
                                                val viewWidth = view.width
                                                val viewHeight = view.height
                                                val viewVisibility = view.visibility
                                                val viewAlpha = view.alpha
                                                Log.d(
                                                    "CCTV_VIEWER",
                                                    "[VIEWER MA] 🔍 [SURFACE] AndroidView update: view=${viewWidth}x${viewHeight}, visibility=$viewVisibility, alpha=$viewAlpha, type=${view.javaClass.simpleName}"
                                                )

                                                if (viewWidth == 0 || viewHeight == 0) {
                                                    Log.w(
                                                        "CCTV_VIEWER",
                                                        "[VIEWER MA] ⚠️ [SURFACE] WARNING: View has zero size in update block! Video may not render."
                                                    )
                                                }

                                                // Handle SurfaceView updates (ensure visibility)
                                                if (useSurfaceView && view is SurfaceView) {
                                                    if (view.visibility != android.view.View.VISIBLE) {
                                                        Log.w(
                                                            "CCTV_VIEWER",
                                                            "[VIEWER MA] ⚠️ [SURFACE] SurfaceView visibility is ${view.visibility}, setting to VISIBLE"
                                                        )
                                                        view.visibility = android.view.View.VISIBLE
                                                    }
                                                }

                                                // For TextureView, alpha handling remains inside TextureView itself (it has onSurfaceTextureUpdated callbacks).
                                            },
                                            /*modifier = Modifier
                                                .fillMaxSize()*/
                                            modifier = Modifier
                                                // Do NOT resize the video container based on stream dimensions.
                                                // The outer Box is fixed (3:4) to match Primary capture UI.
                                                // Fit/letterbox happens inside the SurfaceView (MediaCodec scaling) or TextureView matrix.
                                                .fillMaxSize()
                                                .graphicsLayer { clip = true })
                                        }

                                        // SurfaceView pixel validation (Nord CE4 / green warmup protection).
                                        // We keep the black overlay until PixelCopy confirms non-green pixels.
                                        LaunchedEffect(
                                            useSurfaceView,
                                            decoderReportedFirstRender,
                                            hasPreviewFrame,
                                            surfaceViewRef
                                        ) {
                                            if (!useSurfaceView) return@LaunchedEffect
                                            if (hasPreviewFrame) return@LaunchedEffect
                                            if (!decoderReportedFirstRender) return@LaunchedEffect
                                            val sv = surfaceViewRef ?: return@LaunchedEffect

                                            // PixelCopy reads pixels from a SurfaceView (API 26+). We'll sample a small center region.
                                            fun isPreviewReadyFromCopy(fullSizeCopy: Bitmap): Boolean {
                                                // We treat the Surface as "ready" only when:
                                                // - The center region is NOT a near-solid color (green screen is typically very low-variance)
                                                // - It's not strongly green-dominant
                                                // - It's not essentially black
                                                val fullW = fullSizeCopy.width
                                                val fullH = fullSizeCopy.height
                                                if (fullW <= 0 || fullH <= 0) return false

                                                val sampleSide = 48
                                                val side =
                                                    sampleSide.coerceAtMost(minOf(fullW, fullH))
                                                val left = ((fullW - side) / 2).coerceAtLeast(0)
                                                val top = ((fullH - side) / 2).coerceAtLeast(0)

                                                var sumR = 0L
                                                var sumG = 0L
                                                var sumB = 0L
                                                var sumL = 0L
                                                var sumSqL = 0L
                                                var minL = 255
                                                var maxL = 0
                                                val pixels = IntArray(side * side)
                                                try {
                                                    fullSizeCopy.getPixels(
                                                        pixels, 0, side, left, top, side, side
                                                    )
                                                } catch (_: Throwable) {
                                                    return false
                                                }
                                                for (c in pixels) {
                                                    val r = (c shr 16) and 0xFF
                                                    val g = (c shr 8) and 0xFF
                                                    val b = c and 0xFF
                                                    sumR += r
                                                    sumG += g
                                                    sumB += b
                                                    val l = (r * 3 + g * 6 + b) / 10 // cheap luma
                                                    sumL += l.toLong()
                                                    sumSqL += (l.toLong() * l.toLong())
                                                    if (l < minL) minL = l
                                                    if (l > maxL) maxL = l
                                                }
                                                val n = pixels.size.coerceAtLeast(1)
                                                val meanR = sumR.toDouble() / n
                                                val meanG = sumG.toDouble() / n
                                                val meanB = sumB.toDouble() / n
                                                val meanL = sumL.toDouble() / n
                                                val varL = (sumSqL.toDouble() / n) - (meanL * meanL)
                                                val contrast = maxL - minL

                                                // Strong-green heuristic (covers "solid green" and "mostly green" outputs).
                                                val greenDominant =
                                                    meanG > 60.0 && meanG > meanR * 1.4 && meanG > meanB * 1.4
                                                // Near-solid color heuristic (green screen tends to have very low variance/contrast).
                                                val lowDetail = varL < 120.0 || contrast < 18
                                                // Near-black heuristic (PixelCopy can return black early).
                                                val tooDark = meanL < 10.0

                                                // Log occasionally for diagnostics.
                                                if (surfaceReadyStreak == 0) {
                                                    Log.d(
                                                        "CCTV_VIEWER",
                                                        "[VIEWER MA] 🔍 [SURFACE] PixelCopy metrics: meanRGB=${
                                                            "%.1f".format(
                                                                meanR
                                                            )
                                                        },${"%.1f".format(meanG)},${
                                                            "%.1f".format(
                                                                meanB
                                                            )
                                                        } meanL=${"%.1f".format(meanL)} varL=${
                                                            "%.1f".format(
                                                                varL
                                                            )
                                                        } contrast=$contrast greenDom=$greenDominant lowDetail=$lowDetail"
                                                    )
                                                }

                                                if (tooDark) return false
                                                if (greenDominant && lowDetail) return false
                                                if (lowDetail) return false
                                                return true
                                            }

                                            suspend fun pixelCopy(
                                                view: SurfaceView, dest: Bitmap
                                            ): Int {
                                                return try {
                                                    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                                                        try {
                                                            PixelCopy.request(
                                                                view, dest, { result: Int ->
                                                                    if (!cont.isCompleted) cont.resumeWith(
                                                                        Result.success(result)
                                                                    )
                                                                }, Handler(Looper.getMainLooper())
                                                            )
                                                        } catch (t: Throwable) {
                                                            Log.w(
                                                                "CCTV_VIEWER",
                                                                "[VIEWER MA] ⚠️ [SURFACE] PixelCopy.request failed",
                                                                t
                                                            )
                                                            if (!cont.isCompleted) cont.resumeWith(
                                                                Result.success(PixelCopy.ERROR_UNKNOWN)
                                                            )
                                                        }
                                                    }
                                                } catch (t: Throwable) {
                                                    Log.w(
                                                        "CCTV_VIEWER",
                                                        "[VIEWER MA] ⚠️ [SURFACE] PixelCopy suspend failed",
                                                        t
                                                    )
                                                    PixelCopy.ERROR_UNKNOWN
                                                }
                                            }

                                            try {
                                                val startMs = android.os.SystemClock.uptimeMillis()
                                                var bmp: Bitmap? = null
                                                var bmpW = 0
                                                var bmpH = 0
                                                while (!hasPreviewFrame && android.os.SystemClock.uptimeMillis() - startMs < 20_000L) {
                                                    val vw = sv.width
                                                    val vh = sv.height
                                                    if (vw <= 0 || vh <= 0) {
                                                        surfaceReadyStreak = 0
                                                        delay(200)
                                                        continue
                                                    }

                                                    if (bmp == null || bmpW != vw || bmpH != vh) {
                                                        try {
                                                            bmp?.recycle()
                                                        } catch (_: Throwable) {
                                                        }
                                                        bmp = try {
                                                            createBitmap(
                                                                vw, vh, Bitmap.Config.ARGB_8888
                                                            )
                                                        } catch (t: Throwable) {
                                                            Log.w(
                                                                "CCTV_VIEWER",
                                                                "[VIEWER MA] ⚠️ [SURFACE] Could not allocate PixelCopy bitmap ${vw}x${vh}",
                                                                t
                                                            )
                                                            surfaceReadyStreak = 0
                                                            delay(500)
                                                            continue
                                                        }
                                                        bmpW = vw
                                                        bmpH = vh
                                                    }

                                                    val localBmp = bmp
                                                    val res = pixelCopy(sv, localBmp)
                                                    if (res == PixelCopy.SUCCESS) {
                                                        val ready = isPreviewReadyFromCopy(localBmp)
                                                        if (ready) {
                                                            surfaceReadyStreak++
                                                            if (surfaceReadyStreak >= 3) {
                                                                Log.w(
                                                                    "CCTV_VIEWER",
                                                                    "[VIEWER MA] ✅ [SURFACE] PixelCopy detected stable preview -> visible (streak=$surfaceReadyStreak)"
                                                                )
                                                                hasPreviewFrame = true
                                                                break
                                                            }
                                                        } else {
                                                            surfaceReadyStreak = 0
                                                        }
                                                    }
                                                    delay(250)
                                                }
                                                try {
                                                    bmp?.recycle()
                                                } catch (_: Throwable) {
                                                }
                                            } finally {
                                                // reset streak if effect is restarted
                                                if (!hasPreviewFrame) surfaceReadyStreak = 0
                                            }
                                        }

                                        // Derive a single UI truth for overlays (Media3-style discipline).
                                        // Then refine Buffering -> Rebuffering when we previously had preview and now stall.
                                        val basePlaybackState = remember(state, hasPreviewFrame) {
                                            derivePlaybackState(
                                                connectionState = state,
                                                hasPreviewFrame = hasPreviewFrame
                                            )
                                        }
                                        val playbackState = run {
                                            if (basePlaybackState != PlaybackState.Buffering) return@run basePlaybackState
                                            if (!hadPreviewEver) return@run basePlaybackState
                                            val hs = healthSnapshot ?: return@run basePlaybackState
                                            val lastRx = hs.lastFrameRxUptimeMs
                                            val sinceRx =
                                                if (lastRx > 0L) (hs.nowUptimeMs - lastRx) else Long.MAX_VALUE
                                            // Conservative threshold: only call it "rebuffering" after a noticeable stall.
                                            if (sinceRx >= 1_200L) PlaybackState.Rebuffering else basePlaybackState
                                        }

                                        // Status overlay above the Surface (drawn AFTER AndroidView so it actually covers it).
                                        if (playbackState != PlaybackState.Playing) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(Color.Black),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = when (playbackState) {
                                                        PlaybackState.Idle -> "No Connection"
                                                        PlaybackState.Connecting -> "Connecting…"
                                                        PlaybackState.Buffering -> "Starting stream…"
                                                        PlaybackState.NoVideo -> "No Video"
                                                        PlaybackState.Rebuffering -> "Recovering…"
                                                        PlaybackState.Playing -> "" // not shown
                                                    },
                                                    color = Color.White,
                                                    style = MaterialTheme.typography.titleMedium
                                                )
                                            }
                                        }

                                        // 2. Overlays (Z-Index > Video)
                                        // Connection/streaming badge
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopStart)
                                                .padding(8.dp)
                                                .background(
                                                    color = MaterialTheme.colorScheme.surface.copy(
                                                        alpha = 0.7f
                                                    ), shape = RectangleShape
                                                )
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            val badgeText = when (playbackState) {
                                                PlaybackState.Playing, PlaybackState.Rebuffering -> {
                                                    if (isRecording) "Recording" else "Preview"
                                                }

                                                PlaybackState.Connecting, PlaybackState.Buffering -> stringResource(
                                                    R.string.state_connecting

                                                )

                                                PlaybackState.NoVideo -> stringResource(R.string.state_connected)
                                                PlaybackState.Idle -> stringResource(R.string.state_idle)
                                            }
                                            Text(
                                                text = badgeText,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }

                                        // Debug-only stream health overlay (read-only; does not affect pipeline).
                                        //
                                        // NOTE: This project may have BuildConfig generation disabled, so do not rely on
                                        // BuildConfig.DEBUG here. Keep the feature guarded solely by this constant.
                                        if (DEBUG_STREAM_STATS_OVERLAY) {
                                            val hs = healthSnapshot
                                            if (hs != null) {
                                                val sinceRx =
                                                    if (hs.lastFrameRxUptimeMs > 0L) (hs.nowUptimeMs - hs.lastFrameRxUptimeMs) else -1L
                                                val sinceRender =
                                                    if (hs.lastFrameRenderUptimeMs > 0L) (hs.nowUptimeMs - hs.lastFrameRenderUptimeMs) else -1L
                                                val sincePong =
                                                    if (hs.lastPongUptimeMs > 0L) (hs.nowUptimeMs - hs.lastPongUptimeMs) else -1L
                                                Box(
                                                    modifier = Modifier
                                                        .align(Alignment.TopEnd)
                                                        .padding(8.dp)
                                                        .background(
                                                            color = Color.Black.copy(alpha = 0.55f),
                                                            shape = RectangleShape
                                                        )
                                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                                ) {
                                                    Text(
                                                        text = "dbg state=${hs.connectionState}\n" + "playback=$playbackState\n" + "rx=${sinceRx}ms render=${sinceRender}ms pong=${sincePong}ms\n" + "dq=${hs.decodeQueueSize} decode=${hs.decodeRunning} surf=${hs.surfaceReady}\n" + "waitIDR=${hs.waitingForKeyframe} drops=${hs.rxOverloadDropCount}\n" + "previewVisible=${hs.previewVisible}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = Color.White
                                                    )
                                                }
                                            }
                                        }

                                        // Loading overlay while connecting (kept for compatibility with existing UX)
                                        // (Removed) duplicate connecting overlay:
                                        // playbackState overlay above already shows "Connecting…" and covers the surface.

                                        if (showTimestamp && timestampText.isNotBlank()) {
                                            Text(
                                                text = timestampText,
                                                color = Color.White,
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier
                                                    .align(Alignment.BottomStart)
                                                    .padding(8.dp)
                                                    .background(
                                                        Color.Black.copy(alpha = 0.45f),
                                                        RectangleShape
                                                    )
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }

                                        // Blinking LED status indicator
                                        val ledColor = when (state) {
                                            ConnectionState.STREAMING, ConnectionState.RECOVERING -> if (isRecording) Color.Red else Color.Green

                                            else -> Color.White
                                        }
                                        val infiniteTransition =
                                            rememberInfiniteTransition(label = "led_blink")
                                        val alpha by infiniteTransition.animateFloat(
                                            initialValue = 1f,
                                            targetValue = 0.3f,
                                            animationSpec = infiniteRepeatable(
                                                animation = tween(1000, easing = LinearEasing),
                                                repeatMode = RepeatMode.Reverse
                                            ),
                                            label = "led_alpha"
                                        )

                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(8.dp)
                                                .size(16.dp)
                                                .background(
                                                    color = ledColor.copy(alpha = alpha),
                                                    shape = CircleShape
                                                )
                                        )
                                    }
                                }
                            }

                            if (showSettings) {
                                AlertDialog(
                                    onDismissRequest = { showSettings = false },
                                    title = { Text(stringResource(R.string.settings_title)) },
                                    text = {
                                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            OutlinedTextField(
                                                value = portText,
                                                onValueChange = {
                                                    portText =
                                                        it.filter { c -> c.isDigit() }.take(5)
                                                },
                                                label = { Text(stringResource(R.string.settings_port)) },
                                                supportingText = {
                                                    Text(
                                                        if (state == ConnectionState.DISCONNECTED) stringResource(
                                                            R.string.settings_port_valid_range
                                                        )
                                                        else stringResource(R.string.settings_port_valid_range_disconnect)
                                                    )
                                                },
                                                singleLine = true
                                            )

                                            // Image capture storage folder (SAF preferred; fallback RELATIVE_PATH fixed)
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                OutlinedTextField(
                                                    value = if (!imageTreeUriText.isNullOrBlank()) imageFolderLabel else AppSettings.DEFAULT_IMAGE_RELATIVE_PATH,
                                                    onValueChange = { /* read-only */ },
                                                    readOnly = true,
                                                    label = { Text(stringResource(R.string.settings_image_storage_folder)) },
                                                    supportingText = {
                                                        Text(
                                                            if (!imageTreeUriText.isNullOrBlank()) stringResource(
                                                                R.string.settings_storage_selected_via_browse
                                                            )
                                                            else stringResource(R.string.settings_storage_browse_recommended)
                                                        )
                                                    },
                                                    singleLine = true,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                IconButton(
                                                    onClick = { imageFolderPicker.launch(null) }) {
                                                    Icon(
                                                        imageVector = Icons.Default.FolderOpen,
                                                        contentDescription = stringResource(R.string.content_description_browse_folder)
                                                    )
                                                }
                                                IconButton(
                                                    enabled = !imageTreeUriText.isNullOrBlank(),
                                                    onClick = {
                                                        imageTreeUriText = null
                                                        AppSettings.setImageTreeUri(context, null)
                                                    }) {
                                                    Icon(
                                                        imageVector = Icons.Default.Close,
                                                        contentDescription = stringResource(R.string.content_description_clear_folder)
                                                    )
                                                }
                                            }

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    stringResource(R.string.settings_timestamp_overlay),
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                                Spacer(Modifier.weight(1f))
                                                Switch(
                                                    showTimestamp,
                                                    onCheckedChange = { showTimestamp = it })
                                            }

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    stringResource(R.string.settings_use_surfaceview),
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                                Spacer(Modifier.weight(1f))
                                                Switch(
                                                    checked = useSurfaceViewSetting,
                                                    enabled = !forceTextureViewDevice,
                                                    onCheckedChange = {
                                                        useSurfaceViewSetting = it
                                                    })
                                            }
                                            if (forceTextureViewDevice) {
                                                Text(
                                                    text = stringResource(R.string.settings_surfaceview_disabled),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color(0xFFFFC107)
                                                )
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        TextButton(
                                            onClick = {
                                                // Timestamp is UI-only: always safe to apply immediately.
                                                AppSettings.setTimestampEnabled(
                                                    context, showTimestamp
                                                )

                                                // Port affects TCP connect: only apply while disconnected.
                                                val requestedPort =
                                                    portText.toIntOrNull()?.coerceIn(1, 65535)
                                                        ?: AppSettings.DEFAULT_PORT
                                                val portChanged = requestedPort != port
                                                if (portChanged && state != ConnectionState.DISCONNECTED) {
                                                    Toast.makeText(
                                                        context,
                                                        toastDisconnectPort,
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                    // Keep dialog open so user can decide what to do.
                                                    return@TextButton
                                                }
                                                if (portChanged) {
                                                    port = requestedPort
                                                    AppSettings.setPort(context, requestedPort)
                                                }

                                                AppSettings.setUseSurfaceViewEnabled(
                                                    context,
                                                    if (forceTextureViewDevice) false else useSurfaceViewSetting
                                                )
                                                useSurfaceView =
                                                    if (forceTextureViewDevice) false else useSurfaceViewSetting

                                                showSettings = false
                                            }) { Text(stringResource(R.string.button_save)) }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showSettings = false }) {
                                            Text(
                                                stringResource(R.string.button_cancel)
                                            )
                                        }
                                    })
                            }

                            // Capture action
                            fun captureImage() {
                                textureViewRef?.let { tv ->
                                    val bitmap = tv.bitmap ?: return@let
                                    val timestamp = SimpleDateFormat(
                                        "yyyyMMdd_HHmmss", Locale.getDefault()
                                    ).format(
                                        Date()
                                    )
                                    val filename = "CCTV_Capture_$timestamp.jpg"
                                    try {
                                        // Prefer SAF-selected folder for captured images (works across Android versions).
                                        val treeUriStr = AppSettings.getImageTreeUri(context)
                                        if (!treeUriStr.isNullOrBlank()) {
                                            val treeUri = treeUriStr.toUri()
                                            val dir = DocumentFile.fromTreeUri(context, treeUri)
                                            val doc = dir?.createFile("image/jpeg", filename)
                                            if (doc != null) {
                                                context.contentResolver.openOutputStream(doc.uri)
                                                    ?.use { out ->
                                                        bitmap.compress(
                                                            Bitmap.CompressFormat.JPEG, 90, out
                                                        )
                                                    }
                                                Toast.makeText(
                                                    context,
                                                    String.format(toastImageSavedFmt, filename),
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                return@let
                                            }
                                            // If SAF folder is unavailable, fall through to default path behavior.
                                        }

                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                            val values = ContentValues().apply {
                                                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                                                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                                                put(
                                                    MediaStore.Images.Media.RELATIVE_PATH,
                                                    AppSettings.DEFAULT_IMAGE_RELATIVE_PATH
                                                )
                                            }
                                            val resolver = context.contentResolver
                                            val uri = resolver.insert(
                                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                                            )
                                            if (uri != null) {
                                                resolver.openOutputStream(uri)?.use { out ->
                                                    bitmap.compress(
                                                        Bitmap.CompressFormat.JPEG, 90, out
                                                    )
                                                }
                                                Toast.makeText(
                                                    context,
                                                    String.format(toastImageSavedFmt, filename),
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    toastImageSaveFailed,
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        } else {
                                            val baseDir =
                                                Environment.getExternalStoragePublicDirectory(
                                                    Environment.DIRECTORY_DCIM
                                                )
                                            val targetDir =
                                                File(baseDir, "Images/CCTV").apply { mkdirs() }
                                            val file = File(targetDir, filename)
                                            FileOutputStream(file).use { out ->
                                                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                                            }
                                            Toast.makeText(
                                                context,
                                                String.format(toastImageSavedFmt, filename),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    } catch (e: Exception) {
                                        Log.e("CCTV_VIEWER", "Failed to save image", e)
                                        Toast.makeText(
                                            context, toastImageSaveFailed, Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }

                            val canCaptureImage =
                                state == ConnectionState.STREAMING || state == ConnectionState.RECOVERING
                            val canCaptureNow = canCaptureImage && hasPreviewFrame
                            // Row directly below preview: speaker toggle (left), capture (center), camera switch (right)
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                IconButton(
                                    onClick = {
                                        muteAudio = !muteAudio
                                        client.setAudioMuted(muteAudio)
                                    },
                                    enabled = state == ConnectionState.STREAMING || state == ConnectionState.RECOVERING,
                                    modifier = Modifier.align(Alignment.CenterStart)
                                ) {
                                    Icon(
                                        imageVector = if (muteAudio) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = if (muteAudio) stringResource(R.string.content_description_unmute_audio) else stringResource(
                                            R.string.content_description_mute_audio
                                        ),
                                        tint = if (muteAudio) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                    )
                                }

                                FilledIconButton(
                                    onClick = { captureImage() },
                                    enabled = canCaptureNow,
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                                            alpha = 0.4f
                                        ),
                                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                            alpha = 0.4f
                                        )
                                    ),
                                    modifier = Modifier.size(64.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CameraAlt,
                                        contentDescription = stringResource(R.string.content_description_capture_image)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        client.switchCamera()
                                    },
                                    enabled = (state == ConnectionState.STREAMING || state == ConnectionState.RECOVERING),
                                    modifier = Modifier.align(Alignment.CenterEnd)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Cameraswitch,
                                        contentDescription = stringResource(R.string.content_description_switch_camera),
                                        tint = if (isFrontCamera) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                                    )
                                }
                            }
                            // Row 1: Server IP and Access Password
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Column 1, Row 1: Server IP input
                                Column(
                                    modifier = Modifier.weight(1f)
                                ) {
                                    OutlinedTextField(
                                        value = serverIp,
                                        onValueChange = { raw ->
                                            if (state != ConnectionState.DISCONNECTED) {
                                                return@OutlinedTextField
                                            }

                                            val prev = serverIp

                                            // If user typed exactly one new character at the end, treat it as incremental input.
                                            val text =
                                                if (raw.length == prev.length + 1 && raw.startsWith(
                                                        prev
                                                    )
                                                ) {
                                                    val ch = raw.last()
                                                    when {
                                                        ch.isDigit() -> {
                                                            val dotCount = prev.count { it == '.' }
                                                            val lastDot = prev.lastIndexOf('.')
                                                            val currentOctet =
                                                                if (lastDot == -1) prev else prev.substring(
                                                                    lastDot + 1
                                                                )

                                                            when {
                                                                currentOctet.length < 2 -> prev + ch
                                                                // allow 3rd digit; only auto-dot if not already at last octet
                                                                currentOctet.length == 2 && dotCount < 3 -> "$prev${ch}."
                                                                currentOctet.length == 2 && dotCount == 3 -> prev + ch
                                                                // block anything beyond 3 digits in an octet
                                                                else -> prev
                                                            }
                                                        }

                                                        ch == '.' -> {
                                                            // Manual dot: only if current octet has at least 1 digit and we don't already end with dot,
                                                            // and we haven't already used 3 dots.
                                                            val dotCount = prev.count { it == '.' }
                                                            val lastDot = prev.lastIndexOf('.')
                                                            val currentOctet =
                                                                if (lastDot == -1) prev else prev.substring(
                                                                    lastDot + 1
                                                                )

                                                            if (currentOctet.isNotEmpty() && dotCount < 3 && !prev.endsWith(
                                                                    "."
                                                                )
                                                            ) {
                                                                "$prev."
                                                            } else {
                                                                prev
                                                            }
                                                        }

                                                        else -> prev // ignore any other characters
                                                    }
                                                } else {
                                                    // Fallback: user edited in the middle or pasted; clean but don't auto-dot
                                                    raw.filter { it.isDigit() || it == '.' }
                                                }

                                            serverIp = text

                                            // Validate using the canonical form (no trailing dot)
                                            val candidate = text.trimEnd('.')
                                            ipError = when {
                                                candidate.isBlank() -> errorEnterServerIp
                                                isValidIp(candidate) -> null
                                                else -> errorInvalidIp
                                            }
                                        },
                                        label = {
                                            Text(
                                                text = stringResource(R.string.label_enter_server_ip),
                                                color = MaterialTheme.colorScheme.onBackground
                                            )
                                        },
                                        textStyle = MaterialTheme.typography.bodyLarge,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                            cursorColor = MaterialTheme.colorScheme.primary,
                                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                        ),
                                        isError = ipError != null,
                                        singleLine = true,
                                        readOnly = state != ConnectionState.DISCONNECTED,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .bringIntoViewRequester(ipBringIntoView)
                                            .onFocusEvent { ev ->
                                                if (ev.isFocused) {
                                                    scope.launch { ipBringIntoView.bringIntoView() }
                                                }
                                            })
                                    if (ipError != null) {
                                        Text(
                                            text = ipError ?: "",
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }

                                // Column 2, Row 1: Access password input
                                Column(modifier = Modifier.weight(1f)) {
                                    OutlinedTextField(
                                        value = password,
                                        onValueChange = {
                                            password = it
                                            AppSettings.setPassword(context, it)
                                        },
                                        label = {
                                            Text(
                                                text = stringResource(R.string.label_enter_server_ip),
                                                color = MaterialTheme.colorScheme.onBackground
                                            )
                                        },
                                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                        trailingIcon = {
                                            IconButton(onClick = {
                                                passwordVisible = !passwordVisible
                                            }) {
                                                Icon(
                                                    imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                                    contentDescription = if (passwordVisible) stringResource(
                                                        R.string.content_description_hide_password
                                                    )
                                                    else stringResource(R.string.content_description_show_password)
                                                )
                                            }
                                        },
                                        textStyle = MaterialTheme.typography.bodyLarge,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                            cursorColor = MaterialTheme.colorScheme.primary,
                                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                        ),
                                        singleLine = true,
                                        readOnly = state != ConnectionState.DISCONNECTED,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .bringIntoViewRequester(pwBringIntoView)
                                            .onFocusEvent { ev ->
                                                if (ev.isFocused) {
                                                    scope.launch { pwBringIntoView.bringIntoView() }
                                                }
                                            })
                                }
                            }

                            // Row 2: Start Recording button (full width)
                            val canControlRecording =
                                state == ConnectionState.STREAMING || state == ConnectionState.RECOVERING
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = canControlRecording,
                                onClick = {
                                    if (!isRecording) {
                                        client.startRecording()
                                    } else {
                                        client.stopRecording()
                                    }
                                }) {
                                Text(
                                    if (!isRecording) stringResource(R.string.button_start_recording) else stringResource(
                                        R.string.button_stop_recording
                                    )
                                )
                            }

                            // Row 3: Connect/Disconnect (left) and Push-to-Talk (right)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    modifier = Modifier.weight(1f),
                                    enabled = (state == ConnectionState.DISCONNECTED && isValidIp(
                                        serverIp.trimEnd('.')
                                    )) || (state != ConnectionState.DISCONNECTED && state != ConnectionState.CONNECTING),
                                    onClick = {
                                        if (state == ConnectionState.DISCONNECTED) {
                                            isRecording = false
                                            client.updateHost(serverIp.trimEnd('.'))
                                            client.connect()
                                        } else {
                                            client.disconnect()
                                            isRecording = false
                                        }
                                    }) {
                                    Text(
                                        if (state == ConnectionState.DISCONNECTED) stringResource(R.string.button_connect) else stringResource(
                                            R.string.button_disconnect
                                        )
                                    )
                                }
                                Button(
                                    onClick = {
                                        if (!hasAudioPermission) {
                                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            return@Button
                                        }
                                        if (!talkActive) {
                                            client.startTalk()
                                        } else {
                                            client.stopTalk()
                                        }
                                        talkActive = !talkActive
                                    },
                                    enabled = (state == ConnectionState.STREAMING || state == ConnectionState.RECOVERING) && communicationEnabled,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (talkActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                                    ),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = if (talkActive) Icons.Default.Mic else Icons.Default.MicOff,
                                        contentDescription = null
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (talkActive) stringResource(R.string.push_to_stop_talking)
                                        else stringResource(R.string.push_to_talk_label)
                                    )
                                }
                            }
                            if ((state == ConnectionState.STREAMING || state == ConnectionState.RECOVERING) && !communicationEnabled) {
                                Text(
                                    text = stringResource(R.string.push_to_talk_disabled_message),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }


                        LaunchedEffect(lastError) {
                            val msg = lastError
                            if (!msg.isNullOrBlank()) {
                                Toast.makeText(
                                    context, String.format(errorMessageFmt, msg), Toast.LENGTH_SHORT
                                ).show()
                                @Suppress("AssignedValueIsNeverRead")
                                lastError = null
                            }
                        }

                        // Show a short, self-dismissing message for connection issues
                        LaunchedEffect(state) {
                            if (state >= ConnectionState.AUTHENTICATED && isValidIp(
                                    serverIp.trimEnd(
                                        '.'
                                    )
                                )
                            ) {
                                val prefs =
                                    context.getSharedPreferences("cctv_viewer", MODE_PRIVATE)
                                prefs.edit { putString("last_server_ip", serverIp.trimEnd('.')) }
                            }
                        }


                    }
                }
            }
        }


    }


}

