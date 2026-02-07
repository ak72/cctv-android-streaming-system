package com.anurag.cctvprimary.ui.screens

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.StatFs
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import com.anurag.cctvprimary.AppSettings
import com.anurag.cctvprimary.CameraForegroundService
import com.anurag.cctvprimary.DeviceQuirks
import com.anurag.cctvprimary.EncoderCapabilityDetector
import com.anurag.cctvprimary.EncoderProbeStore
import com.anurag.cctvprimary.R
import com.anurag.cctvprimary.ServiceCaptureState
import kotlinx.coroutines.delay
import java.util.Locale

/* -----------------------------
   UI capture state
   ----------------------------- */
enum class CaptureState {
    IDLE, PREVIEW, RECORDING
}

/**
 * Primary app main screen UI.
 *
 * Intent:
 * - Provide camera preview and capture controls.
 * - Keep all existing behaviors intact; this file is a refactor-only extraction from MainActivity.kt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CctvScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var cameraService by remember { mutableStateOf<CameraForegroundService?>(null) }
    var captureState by remember { mutableStateOf(CaptureState.IDLE) }
    var bound by remember { mutableStateOf(false) }

    var recordAudio by remember { mutableStateOf(true) }
    var accessPassword by remember { mutableStateOf(AppSettings.getPassword(context)) }
    var enableTalkback by remember { mutableStateOf(AppSettings.isTalkbackEnabled(context)) }

    val toastPortCannotChange = stringResource(R.string.toast_port_cannot_change)

    // Record Audio and Talk Back are now independent - no mutual exclusivity needed
    var aeComp by remember { mutableFloatStateOf(0f) }
    var torchEnabled by remember { mutableStateOf(false) }
    var recordingWithAudio by remember { mutableStateOf(false) }  // Track if current recording has audio
    var stoppingRecording by remember { mutableStateOf(false) }
    // Removed "pendingStart" (it was starting recording unintentionally). Start Capture controls preview only.
    var expRange by remember { mutableStateOf(IntRange(-4, 4)) }
    var isFrontCamera by remember { mutableStateOf(false) }

    // Resolve local IPv4 address once for display
    val ipAddress by remember { mutableStateOf(getLocalIpAddress()) }

    // Settings dialog state
    var showSettings by remember { mutableStateOf(false) }
    var portText by remember { mutableStateOf(AppSettings.getPort(context).toString()) }
    var relPathText by remember { mutableStateOf(AppSettings.DEFAULT_VIDEO_RELATIVE_PATH) }
    var treeUriText by remember { mutableStateOf(AppSettings.getVideoTreeUri(context)) }
    var folderLabel by remember { mutableStateOf("") }
    var rotationEnabled by remember { mutableStateOf(AppSettings.isFileRotationEnabled(context)) }
    var limitMbText by remember {
        mutableStateOf((AppSettings.getStorageLimitBytes(context) / (1024 * 1024)).toString())
    }
    var forceBufferMode by remember { mutableStateOf(AppSettings.isForceBufferMode(context)) }
    var forceBufferForced by remember { mutableStateOf(false) }
    // Hidden developer options (revealed by tapping Settings title 7x)
    var showDevOptions by remember { mutableStateOf(false) }
    var devTapCount by remember { mutableIntStateOf(0) }
    var probeVideoCaptureCombo by remember {
        mutableStateOf(AppSettings.isActiveProbeVideoCaptureComboEnabled(context))
    }
    var storageInfo by remember { mutableStateOf<StorageInfo?>(null) }

    // Resolve the selected folder display name (best-effort).
    LaunchedEffect(treeUriText) {
        folderLabel = treeUriText?.let { s ->
            val uri = runCatching { s.toUri() }.getOrNull()
            val name = if (uri != null) runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull() else null
            name ?: s
        } ?: ""
    }

    LaunchedEffect(showSettings) {
        if (showSettings) {
            storageInfo = getStorageInfo(context)
            // Reset hidden dev reveal each time dialog opens.
            devTapCount = 0
            showDevOptions = false
            probeVideoCaptureCombo = AppSettings.isActiveProbeVideoCaptureComboEnabled(context)
            forceBufferForced = runCatching {
                DeviceQuirks.forceBufferInputMode() ||
                    EncoderProbeStore.wasSurfaceInputMarkedBad(context) ||
                    !EncoderCapabilityDetector.hasAvcSurfaceInputEncoder()
            }.getOrDefault(false)
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Throwable) {
                // Some devices/providers may not support persistable permissions; still save the URI.
            }
            treeUriText = uri.toString()
            AppSettings.setVideoTreeUri(context, treeUriText)
        }
    }

    // PreviewView must remain in composition even while IDLE, otherwise Start Capture can’t obtain a surface.
    // To clear the last frame on Stop Capture, we recreate the PreviewView instance.
    var previewKey by remember { mutableIntStateOf(0) }
    val previewView = remember(previewKey) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            // Preserve full camera FOV (avoid center-crop).
            // If the container aspect doesn't match the camera buffer, this may letterbox instead of cropping.
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }

    val serviceConnection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                cameraService = (binder as CameraForegroundService.LocalBinder).getService()
                bound = true

                // Do not pass PreviewView.viewPort here: on some devices it can induce an unwanted center-crop.
                previewView.post {
                    val rotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
                    cameraService?.bindCamera(previewView.surfaceProvider, null, rotation)
                }

                captureState = when (cameraService?.getServiceState()) {
                    ServiceCaptureState.RECORDING -> CaptureState.RECORDING
                    ServiceCaptureState.PREVIEW -> CaptureState.PREVIEW
                    else -> CaptureState.IDLE
                }
                cameraService?.currentCamera?.cameraInfo?.exposureState?.exposureCompensationRange?.let {
                    expRange = IntRange(it.lower, it.upper)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                cameraService = null
                bound = false
            }
        }
    }

    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    fun startOrBindCapture() {
        if (captureState != CaptureState.IDLE) return
        if (bound && cameraService != null) {
            // Already bound: ensure capture is actually running (can be IDLE if a prior bind was skipped).
            val svc = cameraService
            if (svc != null && svc.getServiceState() == ServiceCaptureState.IDLE) {
                previewView.post {
                    val rotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
                    try {
                        svc.bindCamera(previewView.surfaceProvider, null, rotation)
                    } catch (_: Throwable) {
                    }
                }
            }
        } else {
            val intent = Intent(context, CameraForegroundService::class.java)
            // Persist password so Service can always reload it deterministically on startup.
            AppSettings.setPassword(context, accessPassword)
            intent.putExtra("access_password", accessPassword.ifBlank { AppSettings.DEFAULT_PASSWORD })
            intent.putExtra("enable_talkback", enableTalkback)
            ContextCompat.startForegroundService(context, intent)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        // Start Capture should not be blocked by optional permissions (mic/notifications).
        // Only CAMERA is required to start the preview/capture pipeline.
        val camOk = granted[Manifest.permission.CAMERA] == true || hasCameraPermission()
        if (camOk) {
            startOrBindCapture()
        } else {
            Toast.makeText(context, "Camera permission is required to start capture", Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (bound) {
                try {
                    context.unbindService(serviceConnection)
                } catch (_: Exception) {
                }
                bound = false
            }
        }
    }

    // Tell the service whether Primary UI is visible so Viewer-triggered camera switches can safely
    // decide whether to include Preview (avoid freezes when Primary is backgrounded).
    DisposableEffect(lifecycleOwner, cameraService) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    cameraService?.setPrimaryUiVisible(true)
                    // Re-attach PreviewView when app resumes:
                    // - After Viewer-triggered camera switch while backgrounded (service may have rebound headless)
                    // - After service crash/resume (camera may not be bound to PreviewView)
                    // - Always ensure preview is visible if service is running
                    if (bound && cameraService != null) {
                        previewView.post {
                            val rotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
                            try {
                                Log.d("CCTV_PRIMARY", "[PRIMARY MA]🔵 [LIFECYCLE] ON_START: Re-attaching PreviewView to camera service (captureState=$captureState)")
                                // IMPORTANT:
                                // Avoid full bindCamera() on resume (unbindAll + full graph rebuild can ANR under churn).
                                // We only need to re-attach the UI Preview surface.
                                cameraService?.attachPreview(previewView.surfaceProvider, rotation)
                            } catch (t: Throwable) {
                                Log.w("CCTV_PRIMARY", "[PRIMARY MA] ⚠️ [LIFECYCLE] Failed to re-attach PreviewView on resume", t)
                            }
                        }
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    cameraService?.setPrimaryUiVisible(false)
                    // CRITICAL: Unbind Preview when backgrounded to prevent stalling the camera session
                    // on a dead surface.
                    // FIX: Use lightweight detachPreview() instead of full bindCamera(null) to avoid
                    // blocking Main Thread (~300ms) which causes UI Freeze/ANR on slower devices (M30s).
                    if (bound && cameraService != null) {
                         try {
                             Log.d("CCTV_PRIMARY", "[PRIMARY MA] 🔵 [LIFECYCLE] ON_STOP: Calling detachPreview() (Lightweight Headless Switch)")
                             cameraService?.detachPreview()
                         } catch (t: Throwable) {
                             Log.w("CCTV_PRIMARY", "[PRIMARY MA] ⚠️ [LIFECYCLE] Failed to detach preview on stop", t)
                         }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            cameraService?.setPrimaryUiVisible(false)
        }
    }

    // Keep UI in sync even when recording is started/stopped remotely (Viewer).
    var previousIsFrontCamera by remember { mutableStateOf(false) }
    LaunchedEffect(cameraService, bound) {
        while (bound && cameraService != null) {
            captureState = when (cameraService?.getServiceState()) {
                ServiceCaptureState.RECORDING -> CaptureState.RECORDING
                ServiceCaptureState.PREVIEW -> CaptureState.PREVIEW
                else -> CaptureState.IDLE
            }
            recordingWithAudio = cameraService?.isRecordingWithAudio() == true
            stoppingRecording = cameraService?.isStopRecordingInProgress() == true
            cameraService?.currentCamera?.cameraInfo?.exposureState?.exposureCompensationRange?.let {
                expRange = IntRange(it.lower, it.upper)
            }
            val newIsFrontCamera = cameraService?.isUsingFrontCamera() == true
            // When switching from front to rear camera, restore torch if it was ON
            if (previousIsFrontCamera && !newIsFrontCamera && torchEnabled) {
                cameraService?.setTorch(true)
            }
            previousIsFrontCamera = newIsFrontCamera
            isFrontCamera = newIsFrontCamera
            delay(300)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // Prevent keyboard from covering the password fields.
            .imePadding()
            // Keep only small horizontal margin; avoid extra top/bottom padding (top inset handled by ResponsiveSingleScreen).
                .padding(horizontal = 1.dp, vertical = 0.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        // Header and preview grouped together with minimal spacing
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            // Header row: title left, device IP right (saves vertical space)
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                    //.background(MaterialTheme.colorScheme.surfaceVariant),
                  //  .padding(horizontal = 0.dp, vertical = 0.5.dp),
                verticalAlignment = Alignment.CenterVertically

            ) {
                Text("Camera", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.weight(1f))
                if (ipAddress.isNotBlank()) {
                    Text(
                        text = ipAddress,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                IconButton(
                    onClick = {
                        // Refresh dialog fields from disk every time the dialog opens.
                        portText = AppSettings.getPort(context).toString()
                        relPathText = AppSettings.getVideoRelativePath(context)
                        treeUriText = AppSettings.getVideoTreeUri(context)
                        rotationEnabled = AppSettings.isFileRotationEnabled(context)
                        limitMbText = (AppSettings.getStorageLimitBytes(context) / (1024 * 1024)).toString()
                            .ifBlank { (AppSettings.DEFAULT_STORAGE_LIMIT_BYTES / (1024 * 1024)).toString() }
                        showSettings = true
                    },
                    modifier = Modifier.padding(start = 6.dp)
                ) {
                    Icon(imageVector = Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
                }
            }

            // Live preview with controls always visible
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .background(Color.Black)
                    .clipToBounds()
            ) {
                // PreviewView must remain in composition even while IDLE to obtain a surface.
                // We cover it with an overlay when IDLE to hide the last rendered frame.
                @Suppress("UI_COMPOSABLE_EXPECTED", "ComposableTargetInLambda")
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize()
                )

                // Overlay to hide last frame when IDLE and show "No Video" text
                if (captureState == CaptureState.IDLE) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No Video",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                // Blinking LED indicator (top-right) - status text removed (now shown in Viewer)
                if (captureState != CaptureState.IDLE) {
                    val ledColor = when (captureState) {
                        CaptureState.RECORDING -> Color.Red
                        CaptureState.PREVIEW -> Color.Green
                        else -> Color.White
                    }
                    val infiniteTransition = rememberInfiniteTransition(label = "led_blink")
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 0.3f,
                        animationSpec = infiniteRepeatable(
                            tween(1000, easing = LinearEasing),
                            RepeatMode.Reverse
                        ),
                        label = "led_alpha"
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(16.dp)
                            .background(ledColor.copy(alpha = alpha), CircleShape)
                    )
                }

                // Camera switch overlay button (transparent icon-style), centered at bottom of preview.
                // Allow switch during PREVIEW or RECORDING (tryRebind uses includeVideoCapture when RECORDING).
                IconButton(
                    onClick = { cameraService?.requestSwitchCamera() },
                    enabled = captureState == CaptureState.PREVIEW || captureState == CaptureState.RECORDING,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Cameraswitch,
                        contentDescription =
                            if (isFrontCamera) stringResource(R.string.content_description_switch_to_back_camera)
                            else stringResource(R.string.content_description_switch_to_front_camera),
                        tint = Color.White.copy(alpha = 0.85f)
                    )
                }
            }
        }

        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        modifier = Modifier.clickable {
                            devTapCount++
                            if (!showDevOptions && devTapCount >= 7) {
                                showDevOptions = true
                                Toast.makeText(context, "Developer options enabled", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                },
                text = {
                    val defaultLimitMbText = remember {
                        (AppSettings.DEFAULT_STORAGE_LIMIT_BYTES / (1024 * 1024)).toString()
                    }
                    val info = storageInfo ?: getStorageInfo(context)

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = portText,
                            enabled = captureState == CaptureState.IDLE,
                            onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text(stringResource(R.string.settings_port)) },
                            supportingText = {
                                Text(
                                    if (captureState == CaptureState.IDLE)
                                        stringResource(R.string.settings_port_valid_range)
                                    else
                                        stringResource(R.string.settings_port_cannot_change)
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )

                        // Storage location: prefer folder picker (SAF). RELATIVE_PATH fallback is fixed to Movies/CCTV.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = if (!treeUriText.isNullOrBlank()) folderLabel else AppSettings.DEFAULT_VIDEO_RELATIVE_PATH,
                                onValueChange = { /* read-only */ },
                                readOnly = true,
                                label = { Text(stringResource(R.string.settings_storage_folder)) },
                                supportingText = {
                                    Text(
                                        if (!treeUriText.isNullOrBlank())
                                            stringResource(R.string.settings_storage_selected_via_browse)
                                        else
                                            stringResource(R.string.settings_storage_browse_recommended)
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { folderPicker.launch(null) }) {
                                Icon(imageVector = Icons.Default.FolderOpen, contentDescription = stringResource(R.string.content_description_browse_folder))
                            }
                            IconButton(
                                enabled = !treeUriText.isNullOrBlank(),
                                onClick = {
                                    treeUriText = null
                                    AppSettings.setVideoTreeUri(context, null)
                                    // Reset fallback RELATIVE_PATH back to the safe default.
                                    relPathText = AppSettings.DEFAULT_VIDEO_RELATIVE_PATH
                                    AppSettings.setVideoRelativePath(context, AppSettings.DEFAULT_VIDEO_RELATIVE_PATH)
                                }
                            ) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = stringResource(R.string.content_description_clear_folder))
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.settings_available_storage), style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = stringResource(R.string.settings_storage_internal, formatBytes(info.internalAvailableBytes)),
                                style = MaterialTheme.typography.bodySmall
                            )
                            info.externalAvailableBytes?.let { ext ->
                                Text(
                                    text = stringResource(R.string.settings_storage_external, formatBytes(ext)),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.settings_file_rotation), style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.weight(1f))
                            Switch(rotationEnabled, onCheckedChange = { rotationEnabled = it })
                        }

                        OutlinedTextField(
                            value = limitMbText,
                            onValueChange = { newText ->
                                val digits = newText.filter { c -> c.isDigit() }.take(7)
                                limitMbText = digits.ifBlank { defaultLimitMbText }
                            },
                            readOnly = captureState == CaptureState.RECORDING,
                            enabled = rotationEnabled && captureState != CaptureState.RECORDING,
                            label = { Text(stringResource(R.string.settings_storage_limit_mb)) },
                            supportingText = {
                                Text(
                                    when {
                                        captureState == CaptureState.RECORDING ->
                                            stringResource(R.string.settings_storage_limit_readonly_recording)
                                        rotationEnabled ->
                                            stringResource(R.string.settings_storage_limit_rotation_on)
                                        else ->
                                            stringResource(R.string.settings_storage_limit_rotation_off)
                                    }
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                focusedBorderColor = MaterialTheme.colorScheme.outline,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline
                            )
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.settings_force_buffer_mode), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    stringResource(R.string.settings_force_buffer_mode_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            val effectiveForceBuffer = forceBufferForced || forceBufferMode
                            Switch(
                                checked = effectiveForceBuffer,
                                enabled = (captureState == CaptureState.IDLE) && !forceBufferForced,
                                onCheckedChange = { forceBufferMode = it }
                            )
                        }
                        // UX hint: buffer-mode changes are risky mid-capture; require IDLE.
                        if (captureState != CaptureState.IDLE) {
                            Text(
                                text = "Stop Capture to change encoder mode safely.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                        // UX hint: if we already know Surface mode is unsafe on this device/build, explain why the toggle is disabled.
                        // (Do not mention specific device models here; keep it generic.)
                        if (forceBufferForced) {
                            Text(
                                text = "Buffer mode is forced on this device for stability.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }

                        if (showDevOptions) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Active probe: verify VideoCapture combo",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "One-time heavy probe that binds encoder + ImageAnalysis + VideoCapture. Keep OFF unless needed.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Switch(
                                    checked = probeVideoCaptureCombo,
                                    onCheckedChange = { probeVideoCaptureCombo = it }
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val requestedPort = portText.toIntOrNull()?.coerceIn(1, 65535) ?: AppSettings.DEFAULT_PORT
                            val currentPort = AppSettings.getPort(context)

                            if (captureState != CaptureState.IDLE && requestedPort != currentPort) {
                                Toast.makeText(context, toastPortCannotChange, Toast.LENGTH_SHORT).show()
                            } else {
                                AppSettings.setPort(context, requestedPort)
                            }

                            val defaultMb = (AppSettings.DEFAULT_STORAGE_LIMIT_BYTES / (1024 * 1024))
                            val mb = limitMbText.toLongOrNull()?.coerceAtLeast(5) ?: defaultMb

                            // Save the relative path (fallback when no SAF folder is selected).
                            AppSettings.setVideoRelativePath(context, relPathText)
                            AppSettings.setFileRotationEnabled(context, rotationEnabled)
                            AppSettings.setStorageLimitBytes(context, mb * 1024 * 1024)
                            // If forced, persist it as enabled so settings reflect actual behavior.
                            val forceBufferForced = runCatching {
                                DeviceQuirks.forceBufferInputMode() ||
                                    EncoderProbeStore.wasSurfaceInputMarkedBad(context) ||
                                    !EncoderCapabilityDetector.hasAvcSurfaceInputEncoder()
                            }.getOrDefault(false)
                            AppSettings.setForceBufferMode(context, if (forceBufferForced) true else forceBufferMode)
                            AppSettings.setActiveProbeVideoCaptureComboEnabled(context, probeVideoCaptureCombo)
                            // Notify the service to reload settings immediately
                            cameraService?.refreshUserSettings()
                            showSettings = false
                        }
                    ) { Text(stringResource(R.string.button_save)) }
                },
                dismissButton = {
                    TextButton(onClick = { showSettings = false }) { Text(stringResource(R.string.button_cancel)) }
                }
            )
        }

        // Controls grid:
        // Row 1: [Record Audio] [Talkback]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.label_record_audio), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(8.dp))
                Switch(
                    enabled = captureState != CaptureState.RECORDING,
                    checked = recordAudio,
                    onCheckedChange = { newValue ->
                        // Record Audio button controls only whether audio is included in locally saved recordings
                        // It does NOT affect streaming audio or Talk Back functionality
                        recordAudio = newValue
                    }
                )
            }
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                Text(stringResource(R.string.label_talkback), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = enableTalkback,
                    onCheckedChange = { newValue ->
                        // Talk Back button controls two-way audio communication independently
                        // It does NOT affect recording audio or streaming audio/video
                        enableTalkback = newValue
                        AppSettings.setTalkbackEnabled(context, newValue)
                        cameraService?.enableTalkback = newValue
                    }
                )
            }
        }

        // Row 2: [Access Password] [Torch]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = accessPassword,
                onValueChange = { v ->
                    accessPassword = v
                    AppSettings.setPassword(context, v)
                    cameraService?.setAccessPassword(v)
                },
                label = { Text(stringResource(R.string.label_access_password)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                // Slightly reduce apparent height by constraining min height.
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
            )
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                Text(stringResource(R.string.label_torch), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(8.dp))
                Switch(
                    enabled = (captureState == CaptureState.PREVIEW || captureState == CaptureState.RECORDING) && !isFrontCamera,
                    checked = torchEnabled,
                    onCheckedChange = {
                        torchEnabled = it
                        cameraService?.setTorch(it)
                    }
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.label_exposure_compensation), style = MaterialTheme.typography.bodySmall)
            Slider(
                enabled = captureState == CaptureState.PREVIEW || captureState == CaptureState.RECORDING,
                value = aeComp,
                onValueChange = {
                    aeComp = it
                    cameraService?.setAeComp(it)
                },
                valueRange = expRange.first.toFloat()..expRange.last.toFloat(),
                steps = (expRange.last - expRange.first).coerceAtLeast(1) - 1
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                modifier = Modifier.weight(1f),
                enabled = captureState != CaptureState.RECORDING,
                onClick = {
                    if (captureState == CaptureState.IDLE) {
                        if (hasCameraPermission()) startOrBindCapture() else {
                            permissionLauncher.launch(
                                buildList {
                                    add(Manifest.permission.CAMERA)
                                    // Request optional permissions, but do not block capture if denied.
                                    // - RECORD_AUDIO: only needed for streaming/recording audio features
                                    // - POST_NOTIFICATIONS: improves foreground notification visibility on Android 13+
                                    if (!hasAudioPermission()) add(Manifest.permission.RECORD_AUDIO)
                                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                                }.toTypedArray()
                            )
                        }
                    } else {
                        // Stop Capture (only when not recording). Also disables all controls.
                        try { cameraService?.stopCapture() } catch (_: Throwable) { }
                        try { if (bound) context.unbindService(serviceConnection) } catch (_: Throwable) { }
                        cameraService = null
                        bound = false
                        torchEnabled = false
                        captureState = CaptureState.IDLE
                        // Recreate PreviewView to clear any last rendered frame.
                        previewKey++
                    }
                }
            ) {
                Text(
                    if (captureState == CaptureState.IDLE) stringResource(R.string.button_start_capture)
                    else stringResource(R.string.button_stop_capture)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = (captureState == CaptureState.PREVIEW) || (captureState == CaptureState.RECORDING && !stoppingRecording),
                    onClick = {
                        Log.d("CCTV_PRIMARY", "[PRIMARY MA] 🔴 [RECORDING] Recording button clicked: captureState=$captureState, cameraService=${cameraService != null}, recordAudio=$recordAudio")
                        if (captureState == CaptureState.PREVIEW) {
                            if (cameraService == null) {
                                Log.e("CCTV_PRIMARY", "[PRIMARY MA] 🔴 [RECORDING] Button clicked but cameraService is NULL - cannot start recording")
                            } else {
                                Log.d("CCTV_PRIMARY", "[PRIMARY MA] 🔴 [RECORDING] Calling cameraService.startRecording(recordAudio=$recordAudio)")
                                cameraService?.startRecording(recordAudio)
                            }
                        } else if (captureState == CaptureState.RECORDING) {
                            Log.d("CCTV_PRIMARY", "[PRIMARY MA] 🔴 [RECORDING] Calling cameraService.stopRecording()")
                            try {
                                if (stoppingRecording) {
                                    Log.w("CCTV_PRIMARY", "[PRIMARY MA] 🔴 [RECORDING] Stop requested while stop already in progress - ignoring")
                                } else {
                                    cameraService?.stopRecording()
                                }
                            } catch (t: Throwable) {
                                Log.e("CCTV_PRIMARY", "[PRIMARY MA] 🔴 [RECORDING] stopRecording() threw exception", t)
                            }
                        } else {
                            Log.w("CCTV_PRIMARY", "[PRIMARY MA] 🔴 [RECORDING] Button clicked but captureState=$captureState is not PREVIEW or RECORDING")
                        }
                    }
                ) {
                    Text(
                        when (captureState) {
                            CaptureState.RECORDING ->
                                if (stoppingRecording) "Stopping..." else stringResource(R.string.button_stop_recording)
                            else -> stringResource(R.string.button_start_recording)
                        }
                    )
                }
                // Show recording audio status indicator
                if (captureState == CaptureState.RECORDING) {
                    Spacer(Modifier.heightIn(min = 4.dp))
                    Text(
                        text = if (recordingWithAudio) stringResource(R.string.recording_with_audio) else stringResource(R.string.recording_without_audio),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (recordingWithAudio) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

/**
 * Helper to get the first non-loopback IPv4 address for this device.
 */
private fun getLocalIpAddress(): String {
    return try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        interfaces.toList().flatMap { intf -> intf.inetAddresses.toList() }
            .firstOrNull { addr -> !addr.isLoopbackAddress && addr is java.net.Inet4Address }
            ?.hostAddress ?: ""
    } catch (_: Exception) {
        ""
    }
}

private data class StorageInfo(
    val internalAvailableBytes: Long,
    val externalAvailableBytes: Long?
)

/**
 * Get internal + (best-effort) removable external storage availability.
 */
private fun getStorageInfo(context: Context): StorageInfo {
    val internal = runCatching {
        StatFs(Environment.getDataDirectory().absolutePath).availableBytes
    }.getOrNull() ?: 0L

    val externalBytes = runCatching {
        val dirs = context.getExternalFilesDirs(null)
        val sd = dirs.firstOrNull { d ->
            d != null &&
                Environment.isExternalStorageRemovable(d) &&
                Environment.getExternalStorageState(d) == Environment.MEDIA_MOUNTED
        }
        sd?.let { StatFs(it.absolutePath).availableBytes }
    }.getOrNull()

    return StorageInfo(
        internalAvailableBytes = internal,
        externalAvailableBytes = externalBytes
    )
}

/**
 * Formats byte counts for UI display.
 */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    val tb = gb * 1024.0
    return when {
        bytes >= tb -> String.format(Locale.getDefault(), "%.2f TB", bytes / tb)
        bytes >= gb -> String.format(Locale.getDefault(), "%.2f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.getDefault(), "%.2f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.getDefault(), "%.2f KB", bytes / kb)
        else -> "$bytes B"
    }
}

