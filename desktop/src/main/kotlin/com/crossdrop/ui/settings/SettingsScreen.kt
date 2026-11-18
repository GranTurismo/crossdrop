package com.crossdrop.ui.settings

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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.core.i18n.tr
import com.crossdrop.core.i18n.trPlural
import com.crossdrop.platform.Platform
import com.crossdrop.ui.CdIcon
import com.crossdrop.ui.CdIcons
import com.crossdrop.ui.CircleButton
import com.crossdrop.ui.ConfirmDialog
import com.crossdrop.ui.DeviceNameDialog
import com.crossdrop.ui.GlassCard
import com.crossdrop.ui.LocalNavInset
import com.crossdrop.ui.MonoTag
import com.crossdrop.ui.RowSeparator
import com.crossdrop.ui.ScreenTitle
import com.crossdrop.ui.SectionLabel
import com.crossdrop.ui.ToggleRow
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.DangerText
import com.crossdrop.ui.theme.GlassHigh
import com.crossdrop.ui.theme.GlassLow
import com.crossdrop.ui.theme.OnAccent
import com.crossdrop.ui.theme.OnAccentMuted
import com.crossdrop.ui.theme.ScreenGlow
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.theme.TextSecondary
import com.crossdrop.ui.theme.displayStyle
import com.crossdrop.ui.theme.monoStyle
import com.crossdrop.ui.transfer.formatBytes

/** App-wide settings: identity, discovery, wireless drive, storage, about. Desktop copy of the Android screen. */
@Composable
fun SettingsScreen(
    deviceName: String,
    instanceId: String,
    port: Int,
    nsdEnabled: Boolean,
    onRename: (String) -> Unit,
    onSetNsd: (Boolean) -> Unit,
    onRescan: () -> Unit,
    inboxStats: () -> Pair<Int, Long>,
    onWipeInbox: () -> Unit,
    onClearHistory: () -> Unit,
    onShowWelcome: () -> Unit = {},
    vaultState: com.crossdrop.net.VaultHostManager.State? = null,
    onOpenDrive: () -> Unit = {},
    refreshSignal: Any?,
) {
    var renaming by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val stats = remember(refreshSignal) { inboxStats() }
    val storedCount = stats.first
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(Modifier.fillMaxSize()) {
        ScreenGlow(alpha = 0.2f, centerX = 0.26f, centerY = 160.dp, radius = 240.dp)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = top + 28.dp, bottom = LocalNavInset.current + 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ScreenTitle(tr("Settings"), Modifier.padding(bottom = 2.dp))

                // ------------------------------------------------- identity --
                GlassCard(radius = 28.dp, contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.requiredSize(68.dp).clip(CircleShape).background(Accent.copy(alpha = 0.15f)))
                            Box(
                                Modifier.fillMaxSize()
                                    .dropShadow(CircleShape, Shadow(30.dp, Accent.copy(alpha = 0.4f)))
                                    .clip(CircleShape).background(Accent),
                                contentAlignment = Alignment.Center,
                            ) { CdIcon(com.crossdrop.ui.radar.selfDeviceIcon, OnAccent, size = 24.dp) }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(tr("Name others see"), color = TextDim, fontSize = 12.sp)
                            Text(deviceName, style = displayStyle(22), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        CircleButton(CdIcons.Pencil, tr("Rename this device"), { renaming = true }, fill = GlassHigh, iconSize = 18.dp)
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(GlassLow)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("id ${formatId(instanceId)}", style = monoStyle(12, TextSecondary), modifier = Modifier.weight(1f))
                        Text(
                            if (port > 0) tr("port :{0} · new every launch", port) else tr("new every launch"),
                            color = Accent, fontSize = 11.sp,
                        )
                    }
                }

                // ------------------------------------------------- language --
                var pickingLanguage by remember { mutableStateOf(false) }
                if (pickingLanguage) com.crossdrop.ui.language.LanguageDialog { pickingLanguage = false }
                com.crossdrop.ui.language.LanguageRow({ pickingLanguage = true })

                // ------------------------------------------------ discovery --
                SectionLabel(tr("Discovery"), Modifier.padding(top = 4.dp))
                SettingsGroup {
                    ToggleRow(
                        "Wi-Fi", tr("Find devices on this network"), nsdEnabled, onSetNsd, icon = CdIcons.Wifi,
                    )
                    RowSeparator()
                    NavRow(
                        CdIcons.Radar, tr("Rescan network"),
                        if (Platform.isWindows) tr("After switching networks or allowing CrossDrop through Windows Firewall")
                        else tr("After switching networks or approving CrossDrop under Local Network"),
                        onRescan,
                    )
                }

                // ------------------------------------------- wireless drive --
                DriveCard(vaultState, onOpenDrive)

                // -------------------------------------------------- storage --
                SectionLabel(tr("Storage"), Modifier.padding(top = 4.dp))
                SettingsGroup {
                    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Inbox"), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(
                            "${trPlural(storedCount, "1 file", "{n} files")} · ${formatBytes(stats.second)}",
                            style = monoStyle(12, TextSecondary),
                        )
                    }
                    RowSeparator()
                    ActionRow(tr("Wipe inbox"), DangerText, enabled = stats.first > 0) { confirmWipe = true }
                    RowSeparator()
                    ActionRow(tr("Clear transfer history"), TextPrimary) { confirmClear = true }
                }

                // ---------------------------------------------------- about --
                GlassCard(
                    radius = 26.dp, fill = Color.White.copy(alpha = 0.03f), border = null,
                    contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("CrossDrop", style = displayStyle(15, tracking = 0f), modifier = Modifier.weight(1f))
                        Text("v${com.crossdrop.APP_VERSION}", style = monoStyle(12, TextDim))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MonoTag("X25519")
                        MonoTag("HKDF-SHA256")
                        MonoTag("ChaCha20-Poly1305")
                    }
                    Text(tr("No cloud. No accounts. No tracking."), color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    com.crossdrop.ui.ChipButton(tr("Show the welcome tour"), onShowWelcome, icon = com.crossdrop.ui.CdIcons.Help)
                }
            }
        }
    }

    if (renaming) {
        DeviceNameDialog(current = deviceName, onDone = { new ->
            renaming = false
            new?.let(onRename)
        })
    }
    if (confirmWipe) {
        ConfirmDialog(
            title = tr("Wipe the inbox?"),
            text = tr("Every received file is overwritten with zeros and deleted. This can't be undone."),
            confirmLabel = tr("Wipe inbox"),
            onConfirm = { confirmWipe = false; onWipeInbox() },
            onDismiss = { confirmWipe = false },
        )
    }
    if (confirmClear) {
        ConfirmDialog(
            title = tr("Clear transfer history?"),
            text = tr("This forgets the log of past transfers. Received files stay in your inbox."),
            confirmLabel = tr("Clear"),
            onConfirm = { confirmClear = false; onClearHistory() },
            onDismiss = { confirmClear = false },
        )
    }
}

