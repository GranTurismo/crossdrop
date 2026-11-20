package com.crossdrop.ui.apps

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.crossdrop.apps.AppInbox
import com.crossdrop.apps.InstalledApps
import com.crossdrop.core.i18n.tr
import com.crossdrop.core.i18n.trPlural
import com.crossdrop.ui.BackHeader
import com.crossdrop.ui.CdIcon
import com.crossdrop.ui.CdIcons
import com.crossdrop.ui.ChipButton
import com.crossdrop.ui.FootNote
import com.crossdrop.ui.GlassCard
import com.crossdrop.ui.PillButton
import com.crossdrop.ui.PillStyle
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.Amber
import com.crossdrop.ui.theme.GlassMid
import com.crossdrop.ui.theme.HairlineStrong
import com.crossdrop.ui.theme.OnAccent
import com.crossdrop.ui.theme.ScreenGlow
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.theme.TextSecondary
import com.crossdrop.ui.theme.monoStyle
import com.crossdrop.ui.transfer.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Pick installed apps to send to another Android phone (each goes with all its pieces). */
@Composable
fun AppPickerScreen(target: String, onSend: (List<InstalledApps.App>) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var all by remember { mutableStateOf<List<InstalledApps.App>?>(null) }
    LaunchedEffect(Unit) { all = withContext(Dispatchers.IO) { runCatching { InstalledApps.list(context) }.getOrDefault(emptyList()) } }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(listOf<String>()) }
    val shown = remember(all, query) {
        val q = query.trim().lowercase()
        all.orEmpty().filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.contains(q) }
    }
    val picked = all.orEmpty().filter { it.pkg in selected }
    val pickedSize = formatBytes(picked.sumOf { it.bytes })

    Box(Modifier.fillMaxSize()) {
        ScreenGlow(alpha = 0.12f, centerY = 120.dp, radius = 220.dp)
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 16.dp)) {
            BackHeader(
                tr("Send apps"), onClose,
                subtitle = { Text(tr("to {0}", target), color = TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
            SearchField(query, { query = it }, Modifier.padding(top = 12.dp, bottom = 8.dp))
            val list = all
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    list == null -> Text(tr("Loading apps…"), color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 24.dp))
                    list.isEmpty() -> FootNote(tr("No apps to send: only apps you installed yourself can be shared."), Modifier.padding(top = 16.dp), icon = CdIcons.Apk)
                    shown.isEmpty() -> Text(tr("No app matches “{0}”.", query), color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 24.dp))
                    else -> LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
                        items(shown, key = { it.pkg }) { app ->
                            val on = app.pkg in selected
                            AppRow(app, on) { selected = if (on) selected - app.pkg else selected + app.pkg }
                        }
                    }
                }
            }
            FootNote(
                tr("The other phone gets a fresh copy: data, accounts and purchases stay on this phone. Some paid apps and games also need Google Play there."),
                Modifier.padding(top = 8.dp), icon = CdIcons.Shield,
            )
            PillButton(
                if (picked.isEmpty()) tr("Pick apps")
                else trPlural(picked.size, "Send 1 app · {0}", "Send {n} apps · {0}", pickedSize),
                { onSend(picked) },
                Modifier.fillMaxWidth().padding(vertical = 14.dp),
                enabled = picked.isNotEmpty(), icon = CdIcons.ArrowUp, glow = true,
            )
        }
    }
}

