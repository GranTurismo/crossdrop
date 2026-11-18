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
import com.crossdrop.core.i18n.tr
import com.crossdrop.core.i18n.trPlural
import com.crossdrop.di.AppContainer
import com.crossdrop.platform.Platform
import com.crossdrop.platform.SingleInstance
import com.crossdrop.ui.Root
import com.crossdrop.ui.theme.CrossDropTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.awt.Dimension
import java.io.File
import java.awt.Frame
import java.awt.SystemTray
import java.awt.Taskbar

const val APP_VERSION = "0.6.0"

/**
 * CrossDrop for macOS and Windows. Closing the window hides it to the menu bar /
 * system tray so the receiver keeps listening (like the Android foreground
 * service); an inbound offer pops the window forward. Quit from the tray icon
 * (or ⌘Q on a Mac). On Windows only one copy runs per user: launching it again
 * just raises the running one ([SingleInstance]).
 */
fun main(args: Array<String>) {
    // The UI language follows the operating system's language.
    // A language picked in the app's language list ("Automatic" or a language) wins over the OS language.
    com.crossdrop.core.i18n.I18n.install(
        source = { lang -> com.crossdrop.core.i18n.I18n::class.java.getResourceAsStream("/l10n/$lang.json")?.bufferedReader()?.use { it.readText() } },
        savedChoice = runCatching { com.crossdrop.platform.Prefs().getString("language") }.getOrNull(),
    )
    com.crossdrop.ui.language.LanguageReactivity.install()
    // Bumped by a second launch (Windows) to ask the running window to surface.
    val showRequests = MutableStateFlow(0)
    // Files handed to the app — launch arguments (Explorer "Send to"), Finder "Open With", a file
    // dropped on the Dock icon, or a second launch — wait here until the UI picks them up.
    val opened = MutableStateFlow(args.map(::File).filter { it.isFile })
    val singleInstance = if (Platform.isWindows) SingleInstance() else null
    if (singleInstance != null && !singleInstance.acquire(opened.value) { files ->
            if (files.isNotEmpty()) opened.value = files
            showRequests.update { it + 1 }
        }) return
    // macOS delivers "open these files" as an app event, possibly before the window exists.
    if (Platform.isMac) runCatching {
        java.awt.Desktop.getDesktop().setOpenFileHandler { e ->
            opened.value = e.files.filter { it.isFile }
            showRequests.update { it + 1 }
        }
    }

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
        val openedFiles by opened.collectAsState()
        LaunchedEffect(openedFiles) {
            if (openedFiles.isNotEmpty()) {
                container.openFiles(openedFiles)
                opened.value = emptyList()
            }
        }

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
                Item(tr("Show CrossDrop")) { visible = true }
                Separator()
                Item(tr("Quit CrossDrop")) { exitApplication() }
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
            // Files dragged onto the window wait on the radar for a device pick.
            DisposableEffect(Unit) {
                window.dropTarget = object : java.awt.dnd.DropTarget() {
                    @Synchronized
                    override fun drop(evt: java.awt.dnd.DropTargetDropEvent) {
                        runCatching {
                            evt.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY)
                            @Suppress("UNCHECKED_CAST")
                            val files = evt.transferable.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as List<File>
                            evt.dropComplete(true)
                            opened.value = files.filter { it.isFile }
                        }.onFailure { evt.dropComplete(false) }
                    }
                }
                onDispose { window.dropTarget = null }
            }

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
                val fileCount = offer.offer.files.size
                if (trayOk) trayState.sendNotification(Notification(
                    tr("{0} wants to share", offer.peerName),
                    when (offer.kind) {
                        com.crossdrop.core.protocol.OfferKind.TEXT -> if (offer.text != null) tr("A link or note — open CrossDrop to see it")
                            else trPlural(fileCount, "1 file — open CrossDrop to accept", "{n} files — open CrossDrop to accept")
                        com.crossdrop.core.protocol.OfferKind.CONTACT -> offer.contacts.takeIf { it.isNotEmpty() }
                            ?.let { tr("Contact card: {0} — open CrossDrop to save it", com.crossdrop.core.contacts.ContactShare.title(it)) }
                            ?: tr("A contact card — open CrossDrop to save it")
                        com.crossdrop.core.protocol.OfferKind.CAMERA -> tr("Their camera, live on this screen — open CrossDrop to watch")
                        com.crossdrop.core.protocol.OfferKind.SWITCH -> tr("Their photos, videos and contacts — open CrossDrop to accept")
                        else -> trPlural(fileCount, "1 file — open CrossDrop to accept", "{n} files — open CrossDrop to accept")
                    },
                ))
            }

            // Arabic and Persian read right to left; the window follows.
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalLayoutDirection provides
                    if (com.crossdrop.core.i18n.I18n.isRtl()) androidx.compose.ui.unit.LayoutDirection.Rtl
                    else androidx.compose.ui.unit.LayoutDirection.Ltr,
            ) { CrossDropTheme { Root(container) } }
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
