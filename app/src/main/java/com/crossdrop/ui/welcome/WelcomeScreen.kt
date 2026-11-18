package com.crossdrop.ui.welcome

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.core.i18n.tr
import com.crossdrop.ui.CdIcon
import com.crossdrop.ui.CdIcons
import com.crossdrop.ui.ChipButton
import com.crossdrop.ui.FootNote
import com.crossdrop.ui.GlassChip
import com.crossdrop.ui.PillButton
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.Amber
import com.crossdrop.ui.theme.Glass
import com.crossdrop.ui.theme.Hairline
import com.crossdrop.ui.theme.Ink
import com.crossdrop.ui.theme.Sky
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.theme.TextSecondary
import com.crossdrop.ui.theme.Violet
import com.crossdrop.ui.theme.displayStyle
import com.crossdrop.ui.theme.monoStyle
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private class Page(val title: String, val body: String)

private const val PAGE_COUNT = 4

/** Built on demand, never cached: the language is read when the text is created. */
private fun pageAt(index: Int): Page = when (index) {
    0 -> Page(
        tr("Your devices,\none radar."),
        tr("Open CrossDrop on two devices on the same Wi-Fi. They find each other on their own: no pairing, no setup."),
    )
    1 -> Page(
        tr("Tap a device.\nPick files."),
        tr("Photos, videos, any file or folder go straight to the other device at full Wi-Fi speed, never through a server."),
    )
    2 -> Page(
        tr("No app on the\nother side?"),
        tr("Tap “Send to anyone” and show the QR code. Any phone or computer opens the files in its browser. Nothing to install."),
    )
    else -> Page(
        tr("Private by design."),
        tr("Transfers between CrossDrop devices are end-to-end encrypted. No account, no ads, no tracking, no cloud. Browser shares stay on your local network."),
    )
}