@Composable
private fun AppRow(app: InstalledApps.App, selected: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    val icon by produceIcon(app.pkg) { app.icon(context.packageManager) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(if (selected) Accent.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(role = Role.Checkbox, onClickLabel = if (selected) tr("Remove") else tr("Add"), onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(icon, 40)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(app.label, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(app.versionName?.take(20), formatBytes(app.bytes)).joinToString(" · "),
                style = monoStyle(12, TextDim), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier.size(24.dp).clip(CircleShape)
                .background(if (selected) Accent else Color.Transparent)
                .border(1.5.dp, if (selected) Accent else HairlineStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected) CdIcon(CdIcons.Check, OnAccent, size = 14.dp) }
    }
}

/** The apps another phone sent: install each through Android's own installer. */
@Composable
fun ReceivedAppsScreen(inbox: AppInbox, onDone: () -> Unit) {
    val batch by inbox.state.collectAsState()
    val b = batch ?: return
    var canInstall by remember { mutableStateOf(inbox.canInstall()) }
    var checks by remember { mutableIntStateOf(0) }
    // Back from the "Install unknown apps" switch, or from installing/removing apps elsewhere.
    LifecycleResumeEffect(checks) {
        canInstall = inbox.canInstall()
        inbox.refresh()
        onPauseOrDispose { }
    }
    val allow = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { canInstall = inbox.canInstall(); checks++ }
    val context = LocalContext.current

    Box(Modifier.fillMaxSize()) {
        ScreenGlow(alpha = 0.16f, centerY = 160.dp, radius = 240.dp)
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                BackHeader(tr("Apps from {0}", b.peerName), onDone)
                if (!canInstall) {
                    GlassCard(
                        Modifier.fillMaxWidth(), radius = 24.dp, fill = Amber.copy(alpha = 0.06f), border = Amber.copy(alpha = 0.28f),
                        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(tr("Allow CrossDrop to install apps"), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            tr("Android asks once. Switch on “Allow from this source”, then come back."),
                            color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp,
                        )
                        ChipButton(tr("Open settings"), { runCatching { allow.launch(inbox.allowIntent()) } })
                    }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(b.items, key = { it.pkg }) { item ->
                        ReceivedAppRow(
                            item, inbox, canInstall,
                            onInstall = { if (inbox.canInstall()) inbox.install(item.pkg) else runCatching { allow.launch(inbox.allowIntent()) } },
                            onOpen = { inbox.launchIntent(item)?.let { runCatching { context.startActivity(it) } } },
                        )
                    }
                }
                FootNote(
                    tr("Android checks each app before installing it, like any app from outside Google Play. Its data stays on the other phone."),
                    icon = CdIcons.Shield,
                )
            }
            PillButton(tr("Done"), onDone, Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(top = 12.dp), style = PillStyle.Glass)
        }
    }
}

@Composable
private fun ReceivedAppRow(item: AppInbox.Item, inbox: AppInbox, canInstall: Boolean, onInstall: () -> Unit, onOpen: () -> Unit) {
    val icon by produceIcon(item.pkg + item.base.path) { inbox.icon(item) }
    GlassCard(Modifier.fillMaxWidth(), radius = 22.dp, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppIcon(icon, 44)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.label, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(item.versionName?.take(20), formatBytes(item.bytes)).joinToString(" · "),
                    style = monoStyle(12, TextDim), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            when (item.status) {
                AppInbox.Status.INSTALLING -> CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                AppInbox.Status.INSTALLED ->
                    if (inbox.launchIntent(item) != null) ChipButton(tr("Open"), onOpen, height = 36.dp, fontSize = 13)
                    else Text(tr("Installed"), color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                else -> ChipButton(
                    when {
                        item.status == AppInbox.Status.FAILED -> tr("Try again")
                        item.installedVersion == null -> tr("Install")
                        item.installedVersion == item.versionName -> tr("Reinstall")
                        else -> tr("Update")
                    },
                    onInstall, height = 36.dp, fontSize = 13,
                    fg = if (canInstall) OnAccent else TextDim, bg = if (canInstall) Accent else GlassMid,
                )
            }
        }
        item.error?.takeIf { item.status == AppInbox.Status.FAILED }?.let {
            Text(tr("Android didn't install it: {0}", it), color = Amber, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun AppIcon(bitmap: ImageBitmap?, sizeDp: Int) {
    Box(Modifier.size(sizeDp.dp).clip(RoundedCornerShape((sizeDp * 0.28f).dp)).background(GlassMid), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.size(sizeDp.dp))
        else CdIcon(CdIcons.Apk, TextDim, size = (sizeDp / 2).dp)
    }
}

/** Loads an app icon off the main thread, once per [key]. */
@Composable
private fun produceIcon(key: String, load: () -> android.graphics.drawable.Drawable?) =
    androidx.compose.runtime.produceState<ImageBitmap?>(null, key) {
        value = withContext(Dispatchers.IO) { runCatching { load()?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull() }
    }

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier.fillMaxWidth().height(48.dp).clip(shape).background(GlassMid)
            .border(1.dp, Color.White.copy(alpha = 0.1f), shape).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CdIcon(CdIcons.Search, TextDim, size = 18.dp)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(tr("Search apps"), color = TextDim, fontSize = 15.sp, maxLines = 1)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp),
                cursorBrush = SolidColor(Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = tr("Clear")) { onChange("") },
                contentAlignment = Alignment.Center,
            ) { CdIcon(CdIcons.Close, TextDim, size = 14.dp) }
        }
    }
}