/** "a3f9·71c2·e08b" — the rotating instance id, grouped for reading. */
private fun formatId(id: String): String = id.take(12).chunked(4).joinToString("·")

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    GlassCard(
        Modifier.fillMaxWidth(), radius = 26.dp, fill = GlassLow,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp), content = content,
    )
}

@Composable
private fun ActionRow(title: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 54.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.CenterStart,
    ) { Text(title, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun NavRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CdIcon(icon, TextPrimary, size = 22.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TextDim, fontSize = 12.sp)
        }
        CdIcon(CdIcons.ChevronRight, TextDim, size = 18.dp)
    }
}

/** Accent call-to-action while off; a live status card (pulsing dot) while the drive is on. */
@Composable
private fun DriveCard(state: com.crossdrop.net.VaultHostManager.State?, onOpen: () -> Unit) {
    if (state == null) {
        GlassCard(
            Modifier.fillMaxWidth(), radius = 28.dp, fill = Accent, border = null,
            glow = Accent.copy(alpha = 0.2f), glowRadius = 44.dp, glowOffsetY = 14.dp,
            contentPadding = PaddingValues(18.dp), onClick = onOpen,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(OnAccent.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) { CdIcon(CdIcons.Drive, OnAccent, size = 24.dp) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(tr("Turn into drive"), style = displayStyle(17, tracking = -0.02f, color = OnAccent))
                    Text(
                        tr("Open this {0} from other computers, phones or any browser", Platform.current.computerNoun),
                        color = OnAccentMuted, fontSize = 12.sp, lineHeight = 16.sp,
                    )
                }
                CdIcon(CdIcons.ChevronRight, OnAccent, size = 20.dp)
            }
        }
    } else {
        val glow by rememberInfiniteTransition(label = "drive").animateFloat(
            0.4f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "g",
        )
        GlassCard(
            Modifier.fillMaxWidth(), radius = 28.dp, border = Accent.copy(alpha = 0.35f),
            glow = Accent.copy(alpha = 0.13f), contentPadding = PaddingValues(18.dp), onClick = onOpen,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) { CdIcon(CdIcons.Drive, Accent, size = 24.dp) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).alpha(glow).clip(CircleShape).background(Accent))
                        Spacer(Modifier.size(8.dp))
                        Text(tr("Drive is on"), style = displayStyle(17, tracking = -0.02f))
                    }
                    val n = state.clients.size
                    Text(
                        "${state.rootLabel} · " + if (n == 0) tr("no one connected") else tr("{0} connected", n),
                        color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                CdIcon(CdIcons.ChevronRight, TextDim, size = 20.dp)
            }
        }
    }
}
