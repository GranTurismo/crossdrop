package com.crossdrop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.crossdrop.di.AppContainer
import com.crossdrop.ui.Root
import com.crossdrop.ui.theme.CrossDropTheme
import java.awt.Dimension
import java.awt.Taskbar

const val APP_VERSION = "0.3.0"

/**
 * CrossDrop for macOS. Closing the window hides it to the menu bar so the
 * receiver keeps listening (like the Android foreground service); an inbound
 * offer pops the window forward. Quit from the menu bar icon or ⌘Q.
 */
fun main() = application {
    val container = remember { AppContainer().also { it.start() } }
    var visible by remember { mutableStateOf(true) }
    val trayState = rememberTrayState()
    val windowState = rememberWindowState(
        size = DpSize(1080.dp, 760.dp),
        position = WindowPosition.Aligned(Alignment.Center),
    )
    val incoming by container.coordinator.incomingOffer.collectAsState()

    DisposableEffect(Unit) {
        // Clicking the Dock icon while hidden brings the window back.
        runCatching {
            java.awt.Desktop.getDesktop().addAppEventListener(
                java.awt.desktop.AppReopenedListener { visible = true })
        }
        onDispose { container.shutdown() }
    }

    Tray(
        icon = painterResource("tray.png"),
        tooltip = "CrossDrop",
        onAction = { visible = true },
        menu = {
            Item("Show CrossDrop") { visible = true }
            Separator()
            Item("Quit CrossDrop") { exitApplication() }
        },
    )

    Window(
        onCloseRequest = { visible = false },
        visible = visible,
        title = "CrossDrop",
        icon = painterResource("icon.png"),
        state = windowState,
    ) {
        window.minimumSize = Dimension(780, 580)

        LaunchedEffect(incoming?.offer?.transferId) {
            val offer = incoming ?: return@LaunchedEffect
            visible = true
            window.toFront()
            window.requestFocus()
            runCatching { Taskbar.getTaskbar().requestUserAttention(true, false) }
            trayState.sendNotification(Notification(
                "${offer.peerName} wants to share",
                "${offer.offer.files.size} file${if (offer.offer.files.size == 1) "" else "s"} — open CrossDrop to accept",
            ))
        }

        CrossDropTheme { Root(container) }
    }
}
