package com.crossdrop.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.crossdrop.ui.theme.OnAccent
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.core.connect.DirectWifi
import com.crossdrop.core.i18n.tr
import com.crossdrop.net.DirectWifiJoin
import com.crossdrop.platform.Platform
import com.crossdrop.ui.CdIcon
import com.crossdrop.ui.CdIcons
import com.crossdrop.ui.ChipButton
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.Amber
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary

/**
 * On the radar while this computer joins, or is on, an Android phone's CrossDrop Wi-Fi ("Join a CrossDrop Wi-Fi"):
 * what is happening, Leave, and on a failure what to do instead (the name and password for the Wi-Fi menu).
 * Desktop only; Android's twin is `JoinedLinkRow` + `JoinWifiScreen`.
 */
@Composable
fun DirectWifiRow(
    state: DirectWifiJoin.State,
    onRetry: () -> Unit,
    onLeave: () -> Unit,
    onDismiss: () -> Unit,
    onGuide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state == DirectWifiJoin.State.Idle) return
    val failed = state is DirectWifiJoin.State.Failed
    val tint = if (failed) Amber else Accent
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(tint.copy(alpha = 0.07f)).border(1.dp, tint.copy(alpha = 0.3f), shape)
            .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val busy = state == DirectWifiJoin.State.Joining || state == DirectWifiJoin.State.Connecting
            if (busy) CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            else CdIcon(CdIcons.Wifi, tint, size = 18.dp)
            val (title, sub) = texts(state)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (sub.isNotEmpty()) Text(sub, color = TextDim, fontSize = 12.sp, lineHeight = 17.sp)
            }
            when (state) {
                DirectWifiJoin.State.Joining, DirectWifiJoin.State.Connecting, is DirectWifiJoin.State.Joined ->
                    ChipButton(tr("Leave"), onLeave, height = 34.dp, fontSize = 13)
                is DirectWifiJoin.State.Failed -> ChipButton(tr("Close"), onDismiss, height = 34.dp, fontSize = 13)
                DirectWifiJoin.State.Idle -> Unit
            }
        }
        if (failed) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipButton(tr("Try again"), onRetry, fg = OnAccent, bg = Accent, height = 34.dp, fontSize = 13)
                ChipButton(tr("Show me how"), onGuide, height = 34.dp, fontSize = 13)
            }
        }
    }
}

@Composable
private fun texts(state: DirectWifiJoin.State): Pair<String, String> = when (state) {
    DirectWifiJoin.State.Idle -> "" to ""
    DirectWifiJoin.State.Joining -> tr("Joining {0}…", DirectWifi.SSID) to tr("This can take a few seconds.")
    DirectWifiJoin.State.Connecting -> tr("On {0}", DirectWifi.SSID) to tr("Reaching the phone…")
    is DirectWifiJoin.State.Joined ->
        (if (state.hostName != null) tr("Linked with {0}", state.hostName) else tr("On {0}", DirectWifi.SSID)) to
            tr("Private link · no internet")
    is DirectWifiJoin.State.Failed -> when (state.reason) {
        DirectWifiJoin.Failure.NOT_FOUND -> tr("Couldn't find a CrossDrop Wi-Fi") to
            tr("On the phone, tap Connect directly, then Make a CrossDrop Wi-Fi. Keep it close to this {0}.", Platform.current.computerNoun)
        DirectWifiJoin.Failure.NO_WIFI -> tr("Wi-Fi is off on this {0}", Platform.current.computerNoun) to
            tr("Turn on Wi-Fi, then try again.")
        DirectWifiJoin.Failure.FAILED -> tr("Couldn't join it from here") to
            tr("Join {0} from the Wi-Fi menu instead. The password is {1}.", DirectWifi.SSID, DirectWifi.PASSWORD)
        DirectWifiJoin.Failure.LOST -> tr("The CrossDrop Wi-Fi ended") to
            tr("The phone stopped it, or it's out of range.")
    }
}
