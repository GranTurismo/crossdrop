package com.crossdrop.ui.connect

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.crossdrop.core.connect.JoinCode
import com.crossdrop.core.connect.WifiQr
import com.crossdrop.core.i18n.tr
import com.crossdrop.net.WifiJoin
import com.crossdrop.ui.BackHeader
import com.crossdrop.ui.ChipButton
import com.crossdrop.ui.Dot
import com.crossdrop.ui.GlassCard
import com.crossdrop.ui.PillButton
import com.crossdrop.ui.PillStyle
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.Amber
import com.crossdrop.ui.theme.Ink
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.theme.TextSecondary
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors

/**
 * "Scan a code": the camera reads another phone's private-link code (or any Wi-Fi code), CrossDrop joins
 * that network and connects to the phone. Closes itself once the other phone answered.
 */
@Composable
fun ScanCodeScreen(join: WifiJoin, onClose: () -> Unit, onJoined: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by join.state.collectAsState()
    var hasCamera by remember { mutableStateOf(granted(context, Manifest.permission.CAMERA)) }
    var notWifi by remember { mutableStateOf(false) }
    var lastCode by remember { mutableStateOf<JoinCode?>(null) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    val joinPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        lastCode?.let(join::join)
    }
    LaunchedEffect(Unit) {
        join.dismiss()
        if (!hasCamera) cameraPermission.launch(Manifest.permission.CAMERA)
    }
    // The other phone answered: back to the radar, where it now shows.
    LaunchedEffect(state) { if (state is WifiJoin.State.Joined) onJoined() }

    val scanning = state is WifiJoin.State.Idle || state is WifiJoin.State.Failed
    Box(modifier.fillMaxSize().background(Ink)) {
        if (hasCamera) {
            Scanner(active = scanning && lastCode == null) { text ->
                val code = WifiQr.parse(text)
                if (code == null) notWifi = true
                else { notWifi = false; lastCode = code; join.join(code) }
            }
        }
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                BackHeader(tr("Scan a code"), { join.dismiss(); onClose() })
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (hasCamera) Box(Modifier.size(250.dp).border(3.dp, Accent.copy(alpha = 0.9f), RoundedCornerShape(28.dp)))
            }
            GlassCard(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                fill = Ink.copy(alpha = 0.82f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (val s = state) {
                    is WifiJoin.State.Joining -> JoinStatus(
                        busy = true,
                        title = tr("Joining {0}…", s.ssid),
                        body = tr("Android asks you to confirm. Tap Connect."),
                    )
                    is WifiJoin.State.Connecting -> JoinStatus(
                        busy = true,
                        title = tr("Connected to {0}", s.ssid),
                        body = tr("Reaching the other phone…"),
                    )
                    is WifiJoin.State.Joined -> JoinStatus(busy = false, title = tr("Connected to {0}", s.ssid), body = "")
                    is WifiJoin.State.Failed -> {
                        val (title, body) = joinFailureText(s)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Dot(Amber)
                            Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text(body, color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (s.reason == WifiJoin.Failure.PERMISSION) {
                                val p = join.missingPermission()
                                ChipButton(tr("Allow"), { if (p != null) joinPermission.launch(p) else lastCode?.let(join::join) })
                            }
                            ChipButton(tr("Scan again"), { join.dismiss(); lastCode = null })
                        }
                    }
                    WifiJoin.State.Idle -> {
                        if (!hasCamera) {
                            Text(tr("CrossDrop needs the camera to read the code."), color = TextSecondary, fontSize = 14.sp)
                            PillButton(tr("Allow camera"), { cameraPermission.launch(Manifest.permission.CAMERA) }, Modifier.fillMaxWidth())
                        } else {
                            Text(
                                tr("Point at the code on the other phone"),
                                color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                if (notWifi) tr("That code isn't for a Wi-Fi network. On the other phone, tap Connect directly, then Make a CrossDrop Wi-Fi.")
                                else tr("On the other phone: Connect directly, then Make a CrossDrop Wi-Fi. Any Wi-Fi code works too."),
                                color = if (notWifi) Amber else TextDim, fontSize = 13.sp, lineHeight = 18.sp,
                            )
                        }
                    }
                }
                if (state is WifiJoin.State.Joining || state is WifiJoin.State.Connecting) {
                    PillButton(tr("Cancel"), { join.leave(); lastCode = null }, Modifier.fillMaxWidth(), style = PillStyle.Glass)
                }
            }
        }
    }
}

@Composable
internal fun JoinStatus(busy: Boolean, title: String, body: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (busy) CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp)) else Dot(Accent)
        Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
    if (body.isNotEmpty()) Text(body, color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
}

@Composable
internal fun joinFailureText(s: WifiJoin.State.Failed): Pair<String, String> = when (s.reason) {
    WifiJoin.Failure.NOT_JOINED -> tr("Couldn't join {0}", s.ssid) to
        tr("Is the code still on the other phone's screen, and is it close by? If Android asked, tap Connect.")
    WifiJoin.Failure.LOST -> tr("The link to {0} ended", s.ssid) to
        tr("The other phone stopped it, or it's out of range. Scan its code again.")
    WifiJoin.Failure.PERMISSION -> tr("Android needs your permission") to
        tr("Allow CrossDrop to find nearby devices, then scan again.")
    WifiJoin.Failure.UNSUPPORTED -> tr("This network can't be joined from an app") to
        tr("Join it in your phone's Wi-Fi settings instead.")
}

/** Camera preview plus a QR reader on the frames; [onText] runs on the main thread for each read. */
@Composable
private fun Scanner(active: Boolean, onText: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val activeNow = remember { java.util.concurrent.atomic.AtomicBoolean(active) }
    activeNow.set(active)
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var lastText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val provider = runCatching { ProcessCameraProvider.awaitInstance(context) }.getOrNull() ?: return@LaunchedEffect
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        val reader = QRCodeReader()
        val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
        analysis.setAnalyzer(executor) { image ->
            try {
                if (!activeNow.get()) return@setAnalyzer
                val plane = image.planes[0]
                val buf = plane.buffer
                val data = ByteArray(buf.remaining()).also { buf.get(it) }
                val source = PlanarYUVLuminanceSource(data, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
                val text = try {
                    reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
                } catch (_: NotFoundException) {
                    null
                } catch (_: Exception) {
                    null
                } finally {
                    reader.reset()
                }
                if (text != null) mainHandler.post {
                    // The same code stays in view for a while: act on it once.
                    if (activeNow.get() && text != lastText) { lastText = text; onText(text) }
                }
            } finally {
                image.close()
            }
        }
        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }
    }
    // A new scan (after a failure) may read the same code again.
    LaunchedEffect(active) { if (active) lastText = null }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            executor.shutdown()
        }
    }
    AndroidView({ previewView }, Modifier.fillMaxSize())
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
}

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

