package com.crossdrop.ui.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.crossdrop.media.MediaSaver
import com.crossdrop.net.SessionState
import com.crossdrop.net.SessionStatus
import com.crossdrop.ui.CdIcon
import com.crossdrop.ui.CdIcons
import com.crossdrop.ui.CircleButton
import com.crossdrop.ui.Dot
import com.crossdrop.ui.PillButton
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.Danger
import com.crossdrop.ui.theme.DangerText
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.theme.TextSecondary
import com.crossdrop.ui.theme.displayStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A shot taken here and where it went. */
data class CameraShot(val file: File, val video: Boolean, val groupId: String)

/**
 * Live camera: every photo or video taken here is saved to the gallery and sent straight to
 * [targets]. The first shot asks each device once; after that they arrive without a prompt.
 */
@Composable
fun CameraScreen(
    targets: List<String>,
    shots: List<CameraShot>,
    sessions: List<SessionState>,
    onShot: (File, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    var hasCamera by remember { mutableStateOf(granted(context, Manifest.permission.CAMERA)) }
    var hasMic by remember { mutableStateOf(granted(context, Manifest.permission.RECORD_AUDIO)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        hasCamera = r[Manifest.permission.CAMERA] == true || granted(context, Manifest.permission.CAMERA)
        hasMic = r[Manifest.permission.RECORD_AUDIO] == true || granted(context, Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(Unit) {
        if (!hasCamera) launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCamera) {
            CameraBody(targets, shots, sessions, hasMic, onShot, onClose) {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            }
        } else {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                CdIcon(CdIcons.Camera, TextSecondary, size = 44.dp)
                Text("Camera access is off", style = displayStyle(22))
                Text(
                    "CrossDrop needs the camera to send photos the moment you take them.",
                    color = TextDim, fontSize = 14.sp, textAlign = TextAlign.Center,
                )
                PillButton("Allow camera", { launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) })
                PillButton("Close", onClose, style = com.crossdrop.ui.PillStyle.Glass)
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun CameraBody(
    targets: List<String>,
    shots: List<CameraShot>,
    sessions: List<SessionState>,
    hasMic: Boolean,
    onShot: (File, Boolean) -> Unit,
    onClose: () -> Unit,
    haptic: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var videoMode by remember { mutableStateOf(false) }
    var front by remember { mutableStateOf(false) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var recordStart by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var flash by remember { mutableStateOf(false) }

    LaunchedEffect(front, videoMode) {
        val provider = runCatching { ProcessCameraProvider.awaitInstance(context) }.getOrNull() ?: run {
            error = "Camera unavailable"; return@LaunchedEffect
        }
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        provider.unbindAll()
        try {
            if (videoMode) {
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.fromOrderedList(listOf(Quality.FHD, Quality.HD, Quality.SD)))
                    .build()
                val vc = VideoCapture.withOutput(recorder)
                provider.bindToLifecycle(lifecycle, selector, preview, vc)
                videoCapture = vc; imageCapture = null
            } else {
                val ic = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                provider.bindToLifecycle(lifecycle, selector, preview, ic)
                imageCapture = ic; videoCapture = null
            }
            error = null
        } catch (e: Exception) {
            error = "Camera unavailable: ${e.message}"
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            recording?.stop()
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }
    LaunchedEffect(flash) { if (flash) { delay(120); flash = false } }

    fun newFile(ext: String): File {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(dir, "CrossDrop_$stamp.$ext")
    }

    fun shoot() {
        haptic()
        if (!videoMode) {
            val ic = imageCapture ?: return
            val file = newFile("jpg")
            flash = true
            ic.takePicture(
                ImageCapture.OutputFileOptions.Builder(file).build(),
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) = onShot(file, false)
                    override fun onError(exception: ImageCaptureException) { error = exception.message }
                },
            )
        } else {
            val active = recording
            if (active != null) { active.stop(); recording = null; return }
            val vc = videoCapture ?: return
            val file = newFile("mp4")
            val pending = vc.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
            recordStart = System.currentTimeMillis()
            val micPresent = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_MICROPHONE)
            recording = (if (hasMic && micPresent) pending.withAudioEnabled() else pending).start(ContextCompat.getMainExecutor(context)) { ev ->
                if (ev is VideoRecordEvent.Finalize) {
                    if (!ev.hasError() || ev.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) onShot(file, true)
                    else error = "Recording failed"
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        if (flash) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.5f)))

        // ---- top: close + where shots go
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircleButton(CdIcons.Close, "Close camera", onClose, fill = Color.Black.copy(alpha = 0.45f))
            Row(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Dot(Accent)
                Text(
                    "Live to " + targets.joinToString(", "),
                    color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }

        error?.let {
            Text(
                it, color = DangerText, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.6f)).padding(12.dp),
            )
        }

        // ---- bottom: last shot, shutter, flip
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (recording != null) RecordingClock(recordStart)
            ShotStatus(shots.lastOrNull(), sessions)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LastThumb(shots.lastOrNull(), Modifier.size(54.dp))
                Spacer(Modifier.weight(1f))
                Shutter(videoMode, recording != null, ::shoot)
                Spacer(Modifier.weight(1f))
                CircleButton(
                    CdIcons.Swap, "Switch camera", { if (recording == null) front = !front },
                    size = 54.dp, fill = Color.Black.copy(alpha = 0.45f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip("Photo", !videoMode) { if (recording == null) videoMode = false }
                ModeChip("Video", videoMode) { if (recording == null) videoMode = true }
            }
        }
    }
}

@Composable
private fun RecordingClock(start: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(start) { while (true) { now = System.currentTimeMillis(); delay(250) } }
    val s = ((now - start) / 1000).coerceAtLeast(0)
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Danger.copy(alpha = 0.85f)).padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
        Text("%d:%02d".format(s / 60, s % 60), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Shutter(video: Boolean, recording: Boolean, onClick: () -> Unit) {
    val inner by animateDpAsState(if (recording) 30.dp else 62.dp, label = "shutter")
    Box(
        Modifier.size(78.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (video) (if (recording) "Stop recording" else "Record video") else "Take photo" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(inner).clip(if (recording) RoundedCornerShape(8.dp) else CircleShape)
                .background(if (video) Danger else Color.White),
        )
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Color.Black else TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) Accent else Color.Black.copy(alpha = 0.45f))
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun LastThumb(shot: CameraShot?, modifier: Modifier) {
    val bmp by produceState<ImageBitmap?>(null, shot?.file) {
        value = shot?.file?.let { f -> withContext(Dispatchers.IO) { runCatching { MediaSaver.loadImage(f, 200) }.getOrNull() } }
    }
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.45f))
            .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/** "Sent to Mac ✓ · Waiting for Pixel" for the newest shot. */
@Composable
private fun ShotStatus(shot: CameraShot?, sessions: List<SessionState>) {
    if (shot == null) {
        Text("Take a photo — it lands on the other screen instantly", color = TextPrimary, fontSize = 13.sp,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 14.dp, vertical = 7.dp))
        return
    }
    val mine = sessions.filter { it.groupId == shot.groupId }
    val text = mine.joinToString("  ·  ") { s ->
        when (s.status) {
            SessionStatus.COMPLETED -> "${s.peerName} ✓"
            SessionStatus.AWAITING_ACCEPT -> "Waiting for ${s.peerName} to accept"
            SessionStatus.TRANSFERRING -> "${s.peerName} ${(s.fraction * 100).toInt()}%"
            SessionStatus.CONNECTING -> "Connecting to ${s.peerName}"
            SessionStatus.DECLINED -> "${s.peerName} declined"
            else -> "${s.peerName}: not sent"
        }
    }.ifEmpty { "Saved" }
    Text(
        text, color = TextPrimary, fontSize = 13.sp, maxLines = 2, textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 24.dp).clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

