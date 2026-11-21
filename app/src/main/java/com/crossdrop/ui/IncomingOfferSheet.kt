package com.crossdrop.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import com.crossdrop.core.i18n.tr
import com.crossdrop.core.i18n.trPlural
import com.crossdrop.core.protocol.ItemCategory
import com.crossdrop.core.protocol.OfferKind
import com.crossdrop.net.IncomingOffer
import com.crossdrop.net.TransferHub
import com.crossdrop.ui.radar.deviceIconFor
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.Glass
import com.crossdrop.ui.theme.GlassMid
import com.crossdrop.ui.theme.GlassRaised
import com.crossdrop.ui.theme.Hairline
import com.crossdrop.ui.theme.HairlineSoft
import com.crossdrop.ui.theme.Ink
import com.crossdrop.ui.theme.MonoFont
import com.crossdrop.ui.theme.ScreenGlow
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.theme.TextSecondary
import com.crossdrop.ui.theme.caps
import com.crossdrop.ui.theme.displayStyle
import com.crossdrop.ui.theme.monoStyle
import com.crossdrop.ui.theme.trackingEm
import com.crossdrop.ui.transfer.formatBytes

/**
 * Full-screen inbound OFFER. The ring around the sender counts down the
 * coordinator's auto-decline window; the human answers only with the
 * buttons (the shell maps system Back to Decline). Text/link, contact, live-camera
 * and switch-phones offers get their own wording and actions. [addsToContacts] = this
 * device has an address book (desktops save contact cards to the inbox instead).
 */
