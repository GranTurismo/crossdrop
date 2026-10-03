package com.crossdrop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.crossdrop.di.AppContainer
import com.crossdrop.service.TransferService
import com.crossdrop.ui.inbox.InboxSheet
import com.crossdrop.ui.radar.RadarScreen
import com.crossdrop.ui.theme.DeepSpace
import com.crossdrop.ui.transfer.TransferScreen

/**
 * Navigation is one screen (the radar) plus two overlays — a full-screen
 * transfer view sliding up over it, and modal sheets for offers + inbox.
 * No navigation library needed at this scale.
 */
@Composable
fun Root(container: AppContainer) {
    val peers by container.peers.collectAsState()
    val session by container.coordinator.session.collectAsState()
    val incomingOffer by container.coordinator.incomingOffer.collectAsState()
    val deviceName by container.deviceName.collectAsState()

    var inboxOpen by remember { mutableStateOf(false) }
    var inboxCount by remember { mutableIntStateOf(container.inbox.list().size) }

    // Recount whenever a receive might have landed files.
    androidx.compose.runtime.LaunchedEffect(session) {
        inboxCount = container.inbox.list().size
    }

    Box(Modifier.fillMaxSize().background(DeepSpace)) {
        RadarScreen(
            selfName = deviceName,
            peers = peers.values.sortedBy { it.lastSeenMs },
            inboxCount = inboxCount,
            onSendTo = { peer, uris ->
                TransferService.start(container.contextForService())
                container.coordinator.sendTo(peer, uris)
            },
            onOpenInbox = { inboxOpen = true },
            onRename = container::setDeviceName,
            onPermissionsGranted = container::restartDiscovery,
        )

        AnimatedVisibility(
            visible = session != null,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            session?.let { s ->
                Surface(Modifier.fillMaxSize(), color = DeepSpace) {
                    TransferScreen(
                        session = s,
                        onCancel = container.coordinator::cancelSession,
                        onClose = container.coordinator::dismissSession,
                    )
                }
            }
        }

        incomingOffer?.let { offer ->
            IncomingOfferSheet(offer) { accept ->
                if (accept) TransferService.start(container.contextForService())
                container.coordinator.respondToOffer(accept)
            }
        }

        if (inboxOpen) {
            InboxSheet(
                inbox = container.inbox,
                onChanged = { inboxCount = container.inbox.list().size },
                onDismiss = {
                    inboxOpen = false
                    inboxCount = container.inbox.list().size
                },
            )
        }
    }
}
