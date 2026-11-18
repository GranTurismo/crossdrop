package com.crossdrop.ui.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossdrop.contacts.ContactInbox
import com.crossdrop.core.i18n.tr
import com.crossdrop.ui.CdIcons
import com.crossdrop.ui.ChipButton
import com.crossdrop.ui.ContactInitials
import com.crossdrop.ui.GlassCard
import com.crossdrop.ui.theme.Accent
import com.crossdrop.ui.theme.GlassRaised
import com.crossdrop.ui.theme.HairlineStrong
import com.crossdrop.ui.theme.OnAccent
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.theme.TextPrimary
import kotlinx.coroutines.delay

/**
 * Floating result of contact cards that arrived: who was added (with a way to open them), who was
 * already there, or — without contacts access — a one-tap way to add what was kept in the inbox.
 */
@Composable
fun ContactDropCard(
    drop: ContactInbox.Drop,
    onView: (android.net.Uri) -> Unit,
    onAllow: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = drop.names.firstOrNull() ?: tr("Contact")
    val n = drop.names.size
    val waiting = drop.kept != null
    // A finished add goes away by itself; a card waiting for access stays until answered.
    if (!waiting) LaunchedEffect(drop.id) { delay(12_000); onDone() }

    val title = when {
        drop.unreadable -> tr("Couldn't read that contact card")
        waiting -> if (n == 1) tr("Add {0} to your contacts?", first) else tr("Add {0} contacts?", n)
        drop.added > 0 && drop.skipped == 0 -> if (drop.added == 1) tr("Added {0}", first) else tr("Added {0} contacts", drop.added)
        drop.added == 0 && drop.skipped > 0 ->
            if (drop.skipped == 1 && n == 1) tr("{0} is already in your contacts", first) else tr("Already in your contacts")
        drop.added > 0 -> tr("Added {0} · {1} already there", drop.added, drop.skipped)
        else -> tr("Couldn't add the contact")
    }
    val subtitle = when {
        drop.unreadable -> tr("From {0} · saved to your Inbox", drop.peerName)
        waiting -> tr("From {0} · CrossDrop needs contacts access. Until then it's in your Inbox.", drop.peerName)
        else -> tr("From {0}", drop.peerName)
    }

    GlassCard(
        modifier.widthIn(max = 520.dp).fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        radius = 24.dp, fill = GlassRaised, border = HairlineStrong,
        glow = Accent.copy(alpha = 0.16f), glowRadius = 30.dp,
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ContactInitials(if (n == 1) first else "$n", size = 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = TextDim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            when {
                waiting && !drop.unreadable -> {
                    ChipButton(tr("Later"), onDone)
                    ChipButton(tr("Allow"), onAllow, icon = CdIcons.Contacts, fg = OnAccent, bg = Accent)
                }
                drop.viewUri != null -> {
                    ChipButton(tr("Done"), onDone)
                    ChipButton(tr("View"), { onView(drop.viewUri) }, icon = CdIcons.Contacts, fg = OnAccent, bg = Accent)
                }
                else -> ChipButton(tr("Done"), onDone)
            }
        }
    }
}
