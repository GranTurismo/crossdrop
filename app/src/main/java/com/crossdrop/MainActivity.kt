package com.crossdrop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.crossdrop.ui.Root
import com.crossdrop.ui.theme.CrossDropTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as CrossDropApp).container
        handleDevPeer(intent, container)
        setContent {
            // Arabic and Persian read right to left, whatever the phone's own language is.
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalLayoutDirection provides
                    if (com.crossdrop.core.i18n.I18n.isRtl()) androidx.compose.ui.unit.LayoutDirection.Rtl
                    else androidx.compose.ui.unit.LayoutDirection.Ltr,
            ) {
                CrossDropTheme {
                    Root(container)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        (application as CrossDropApp).container.vaultExplorer.livePaused = false
        com.crossdrop.service.OfferNotifier.clear(this)
    }

    override fun onPause() {
        super.onPause()
        foreground = false
        (application as CrossDropApp).container.vaultExplorer.livePaused = true     // nobody is watching the drive list
    }

    companion object {
        /** The app's own screen is showing — incoming offers appear there, not as notifications. */
        @Volatile var foreground = false
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleDevPeer(intent, (application as CrossDropApp).container)
    }

    /** Dev hook: -e dev_peer 10.0.2.17:44885 fires a self-test at that endpoint. */
    private fun handleDevPeer(intent: android.content.Intent?, container: com.crossdrop.di.AppContainer) {
        if (!BuildConfig.DEBUG) return
        // -e dev_skip_welcome 1 — a fresh install goes straight to the radar (the first-run tour would cover it).
        if (intent?.hasExtra("dev_skip_welcome") == true) container.welcomeSeen = true
        // -e dev_directlink on|fail — shows the "Connect directly" states without a hotspot (emulators can't make one).
        intent?.getStringExtra("dev_directlink")?.let {
            // The code carries this device's real address and port, so another emulator can "scan" it.
            android.os.Handler(mainLooper).postDelayed({
                container.directLink.forceStateForDebug(
                    if (it == "on") com.crossdrop.core.connect.DirectLinkState.On(
                        "AndroidShare_4821", "k9Qm-x2Lp", "WPA",
                        com.crossdrop.net.LocalIp.wifiIpv4(), container.listenerPort.value, container.instanceId,
                    )
                    else com.crossdrop.core.connect.DirectLinkState.Failed(com.crossdrop.core.connect.DirectLinkFailure.BUSY),
                )
            }, 1_500)
        }
        // -e dev_ble_peer 'Galaxy S24' — a phone the radar only hears over Bluetooth (tap it: show/scan sheet).
        intent?.getStringExtra("dev_ble_peer")?.let { container.injectNearbyPeer(it) }
        // -e dev_backup_only 'Pictures/CrossDropTest/' — backups (and freeing up space) see that folder only.
        intent?.getStringExtra("dev_backup_only")?.let { container.backup.debugOnlyPath = it }
        // -e dev_join '<WIFI:… code>' joins for real (system dialog); dev_join_skip_wifi skips the Wi-Fi step
        // (emulators can't join each other's hotspot) and goes straight to connect + announce.
        // Android only takes a join request from an app on screen: wait until we are.
        intent?.getStringExtra("dev_join")?.let { com.crossdrop.core.connect.WifiQr.parse(it) }?.let { code ->
            android.os.Handler(mainLooper).postDelayed({ container.wifiJoin.join(code) }, 2_000)
        }
        intent?.getStringExtra("dev_join_skip_wifi")?.let { com.crossdrop.core.connect.WifiQr.parse(it) }?.let { code ->
            android.os.Handler(mainLooper).postDelayed({ container.wifiJoin.connectForDebug(code) }, 1_500)
        }
        android.util.Log.i("DevPeer", "extras=${intent?.extras?.keySet()?.joinToString()} dev_peer=${intent?.getStringExtra("dev_peer")}")
        // Comma-separated for several peers: -e dev_peer 10.0.2.2:5001,10.0.2.2:5002
        intent?.getStringExtra("dev_peer")?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }?.forEach { peer ->
            val host = peer.substringBefore(':')
            val port = peer.substringAfter(':', "0").toIntOrNull() ?: 0
            android.os.Handler(mainLooper).postDelayed(
                { container.injectPeer(null, host, port) }, 1500)
        }
        intent?.getStringExtra("dev_send")?.takeIf { it.isNotBlank() }?.let { peer ->
            val host = peer.substringBefore(':')
            val port = peer.substringAfter(':', "0").toIntOrNull() ?: 0
            android.os.Handler(mainLooper).postDelayed(
                { container.coordinator.selfTransfer(host, port) }, 1500)
        }
        // -e dev_send_contact host:port — a made-up contact card (never the address book).
        intent?.getStringExtra("dev_send_contact")?.takeIf { it.isNotBlank() }?.let { peer ->
            val host = peer.substringBefore(':')
            val port = peer.substringAfter(':', "0").toIntOrNull() ?: 0
            android.os.Handler(mainLooper).postDelayed({
                val card = com.crossdrop.core.contacts.VContact(
                    given = "Test", family = "Card", org = "CrossDrop QA",
                    phones = listOf(com.crossdrop.core.contacts.VContact.Labeled("+1 555 0199", "CELL")),
                    emails = listOf(com.crossdrop.core.contacts.VContact.Labeled("test.card@example.com")),
                )
                container.coordinator.sendContacts(
                    listOf(com.crossdrop.core.Peer("dev-$host:$port", "dev $host", com.crossdrop.core.Endpoint(host, port))),
                    listOf(card),
                )
            }, 1500)
        }
        if (intent?.getStringExtra("dev_share") != null) {
            android.os.Handler(mainLooper).postDelayed(
                { container.qrShare.startDebug() }, 1500)
        }
        // -e dev_vault rw|ro|pin — host a vault seeded with test files
        intent?.getStringExtra("dev_vault")?.let { mode ->
            android.os.Handler(mainLooper).postDelayed({
                seedVault(container)
                container.vaultHost.start(
                    writable = mode != "ro", usePin = mode == "pin")
                com.crossdrop.service.TransferService.start(
                    container.contextForService())
            }, 1500)
        }
        // -e dev_vault_mount host[:port] — mount a vault directly (no mDNS on
        // emu); port 0/omitted loops back to our own vault once it's bound.
        intent?.getStringExtra("dev_vault_mount")?.takeIf { it.isNotBlank() }?.let { t ->
            val host = t.substringBefore(':')
            val port = t.substringAfter(':', "0").toIntOrNull() ?: 0
            val pin = intent.getStringExtra("dev_vault_pin")
            android.os.Handler(mainLooper).postDelayed(
                { container.mountVaultAt(host, port, pin) }, 2500)
        }
        // -e dev_vault_peer host[:port] — put a vault beacon on the radar;
        // port 0 resolves to our own hosted vault (self beacons are filtered).
        intent?.getStringExtra("dev_vault_peer")?.takeIf { it.isNotBlank() }?.let { t ->
            val host = t.substringBefore(':')
            val port = t.substringAfter(':', "0").toIntOrNull() ?: 0
            android.os.Handler(mainLooper).postDelayed(
                { container.injectVaultPeer(host, port) }, 2500)
        }
    }

    /** Debug: drop a few files into the vault root so the explorer has content. */
    private fun seedVault(container: com.crossdrop.di.AppContainer) {
        val dir = java.io.File(container.contextForService().filesDir, "vault")
        java.io.File(dir, "docs").mkdirs()
        java.io.File(dir, "docs/welcome.txt").writeText(
            "CrossDrop LAN vault — hello from the host.\n")
        java.io.File(dir, "media").mkdirs()
        java.io.File(dir, "media/big.bin").writeBytes(
            ByteArray(3 * 1024 * 1024) { (it % 251).toByte() })
        java.io.File(dir, "readme.md").writeText("# vault\nServed over LAN.\n")
    }
}
