package com.crossdrop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.sp
import com.crossdrop.di.AppContainer
import com.crossdrop.service.TransferService
import com.crossdrop.ui.inbox.InboxScreen
import com.crossdrop.ui.radar.RadarScreen
import com.crossdrop.ui.settings.SettingsScreen
import com.crossdrop.ui.theme.DeepSpace
import com.crossdrop.ui.theme.DeepSpaceSoft
import com.crossdrop.ui.theme.RippleTeal
import com.crossdrop.ui.theme.TextDim
import com.crossdrop.ui.transfer.TransferScreen
import com.crossdrop.ui.transfers.TransfersScreen

enum class Tab(val label: String, val glyph: String) {
    RADAR("Radar", "◎"),
    TRANSFERS("Transfers", "⇅"),
    INBOX("Inbox", "▼"),
    SETTINGS("Settings", "⚙"),
}

/**
 * App shell: four tabs (radar / transfers / inbox / settings) behind a bottom
 * bar — or a nav rail on wide screens. The transfer view slides over
 * everything as a full-screen overlay, and inbound offers stay modal.
 */
@Composable
fun Root(container: AppContainer) {
    val peers by container.peers.collectAsState()
    val session by container.coordinator.session.collectAsState()
    val incomingOffer by container.coordinator.incomingOffer.collectAsState()
    val deviceName by container.deviceName.collectAsState()
    val nsdEnabled by container.nsdEnabled.collectAsState()
    val bleEnabled by container.bleEnabled.collectAsState()
    val listenerPort by container.listenerPort.collectAsState()

    var tabName by rememberSaveable { mutableStateOf(Tab.RADAR.name) }
    val tab = Tab.valueOf(tabName)
    var inboxCount by remember { mutableIntStateOf(container.inbox.list().size) }
    var historyTick by remember { mutableIntStateOf(0) }
    var inboxTick by remember { mutableIntStateOf(0) }

    // Files may land while we're anywhere in the app — keep badges fresh.
    LaunchedEffect(session) { inboxCount = container.inbox.list().size }

    val wide = LocalConfiguration.current.screenWidthDp >= 600

    val content: @Composable () -> Unit = {
        when (tab) {
            Tab.RADAR -> RadarScreen(
                selfName = deviceName,
                peers = peers.values.sortedBy { it.lastSeenMs },
                onSendTo = { peer, uris ->
                    TransferService.start(container.contextForService())
                    container.coordinator.sendTo(peer, uris)
                },
                onRename = container::setDeviceName,
                onPermissionsGranted = container::restartDiscovery,
                selfPort = listenerPort,
            )
            Tab.TRANSFERS -> TransfersScreen(
                session = session,
                history = container.history,
                onClearHistory = { container.history.clear(); historyTick++ },
                refreshSignal = listOf(session?.status, historyTick),
            )
            Tab.INBOX -> InboxScreen(
                inbox = container.inbox,
                onChanged = { inboxCount = container.inbox.list().size },
                refreshSignal = listOf(session?.status, inboxTick),
            )
            Tab.SETTINGS -> SettingsScreen(
                deviceName = deviceName,
                instanceId = container.instanceId,
                port = listenerPort,
                nsdEnabled = nsdEnabled,
                bleEnabled = bleEnabled,
                onRename = container::setDeviceName,
                onSetNsd = container::setNsdEnabled,
                onSetBle = container::setBleEnabled,
                inboxStats = { container.inbox.list().let { it.size to it.sumOf { f -> f.size } } },
                onWipeInbox = {
                    container.inbox.shredAll()
                    inboxCount = 0
                    inboxTick++
                },
                onClearHistory = { container.history.clear(); historyTick++ },
                onSelfTest = if (com.crossdrop.BuildConfig.DEBUG)
                    ({ container.coordinator.selfTransfer() }) else null,
                onAddPeer = if (com.crossdrop.BuildConfig.DEBUG)
                    ({ h, p -> container.injectPeer(null, h, p) }) else null,
                refreshSignal = listOf(session?.status, inboxTick, historyTick),
            )
        }
    }

    Box(Modifier.fillMaxSize().background(DeepSpace)) {
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(containerColor = DeepSpaceSoft) {
                    Tab.entries.forEach { t ->
                        NavigationRailItem(
                            selected = tab == t,
                            onClick = { tabName = t.name },
                            icon = { TabGlyph(t, if (t == Tab.INBOX) inboxCount else 0) },
                            label = { Text(t.label, fontSize = 11.sp) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = RippleTeal,
                                selectedTextColor = RippleTeal,
                                unselectedIconColor = TextDim,
                                unselectedTextColor = TextDim,
                                indicatorColor = RippleTeal.copy(alpha = 0.12f),
                            ),
                        )
                    }
                }
                Box(Modifier.weight(1f)) { content() }
            }
        } else {
            Scaffold(
                containerColor = DeepSpace,
                bottomBar = {
                    NavigationBar(containerColor = DeepSpaceSoft) {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tabName = t.name },
                                icon = { TabGlyph(t, if (t == Tab.INBOX) inboxCount else 0) },
                                label = { Text(t.label, fontSize = 11.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = RippleTeal,
                                    selectedTextColor = RippleTeal,
                                    unselectedIconColor = TextDim,
                                    unselectedTextColor = TextDim,
                                    indicatorColor = RippleTeal.copy(alpha = 0.12f),
                                ),
                            )
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) { content() }
            }
        }

        // ---- full-screen transfer overlay (the signature orb experience)
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
    }
}

@Composable
private fun TabGlyph(tab: Tab, badgeCount: Int) {
    BadgedBox(
        badge = {
            if (badgeCount > 0) {
                Badge(containerColor = RippleTeal, contentColor = DeepSpace) {
                    Text("$badgeCount")
                }
            }
        },
    ) {
        Text(tab.glyph, fontSize = 17.sp)
    }
}