/**
 * First-run welcome: four short, animated pages (find → send → send to anyone → private) that
 * show what the app does before it asks for anything. Skippable from every page; the last page's
 * button finishes it. [permissionNote] is the line about what the system will ask next.
 *
 * Platform-free on purpose: shared verbatim with the desktop app.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WelcomeScreen(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    permissionNote: String? = null,
) {
    val pager = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == PAGE_COUNT - 1

    Box(modifier.fillMaxSize().background(Ink)) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.width * 0.9f
            drawCircle(
                Brush.radialGradient(listOf(Accent.copy(alpha = 0.14f), Color.Transparent), Offset(size.width * 0.5f, 0f), r),
                radius = r, center = Offset(size.width * 0.5f, 0f),
            )
        }
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ---- top bar: brand + language + Skip
            var pickingLanguage by remember { mutableStateOf(false) }
            if (pickingLanguage) com.crossdrop.ui.language.LanguageDialog { pickingLanguage = false }
            Row(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().height(60.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandMark()
                Spacer(Modifier.size(8.dp))
                Text("CrossDrop", style = displayStyle(16, tracking = -0.01f), modifier = Modifier.weight(1f))
                com.crossdrop.ui.language.LanguageButton({ pickingLanguage = true })
                if (!last) {
                    Spacer(Modifier.size(8.dp))
                    ChipButton(tr("Skip"), onFinish, contentDescription = tr("Skip the introduction"))
                }
            }

            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { i ->
                WelcomePage(i, if (i == PAGE_COUNT - 1) permissionNote else null)
            }

            // ---- dots + primary button
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 22.dp, top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    repeat(PAGE_COUNT) { i ->
                        val width by animateDpAsState(if (i == pager.currentPage) 22.dp else 7.dp, label = "dot")
                        Box(
                            Modifier.height(7.dp).size(width, 7.dp).clip(CircleShape)
                                .background(if (i == pager.currentPage) Accent else Color.White.copy(alpha = 0.2f)),
                        )
                    }
                }
                PillButton(
                    text = if (last) tr("Get started") else tr("Next"),
                    onClick = { if (last) onFinish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                    modifier = Modifier.fillMaxWidth(),
                    glow = true,
                )
            }
        }
    }
}

@Composable
private fun WelcomePage(index: Int, note: String?) {
    val t by rememberInfiniteTransition(label = "welcome").animateFloat(
        0f, 1f, infiniteRepeatable(tween(5000, easing = LinearEasing)), label = "t",
    )
    val page = pageAt(index)
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.widthIn(max = 520.dp).heightIn(max = 340.dp).fillMaxSize()) {
                when (index) {
                    0 -> RadarArt(t)
                    1 -> SendArt(t)
                    2 -> QrArt(t)
                    else -> PrivateArt(t)
                }
            }
        }
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                page.title,
                style = displayStyle(32, tracking = -0.03f).copy(lineHeight = 36.sp),
                modifier = Modifier.semantics { heading() },
            )
            Text(page.body, color = TextDim, fontSize = 15.sp, lineHeight = 22.sp)
            if (note != null) FootNote(note, Modifier.padding(top = 4.dp), icon = CdIcons.Lock)
        }
    }
}

@Composable
private fun BrandMark() {
    Box(Modifier.size(24.dp).border(2.dp, Accent, CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Accent))
    }
}

// ------------------------------------------------------------ 1 · radar --

private class Blip(val name: String, val icon: ImageVector, val tint: Color, val angleDeg: Float, val radius: Float)

private fun blips() = listOf(
    Blip(tr("Mia’s phone"), CdIcons.Phone, Accent, -55f, 0.66f),
    Blip(tr("Work laptop"), CdIcons.Laptop, Sky, 200f, 0.80f),
    Blip("iPad", CdIcons.Tablet, Violet, 85f, 0.56f),
)

@Composable
private fun RadarArt(t: Float) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val radius = min(maxWidth.value, maxHeight.value).dp / 2 * 0.92f
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = radius.toPx()
            drawCircle(Brush.radialGradient(listOf(Accent.copy(alpha = 0.18f), Color.Transparent), c, r * 1.15f), r * 1.15f, c)
            listOf(0.33f, 0.66f, 1f).forEach { drawCircle(Accent.copy(alpha = 0.2f), r * it, c, style = Stroke(1.dp.toPx())) }
            val ping = (t * 2f) % 1f
            drawCircle(Accent.copy(alpha = 0.32f * (1f - ping)), r * ping, c, style = Stroke(2.dp.toPx()))
            rotate(t * 360f, c) {
                drawCircle(
                    Brush.sweepGradient(
                        0f to Color.Transparent, 0.78f to Color.Transparent, 1f to Accent.copy(alpha = 0.38f), center = c,
                    ),
                    radius = r, center = c,
                )
            }
        }
        // you
        Box(Modifier.size(18.dp).clip(CircleShape).background(Accent.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Accent))
        }
        blips().forEach { b ->
            val rad = b.angleDeg * PI.toFloat() / 180f
            // Lit when the sweep passes (the sweep starts at 3 o'clock, so angle/360 is its time), then dims.
            val seen = ((b.angleDeg / 360f) % 1f + 1f) % 1f
            val age = (((t - seen) % 1f) + 1f) % 1f
            Column(
                Modifier.offset(x = radius * b.radius * cos(rad), y = radius * b.radius * sin(rad))
                    .alpha(0.4f + 0.6f * (1f - age)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(b.tint.copy(alpha = 0.14f))
                        .border(1.dp, b.tint.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { CdIcon(b.icon, b.tint, size = 22.dp) }
                Text(b.name, color = TextSecondary, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

// ------------------------------------------------------------- 2 · send --

@Composable
private fun SendArt(t: Float) {
    val flying = listOf(CdIcons.Photo to Sky, CdIcons.Video to Violet, CdIcons.Doc to Amber)
    val progress = (t / 0.85f).coerceIn(0f, 1f)
    val done = progress >= 1f
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().height(110.dp), verticalAlignment = Alignment.CenterVertically) {
            DeviceTile(CdIcons.Phone, tr("You"), Accent)
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 6.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    drawLine(
                        Accent.copy(alpha = 0.35f), Offset(0f, 41.dp.toPx()), Offset(size.width, 41.dp.toPx()),
                        strokeWidth = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 7.dp.toPx())),
                    )
                }
                if (!done) flying.forEachIndexed { i, (icon, tint) ->
                    val p = (t * 2f + i / 3f) % 1f
                    Box(
                        Modifier.offset(x = (maxWidth - 34.dp) * p, y = 24.dp).size(34.dp)
                            .alpha(sin(PI.toFloat() * p).coerceIn(0f, 1f))
                            .clip(RoundedCornerShape(11.dp)).background(tint.copy(alpha = 0.18f))
                            .border(1.dp, tint.copy(alpha = 0.45f), RoundedCornerShape(11.dp)),
                        contentAlignment = Alignment.Center,
                    ) { CdIcon(icon, tint, size = 18.dp) }
                }
            }
            DeviceTile(CdIcons.Laptop, tr("Laptop"), Sky)
        }
        Spacer(Modifier.height(26.dp))
        Column(Modifier.fillMaxWidth().widthIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress).clip(CircleShape).background(Accent))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (done) {
                    CdIcon(CdIcons.Check, Accent, size = 14.dp)
                    Spacer(Modifier.size(5.dp))
                }
                Text(
                    if (done) tr("Delivered") else tr("Sending 3 files…"),
                    color = if (done) Accent else TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(tr("direct · encrypted"), style = monoStyle(11, TextDim))
            }
        }
    }
}

@Composable
private fun DeviceTile(icon: ImageVector, label: String, tint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(22.dp)).background(Glass)
                .border(1.dp, Hairline, RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center,
        ) { CdIcon(icon, tint, size = 30.dp) }
        Text(label, color = TextDim, fontSize = 12.sp)
    }
}

// --------------------------------------------------------------- 3 · QR --

private const val QR_N = 21

/** A made-up QR-looking grid (never a real code): three finder squares and seeded noise. */
private fun fakeQr(): Array<BooleanArray> {
    val m = Array(QR_N) { BooleanArray(QR_N) }
    var s = 0x2545F491
    for (y in 0 until QR_N) for (x in 0 until QR_N) {
        s = s * 1103515245 + 12345
        m[y][x] = ((s ushr 16) and 0xFF) < 124
    }
    for ((ox, oy) in listOf(0 to 0, QR_N - 7 to 0, 0 to QR_N - 7)) {
        for (dy in -1..7) for (dx in -1..7) {
            val x = ox + dx
            val y = oy + dy
            if (x !in 0 until QR_N || y !in 0 until QR_N) continue
            m[y][x] = dx in 0..6 && dy in 0..6 &&
                (dx == 0 || dx == 6 || dy == 0 || dy == 6 || (dx in 2..4 && dy in 2..4))
        }
    }
    return m
}

