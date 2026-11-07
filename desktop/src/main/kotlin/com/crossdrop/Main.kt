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
import com.crossdrop.platform.Platform
import com.crossdrop.platform.SingleInstance
import com.crossdrop.ui.Root
import com.crossdrop.ui.theme.CrossDropTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.awt.Dimension
import java.awt.Frame
import java.awt.SystemTray
import java.awt.Taskbar

const val APP_VERSION = "0.3.0"

/**
 * CrossDrop for macOS and Windows. Closing the window hides it to the menu bar /
 * system tray so the receiver keeps listening (like the Android foreground
 * service); an inbound offer pops the window forward. Quit from the tray icon
 * (or ⌘Q on a Mac). On Windows only one copy runs per user: launching it again
 * just raises the running one ([SingleInstance]).
 */
fun main() {
    // Bumped by a second launch (Windows) to ask the running window to surface.
    val showRequests = MutableStateFlow(0)
    val singleInstance = if (Platform.isWindows) SingleInstance() else null
    if (singleInstance != null && !singleInstance.acquire { showRequests.update { it + 1 } }) return

    application {
        val container = remember { AppContainer().also { it.start() } }
        var visible by remember { mutableStateOf(true) }
        val trayState = rememberTrayState()
        // Without a tray (some locked-down Windows SKUs) a hidden window could never be
        // reopened, so closing the window must quit instead.
        val trayOk = remember { SystemTray.isSupported() }
        val windowState = rememberWindowState(
            size = DpSize(1080.dp, 760.dp),
            position = WindowPosition.Aligned(Alignment.Center),
        )
        val incoming by container.coordinator.incomingOffer.collectAsState()
        val showTick by showRequests.collectAsState()

        DisposableEffect(Unit) {
            // macOS: clicking the Dock icon while hidden brings the window back.
            if (Platform.isMac) runCatching {
                java.awt.Desktop.getDesktop().addAppEventListener(
                    java.awt.desktop.AppReopenedListener { visible = true })
            }
            onDispose {
                container.shutdown()
                singleInstance?.release()
            }
        }

        if (trayOk) Tray(
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
            onCloseRequest = { if (trayOk) visible = false else exitApplication() },
            visible = visible,
            title = "CrossDrop",
            icon = painterResource("icon.png"),
            state = windowState,
        ) {
            window.minimumSize = Dimension(780, 580)

            LaunchedEffect(showTick) {
                if (showTick == 0) return@LaunchedEffect
                visible = true
                window.raiseWhenShown()
            }

            LaunchedEffect(incoming?.offer?.transferId) {
                val offer = incoming ?: return@LaunchedEffect
                visible = true
                window.raiseWhenShown()
                runCatching {
                    val taskbar = Taskbar.getTaskbar()
                    when {
                        Platform.isWindows && taskbar.isSupported(Taskbar.Feature.USER_ATTENTION_WINDOW) ->
                            taskbar.requestWindowUserAttention(window)
                        taskbar.isSupported(Taskbar.Feature.USER_ATTENTION) ->
                            taskbar.requestUserAttention(true, false)
                    }
                }
                if (trayOk) trayState.sendNotification(Notification(
                    "${offer.peerName} wants to share",
                    "${offer.offer.files.size} file${if (offer.offer.files.size == 1) "" else "s"} — open CrossDrop to accept",
                ))
            }

            CrossDropTheme { Root(container) }
        }
    }
}

/**
 * `visible = true` only reaches the AWT frame on the next recomposition, and raising a frame that
 * is not showing yet is a no-op — so wait (bounded) until it is, then raise it.
 */
private suspend fun Frame.raiseWhenShown() {
    var waitedMs = 0L
    while (!isShowing && waitedMs < 1_000) { delay(25); waitedMs += 25 }
    bringToFront()
}

/**
 * Raise and focus the window. Windows refuses to hand focus to a background app
 * (a bare `toFront()` only flashes the taskbar button), so there it is made
 * briefly topmost, which reliably surfaces it.
 */
private fun Frame.bringToFront() {
    if (state == Frame.ICONIFIED) state = Frame.NORMAL
    if (Platform.isWindows) {
        isAlwaysOnTop = true
        toFront()
        requestFocus()
        isAlwaysOnTop = false
    } else {
        toFront()
        requestFocus()
    }
}
