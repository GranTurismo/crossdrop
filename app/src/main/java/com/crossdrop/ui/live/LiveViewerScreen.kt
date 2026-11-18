package com.crossdrop.ui.live

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.core.i18n.tr
import com.crossdrop.core.i18n.trPlural
import com.crossdrop.net.LiveFeed
import com.crossdrop.net.LiveShot
import com.crossdrop.ui.CdIcon
import com.crossdrop.ui.CdIcons
import com.crossdrop.ui.ChipButton
import com.crossdrop.ui.Dot
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.GlassHigh
import com.crossdrop.ui.theme.GlassMid
import com.crossdrop.ui.theme.HairlineStrong
import com.crossdrop.ui.theme.ScreenGlow
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.theme.TextSecondary
import com.crossdrop.ui.theme.caps
import com.crossdrop.ui.theme.displayStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** True for names the viewer shows as video tiles rather than pictures. */
fun isVideoName(name: String, mime: String?): Boolean =
    mime?.startsWith("video/") == true ||
        name.substringAfterLast('.', "").lowercase() in setOf("mp4", "mov", "m4v", "3gp", "webm", "mkv")

/**
 * Someone else's camera, live: the newest shot fills the screen the moment it lands, older ones sit
 * in a strip underneath. [loadImage] decodes a picture no larger than the given pixel size (null for
 * video or undecodable files). Shared by Android and desktop.
 */
@Composable
fun LiveViewerScreen(
    feed: LiveFeed,
    loadImage: (File, Int) -> ImageBitmap?,
    onOpen: (LiveShot) -> Unit,
    onStop: () -> Unit,
    onHide: (() -> Unit)? = null,
) {
    var pinned by remember { mutableStateOf<LiveShot?>(null) }
    val newest = feed.shots.lastOrNull()
    // A new shot always takes over the big view.
    LaunchedEffect(newest) { pinned = null }
    val shown = pinned ?: newest
    val strip = rememberLazyListState()
    LaunchedEffect(feed.shots.size) { if (feed.shots.isNotEmpty()) strip.animateScrollToItem(feed.shots.size - 1) }

    Box(Modifier.fillMaxSize()) {
        ScreenGlow(alpha = 0.12f, centerY = 120.dp, radius = 260.dp)
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.height(32.dp).clip(RoundedCornerShape(50)).background(Accent.copy(alpha = 0.14f)).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Dot(Accent)
                    Text(tr("Live").caps(), color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f)) {
                    Text(feed.peerName, style = displayStyle(17), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (feed.shots.isEmpty()) tr("Waiting for the first shot…")
                        else trPlural(feed.shots.size, "1 shot", "{n} shots"),
                        color = TextDim, fontSize = 12.sp,
                    )
                }
                if (onHide != null) ChipButton(tr("Hide"), onHide, bg = GlassMid, border = HairlineStrong, height = 38.dp)
                ChipButton(tr("Stop"), onStop, icon = CdIcons.Close, bg = GlassMid, border = HairlineStrong, height = 38.dp)
            }

            Box(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color.Black)
                    .border(1.dp, HairlineStrong, RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(shown, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "shot") { shot ->
                    if (shot == null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            CdIcon(CdIcons.Camera, TextSecondary, size = 40.dp)
                            Text(
                                tr("Shots from {0} appear here\nthe moment they're taken.", feed.peerName),
                                color = TextDim, fontSize = 14.sp, textAlign = TextAlign.Center,
                            )
                        }
                    } else {
                        ShotView(shot, loadImage, 2048, Modifier.fillMaxSize().clickable { onOpen(shot) }, ContentScale.Fit, Color.Transparent)
                    }
                }
                feed.incoming?.let { s ->
                    Row(
                        Modifier.align(Alignment.TopCenter).padding(12.dp).height(32.dp).clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Dot(Accent, size = 6.dp)
                        Text(tr("Arriving… {0}%", (s.fraction * 100).toInt()), color = TextPrimary, fontSize = 12.sp)
                    }
                }
            }

            if (feed.shots.size > 1) {
                LazyRow(
                    state = strip,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp),
                ) {
                    items(feed.shots, key = { it.file.path }) { shot ->
                        val selected = shot == shown
                        ShotView(
                            shot, loadImage, 240,
                            Modifier.size(64.dp).clip(RoundedCornerShape(14.dp))
                                .border(if (selected) 2.dp else 1.dp, if (selected) Accent else HairlineStrong, RoundedCornerShape(14.dp))
                                .clickable { pinned = shot },
                            ContentScale.Crop,
                        )
                    }
                }
            }
            Text(
                tr("Saved to your inbox as they arrive. Tap a shot to open it."),
                color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun ShotView(
    shot: LiveShot, loadImage: (File, Int) -> ImageBitmap?, maxPx: Int, modifier: Modifier, scale: ContentScale,
    bg: Color = GlassHigh,
) {
    val video = isVideoName(shot.name, shot.mime)
    val bitmap by produceState<ImageBitmap?>(null, shot.file, maxPx) {
        value = withContext(Dispatchers.IO) { runCatching { loadImage(shot.file, maxPx) }.getOrNull() }
    }
    Box(modifier.background(bg).semantics { contentDescription = shot.name }, contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = scale) }
        if (video) {
            Box(Modifier.size(if (maxPx > 500) 64.dp else 26.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                CdIcon(CdIcons.Play, TextPrimary, size = if (maxPx > 500) 28.dp else 12.dp)
            }
        } else if (bitmap == null) {
            Spacer(Modifier.width(1.dp))
        }
    }
}
