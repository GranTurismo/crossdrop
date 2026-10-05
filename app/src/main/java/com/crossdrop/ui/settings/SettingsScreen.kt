package com.crossdrop.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.BuildConfig
import com.crossdrop.ui.AddPeerDialog
import com.crossdrop.ui.DeviceNameDialog
import com.crossdrop.ui.theme.DeepSpace
import com.crossdrop.ui.theme.DeepSpaceRaised
import com.crossdrop.ui.theme.DangerSoft
import com.crossdrop.ui.theme.GlassEdge
import com.crossdrop.ui.theme.Phosphor
import com.crossdrop.ui.theme.RippleTeal
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import com.crossdrop.ui.transfer.formatBytes

/** App-wide settings: identity, discovery layers, storage, about, dev tools. */
@Composable
fun SettingsScreen(
    deviceName: String,
    instanceId: String,
    port: Int,
    nsdEnabled: Boolean,
    bleEnabled: Boolean,
    onRename: (String) -> Unit,
    onSetNsd: (Boolean) -> Unit,
    onSetBle: (Boolean) -> Unit,
    inboxStats: () -> Pair<Int, Long>,
    onWipeInbox: () -> Unit,
    onClearHistory: () -> Unit,
    vaultState: com.crossdrop.net.VaultHostManager.State? = null,
    onVaultStart: (writable: Boolean, usePin: Boolean) -> Unit = { _, _ -> },
    onVaultStop: () -> Unit = {},
    onSelfTest: (() -> Unit)?,
    onAddPeer: ((String, Int) -> Unit)?,
    refreshSignal: Any?,
) {
    var renaming by remember { mutableStateOf(false) }
    var addingPeer by remember { mutableStateOf(false) }
    var vaultRw by remember { mutableStateOf(true) }
    var vaultPin by remember { mutableStateOf(false) }
    var showVaultQr by remember { mutableStateOf(false) }
    val stats = remember(refreshSignal) { inboxStats() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Text(
            "Settings", color = TextPrimary, fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(top = 20.dp, bottom = 12.dp),
        )

        // ---------------------------------------------------------- device --
        SectionLabel("Device")
        SettingsCard {
            SettingRow(
                title = deviceName,
                subtitle = "visible name · tap to change",
                onClick = { renaming = true },
            )
            RowDivider()
            SettingRow(
                title = "Device id",
                subtitle = instanceId.take(12),
            )
            if (port > 0) {
                RowDivider()
                SettingRow(
                    title = "Listening port",
                    subtitle = ":$port",
                )
            }
        }

        // ------------------------------------------------------- discovery --
        SectionLabel("Discovery")
        SettingsCard {
            ToggleRow(
                title = "Wi-Fi (mDNS)",
                subtitle = "find devices on the same network",
                checked = nsdEnabled,
                onChecked = onSetNsd,
            )
            RowDivider()
            ToggleRow(
                title = "Bluetooth LE",
                subtitle = "find devices without a shared network",
                checked = bleEnabled,
                onChecked = onSetBle,
            )
        }

        // ------------------------------------------------------ local drive --
        SectionLabel("Local drive")
        SettingsCard {
            val hosting = vaultState != null
            ToggleRow(
                title = "Host LAN vault",
                subtitle = if (hosting) vaultState!!.url
                    else "let nearby devices browse this phone's vault",
                checked = hosting,
                onChecked = { on ->
                    if (on) onVaultStart(vaultRw, vaultPin) else onVaultStop()
                },
            )
            if (hosting) {
                val v = vaultState!!
                RowDivider()
                ToggleRow(
                    title = "Read-write",
                    subtitle = "clients may upload files",
                    checked = v.writable,
                    onChecked = { onVaultStart(it, v.pin != null) },   // restarts
                )
                RowDivider()
                ToggleRow(
                    title = "PIN lock",
                    subtitle = "clients need the 4-digit code",
                    checked = v.pin != null,
                    onChecked = { onVaultStart(v.writable, it) },      // restarts
                )
                if (v.pin != null) {
                    RowDivider()
                    SettingRow(
                        title = "PIN  ${v.pin}",
                        subtitle = "regenerates on every restart",
                        titleColor = Phosphor,
                    )
                }
                RowDivider()
                SettingRow(
                    title = "Connected",
                    subtitle = if (v.clients.isEmpty()) "no clients"
                        else v.clients.joinToString(),
                )
                RowDivider()
                SettingRow(
                    title = "Traffic",
                    subtitle = "↓ ${formatBytes(v.bytesOut)} out · ↑ ${formatBytes(v.bytesIn)} in",
                )
                RowDivider()
                SettingRow(
                    title = if (showVaultQr) "Hide browser QR" else "Show browser QR",
                    subtitle = "any browser on this Wi-Fi can open the drive",
                    onClick = { showVaultQr = !showVaultQr },
                )
                if (showVaultQr) {
                    androidx.compose.foundation.layout.Column(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        com.crossdrop.ui.QrCard(v.url, size = 170.dp)
                    }
                }
                RowDivider()
                SettingRow(
                    title = "Kill drive",
                    subtitle = "drop listener + clients, purge staging",
                    titleColor = DangerSoft,
                    onClick = onVaultStop,
                )
            } else {
                RowDivider()
                ToggleRow(
                    title = "Read-write",
                    subtitle = "clients may upload files",
                    checked = vaultRw,
                    onChecked = { vaultRw = it },
                )
                RowDivider()
                ToggleRow(
                    title = "PIN lock",
                    subtitle = "clients need the 4-digit code",
                    checked = vaultPin,
                    onChecked = { vaultPin = it },
                )
            }
        }

        // --------------------------------------------------------- storage --
        SectionLabel("Storage")
        SettingsCard {
            SettingRow(
                title = "Inbox",
                subtitle = "${stats.first} file${if (stats.first == 1) "" else "s"} · ${formatBytes(stats.second)}",
            )
            if (stats.first > 0) {
                RowDivider()
                SettingRow(
                    title = "Wipe inbox",
                    subtitle = "zero-fill and delete every received file",
                    titleColor = DangerSoft,
                    onClick = onWipeInbox,
                )
            }
            RowDivider()
            SettingRow(
                title = "Clear transfer history",
                subtitle = "forget the log of past transfers",
                titleColor = DangerSoft,
                onClick = onClearHistory,
            )
        }

        // ----------------------------------------------------------- about --
        SectionLabel("About")
        SettingsCard {
            SettingRow(
                title = "CrossDrop ${BuildConfig.VERSION_NAME}",
                subtitle = "local-only file sharing",
            )
            RowDivider()
            SettingRow(
                title = "Encryption",
                subtitle = "X25519 · HKDF-SHA256 · ChaCha20-Poly1305",
            )
            RowDivider()
            SettingRow(
                title = "No cloud. No accounts. No tracking.",
                subtitle = "files travel directly between devices on your network",
                titleColor = Phosphor,
            )
        }

        // ------------------------------------------------------- developer --
        if (onSelfTest != null || onAddPeer != null) {
            SectionLabel("Developer")
            SettingsCard {
                onSelfTest?.let {
                    SettingRow(
                        title = "Loopback self-test",
                        subtitle = "send a 4 MB file to this device",
                        onClick = it,
                    )
                    if (onAddPeer != null) RowDivider()
                }
                onAddPeer?.let { add ->
                    SettingRow(
                        title = "Add peer by address",
                        subtitle = "place a peer on the radar manually",
                        onClick = { addingPeer = true },
                    )
                    if (addingPeer) {
                        AddPeerDialog(
                            onDone = { addingPeer = false },
                            onAdd = { h, p -> addingPeer = false; add(h, p) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (renaming) {
        DeviceNameDialog(current = deviceName, onDone = { new ->
            renaming = false
            new?.let(onRename)
        })
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = TextDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(start = 6.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = DeepSpaceRaised,
        border = BorderStroke(1.dp, GlassEdge),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
}

@Composable
private fun RowDivider() {
    androidx.compose.material3.HorizontalDivider(
        color = GlassEdge.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = 14.dp),
    )
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    titleColor: androidx.compose.ui.graphics.Color = TextPrimary,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = titleColor, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TextDim, fontSize = 12.sp)
        }
        if (onClick != null) {
            Spacer(Modifier.width(8.dp))
            Text("›", color = TextDim, fontSize = 18.sp)
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TextDim, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DeepSpace,
                checkedTrackColor = RippleTeal,
            ),
        )
    }
}