@Composable
fun IncomingOfferSheet(
    offer: IncomingOffer,
    addsToContacts: Boolean = true,
    onRespond: (accept: Boolean) -> Unit,
) {
    android.util.Log.i("IncomingOfferSheet", "composed for ${offer.offer.transferId}")
    val files = offer.offer.files
    val total = files.sumOf { it.size }
    val timeout = TransferHub.OFFER_TIMEOUT_MS
    val kind = offer.kind
    val text = offer.text
    val isLink = text != null && isLinkText(text)
    val switch = kind == OfferKind.SWITCH
    val camera = kind == OfferKind.CAMERA
    val cards = offer.contacts
    val contact = kind == OfferKind.CONTACT
    val contactCount = cards.size.coerceAtLeast(1)
    val apps = remember(offer) { if (kind == OfferKind.APP) appGroups(files) else emptyList() }
    val app = apps.isNotEmpty()
    // A whole folder: say so by name, instead of "{n} files" that the person never picked one by one.
    val folder = remember(offer) { if (kind == null || kind == OfferKind.FILES) com.crossdrop.core.protocol.Folders.topFolder(files) else null }
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val startedAt = remember(offer) { System.currentTimeMillis() }
    val elapsed = remember(offer) { mutableLongStateOf(0L) }
    LaunchedEffect(offer) {
        while (elapsed.longValue < timeout) {
            withFrameMillis { elapsed.longValue = System.currentTimeMillis() - startedAt }
        }
    }
    val secondsLeft by remember(offer) {
        derivedStateOf { ((timeout - elapsed.longValue + 999) / 1000).coerceAtLeast(0) }
    }

    Surface(Modifier.fillMaxSize(), color = Ink) {
        Box(Modifier.fillMaxSize()) {
            ScreenGlow(centerY = 215.dp, radius = 275.dp)
            Column(
                Modifier.fillMaxSize().safeDrawingPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier.weight(1f).widthIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    Row(
                        Modifier.height(32.dp).clip(RoundedCornerShape(50)).background(GlassMid)
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(50))
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Dot(Accent)
                        Text(
                            when {
                                text != null -> if (isLink) tr("Incoming link") else tr("Incoming text")
                                contact -> if (contactCount == 1) tr("Incoming contact") else tr("Incoming contacts")
                                app -> if (apps.size == 1) tr("Incoming app") else tr("Incoming apps")
                                camera -> tr("Live camera")
                                switch -> tr("Switch phones")
                                else -> tr("Incoming files")
                            }.caps(), color = TextSecondary, fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold, letterSpacing = trackingEm(0.06f),
                        )
                    }

                    CountdownAvatar(
                        peerName = offer.peerName,
                        fraction = { 1f - elapsed.longValue.toFloat() / timeout },
                        modifier = Modifier.padding(top = 10.dp),
                    )

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            when {
                                text != null ->
                                    if (isLink) tr("{0} sent you a link", offer.peerName) else tr("{0} sent you some text", offer.peerName)
                                contact -> trPlural(contactCount, "{0} sent you a contact", "{0} sent you {n} contacts", offer.peerName)
                                app -> trPlural(apps.size, "{0} wants to send you an app", "{0} wants to send you {n} apps", offer.peerName)
                                camera -> tr("{0} wants to share their camera", offer.peerName)
                                switch -> tr("{0} wants to move to this device", offer.peerName)
                                folder != null -> tr("{0} wants to send you the folder {1}", offer.peerName, folder)
                                else -> trPlural(files.size, "{0} wants to send you a file", "{0} wants to send you {n} files", offer.peerName)
                            },
                            style = displayStyle(28, tracking = -0.035f).copy(lineHeight = 31.sp),
                            textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildAnnotatedString {
                                if (text == null && !camera && !contact) append(formatBytes(offer.offer.batch?.totalBytes?.takeIf { switch && it > 0 } ?: total) + " · ")
                                // The sentence is one translatable string; the seconds are styled where {0} lands.
                                val parts = tr("auto-declines in {0}", "\u0001").split('\u0001', limit = 2)
                                append(parts[0])
                                withStyle(SpanStyle(fontFamily = MonoFont, color = Accent)) { append("$secondsLeft") }
                                withStyle(SpanStyle(color = Accent)) { append(" s") }
                                parts.getOrNull(1)?.let { append(it) }
                            },
                            color = TextDim, fontSize = 14.sp,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }

                    if (text != null) {
                        GlassCard(Modifier.fillMaxWidth(), radius = 28.dp, fill = Glass, border = Hairline) {
                            Text(
                                text, style = if (isLink) monoStyle(14, TextPrimary) else androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 15.sp, lineHeight = 21.sp),
                                maxLines = 12, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else if (contact && cards.isNotEmpty()) {
                        ContactCards(cards, addsToContacts)
                    } else if (camera) {
                        GlassCard(Modifier.fillMaxWidth(), radius = 28.dp, fill = Glass, border = Hairline) {
                            Text(
                                tr("Every photo and video they take shows up here the moment it's taken, without asking again. Closing the viewer ends it."),
                                color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp,
                            )
                        }
                    } else if (switch) {
                        SwitchSummary(offer)
                    } else if (app) {
                        AppList(apps)
                    } else GlassCard(
                        Modifier.fillMaxWidth(), radius = 28.dp, fill = Glass, border = Hairline,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
                    ) {
                        files.take(4).forEachIndexed { i, f ->
                            if (i > 0) RowSeparator()
                            Row(
                                Modifier.fillMaxWidth().padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                FileTypeTile(f.name, size = 42.dp)
                                Text(
                                    f.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(formatBytes(f.size), style = monoStyle(12, TextDim))
                            }
                        }
                        if (files.size > 4) {
                            RowSeparator(color = HairlineSoft)
                            Text(
                                tr("+ {0} more", files.size - 4),
                                color = TextDim, fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (text != null) {
                        PillButton(
                            tr("Dismiss"), { onRespond(false) }, Modifier.weight(1f),
                            style = PillStyle.Outline, height = 62.dp, fontSize = 16,
                        )
                        PillButton(
                            tr("Copy"), { clipboard.setText(AnnotatedString(text)); onRespond(true) }, Modifier.weight(1f),
                            style = if (isLink) PillStyle.Glass else PillStyle.Accent, height = 62.dp, fontSize = 16,
                            icon = CdIcons.Copy, glow = !isLink,
                        )
                        if (isLink) PillButton(
                            tr("Open"), { onRespond(true); runCatching { uriHandler.openUri(text.trim()) } }, Modifier.weight(1f),
                            style = PillStyle.Accent, height = 62.dp, fontSize = 16, icon = CdIcons.Globe, glow = true,
                        )
                    } else {
                        PillButton(
                            tr("Decline"), { onRespond(false) }, Modifier.weight(1f),
                            style = PillStyle.Outline, height = 62.dp, fontSize = 16,
                        )
                        PillButton(
                            when {
                                camera -> tr("Watch")
                                switch -> tr("Start moving")
                                contact && addsToContacts -> tr("Add to contacts")
                                contact -> tr("Save")
                                app -> tr("Receive")
                                else -> tr("Accept")
                            }, { onRespond(true) }, Modifier.weight(1.5f),
                            style = PillStyle.Accent, height = 62.dp, fontSize = 16,
                            icon = if (contact && addsToContacts) CdIcons.Contacts else CdIcons.Check, glow = true,
                        )
                    }
                }
            }
        }
    }
}

/** One row per app (its base APK and splits counted together), named as the sender called it. */
private class AppGroup(val name: String, val bytes: Long)

private fun appGroups(files: List<com.crossdrop.core.protocol.FileMeta>): List<AppGroup> =
    files.groupBy { it.pkg ?: it.name }.values.map { parts ->
        AppGroup(parts.first().name.removeSuffix(".apk"), parts.sumOf { it.size })
    }

@Composable
private fun AppList(apps: List<AppGroup>) {
    GlassCard(
        Modifier.fillMaxWidth(), radius = 28.dp, fill = Glass, border = Hairline,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
    ) {
        apps.take(4).forEachIndexed { i, a ->
            if (i > 0) RowSeparator()
            Row(
                Modifier.fillMaxWidth().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(GlassMid),
                    contentAlignment = Alignment.Center,
                ) { CdIcon(CdIcons.Apk, Accent, size = 22.dp) }
                Text(
                    a.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(formatBytes(a.bytes), style = monoStyle(12, TextDim))
            }
        }
        if (apps.size > 4) {
            RowSeparator(color = HairlineSoft)
            Text(
                tr("+ {0} more", apps.size - 4),
                color = TextDim, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            )
        }
        RowSeparator(color = HairlineSoft)
        Text(
            tr("Nothing installs by itself: you install each app afterwards, through Android's own installer."),
            color = TextDim, fontSize = 13.sp, lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
        )
    }
}

/** Sender glyph in a glass disc, ringed by the auto-decline countdown and a soft pulse. */
@Composable
private fun CountdownAvatar(peerName: String, fraction: () -> Float, modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "offer").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "p",
    )
    Box(modifier.size(168.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    val s = 0.6f + 0.65f * pulse
                    scaleX = s; scaleY = s; alpha = 0.6f * (1f - pulse)
                }
                .border(1.5.dp, Accent, CircleShape),
        )
        Canvas(Modifier.fillMaxSize()) {
            val sw = 4.dp.toPx()
            val inset = sw / 2 + 2.dp.toPx()
            val arcSize = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
            val tl = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(Color.White.copy(alpha = 0.08f), 0f, 360f, false, tl, arcSize, style = Stroke(sw))
            rotate(-90f) {
                drawArc(
                    Accent, 0f, 360f * fraction().coerceIn(0f, 1f), false, tl, arcSize,
                    style = Stroke(sw, cap = StrokeCap.Round),
                )
            }
        }
        Box(
            Modifier.size(112.dp)
                .dropShadow(CircleShape, Shadow(50.dp, Accent.copy(alpha = 0.18f)))
                .clip(CircleShape).background(GlassRaised)
                .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { CdIcon(deviceIconFor(peerName), TextPrimary, size = 44.dp) }
    }
}

/** "http(s)://…" with nothing else around it. */
fun isLinkText(text: String): Boolean {
    val t = text.trim()
    return !t.contains(' ') && !t.contains('\n') &&
        (t.startsWith("http://", true) || t.startsWith("https://", true))
}

/** Who the cards are: initials, name and the first number or address, up to four of them. */
@Composable
private fun ContactCards(cards: List<com.crossdrop.core.contacts.VContact>, addsToContacts: Boolean) {
    GlassCard(
        Modifier.fillMaxWidth(), radius = 28.dp, fill = Glass, border = Hairline,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
    ) {
        cards.take(4).forEachIndexed { i, c ->
            if (i > 0) RowSeparator()
            Row(
                Modifier.fillMaxWidth().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ContactInitials(c.displayName)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        c.displayName, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val detail = listOfNotNull(
                        c.phones.firstOrNull()?.value, c.emails.firstOrNull()?.value,
                        c.org.takeIf { it.isNotBlank() && it != c.displayName },
                    ).firstOrNull()
                    if (detail != null) Text(detail, style = monoStyle(12, TextDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (cards.size > 4) {
            RowSeparator(color = HairlineSoft)
            Text(
                tr("+ {0} more", cards.size - 4),
                color = TextDim, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            )
        }
        Text(
            if (addsToContacts) tr("Goes straight into your contacts. Anyone who's already there is skipped.")
            else tr("Saved to your Inbox as a contact card (.vcf) you can open in your contacts app."),
            color = TextDim, fontSize = 12.sp, lineHeight = 17.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
        )
    }
}

/** Round initials badge for a contact without a photo. */
@Composable
fun ContactInitials(name: String, size: androidx.compose.ui.unit.Dp = 42.dp) {
    val initials = name.split(' ', '-').filter { it.isNotBlank() }.take(2)
        .joinToString("") { String(Character.toChars(it.codePointAt(0))).uppercase() }.ifEmpty { "?" }
    Box(
        Modifier.size(size).clip(CircleShape).background(Accent.copy(alpha = 0.14f))
            .border(1.dp, Accent.copy(alpha = 0.3f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(initials, color = Accent, fontSize = (size.value * 0.36f).sp, fontWeight = FontWeight.SemiBold) }
}

/** What a phone move brings: counts per kind from the batch totals. */
@Composable
private fun SwitchSummary(offer: IncomingOffer) {
    val b = offer.offer.batch
    val cats = offer.offer.files.groupingBy { it.cat }.eachCount()
    val rows = listOf(
        Triple(CdIcons.Photo, tr("Photos"), b?.photos ?: cats[ItemCategory.PHOTO] ?: 0),
        Triple(CdIcons.Video, tr("Videos"), b?.videos ?: cats[ItemCategory.VIDEO] ?: 0),
        Triple(CdIcons.Contacts, tr("Contacts"), b?.contacts ?: 0),
        Triple(CdIcons.Doc, tr("Files"), b?.files ?: cats[null] ?: 0),
    ).filter { it.third > 0 }
    GlassCard(
        Modifier.fillMaxWidth(), radius = 28.dp, fill = Glass, border = Hairline,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
    ) {
        rows.forEachIndexed { i, (icon, label, n) ->
            if (i > 0) RowSeparator()
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CdIcon(icon, Accent, size = 22.dp)
                Text(label, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("%,d".format(n), style = monoStyle(14, TextSecondary))
            }
        }
        Text(
            tr("Photos and videos go to your gallery with their original dates, contacts to your address book. Keep both devices awake and on this Wi-Fi."),
            color = TextDim, fontSize = 12.sp, lineHeight = 17.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}