@Composable
private fun QrArt(t: Float) {
    val modules = remember { fakeQr() }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val side = (min(maxWidth.value, maxHeight.value - 56f) * 0.92f).coerceAtLeast(120f).dp
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val shape = RoundedCornerShape(26.dp)
            Box(
                Modifier.size(side).dropShadow(shape, Shadow(44.dp, Accent.copy(alpha = 0.22f)))
                    .clip(shape).background(Color(0xFFF4F7F9)).padding(side * 0.09f),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val cell = size.width / QR_N
                    for (y in 0 until QR_N) for (x in 0 until QR_N) if (modules[y][x]) {
                        drawRect(Ink, Offset(x * cell, y * cell), Size(cell + 0.6f, cell + 0.6f))
                    }
                    // the scan line sweeps down and back
                    val ph = if (t < 0.5f) t * 2f else (1f - t) * 2f
                    val y = ph * size.height
                    val band = 46.dp.toPx()
                    drawRect(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xFF14C79A).copy(alpha = 0.35f), Color.Transparent),
                            startY = y - band, endY = y + band,
                        ),
                        Offset(0f, y - band), Size(size.width, band * 2),
                    )
                    drawRect(Color(0xFF14C79A), Offset(0f, y - 1.5.dp.toPx()), Size(size.width, 3.dp.toPx()))
                }
            }
            GlassChip(tr("Example"), dot = Accent)
        }
    }
}

// ----------------------------------------------------------- 4 · private --

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrivateArt(t: Float) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Translated chips can wrap to three or four rows (~42 dp each). The disc takes what is left over; on a very
        // short screen it is dropped altogether so the chips (the actual content) are never clipped.
        val compact = maxHeight < 200.dp
        val disc = (maxHeight - 100.dp).coerceIn(56.dp, 168.dp)
        val rows = ((maxHeight - (if (compact) 0.dp else disc + 14.dp)) / 42.dp).toInt().coerceAtLeast(2)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (!compact) Box(Modifier.size(disc), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2f
                    repeat(3) { i ->
                        val p = (t * 1.5f + i / 3f) % 1f
                        drawCircle(Accent.copy(alpha = 0.3f * (1f - p)), r * (0.55f + 0.45f * p), center, style = Stroke(1.5.dp.toPx()))
                    }
                }
                Box(
                    Modifier.size(disc * 0.56f).clip(CircleShape).background(Accent.copy(alpha = 0.14f))
                        .border(1.dp, Accent.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { CdIcon(CdIcons.Shield, Accent, size = disc * 0.3f) }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxLines = rows,
            ) {
                listOf(tr("Encrypted between devices"), tr("No account"), tr("No ads"), tr("No tracking")).forEach {
                    GlassChip(it, dot = Accent, fg = TextPrimary)
                }
            }
        }
    }
}
